package com.militarychess.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Camera2 + TextureView 相机拍照 Activity
 * 支持半透明网格叠加（OverlayView）
 *
 * 接收参数:
 *   overlay_image: string — 半透明网格图 base64
 *   overlay_alpha: float — 透明度 0.0-1.0
 *
 * 回调返回:
 *   成功: { code: 0, photoPath: "..." }
 *   失败: { code: -1, message: "..." }
 *
 * 纯 Android SDK，无 CameraX/Guava 外部依赖。
 */
public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    private TextureView textureView;
    private OverlayView overlayView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewBuilder;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Size previewSize;
    private ImageReader imageReader;
    private String savePath;
    private boolean isCapturing = false;

    // 来自 Intent 的参数
    private String overlayB64;
    private float overlayAlpha = 0.3f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 读取参数
        overlayB64 = getIntent().getStringExtra("overlay_image");
        overlayAlpha = getIntent().getFloatExtra("overlay_alpha", 0.3f);
        if (overlayAlpha <= 0 || overlayAlpha > 1.0f) overlayAlpha = 0.3f;
        savePath = getIntent().getStringExtra("save_path");
        if (savePath == null || savePath.isEmpty()) {
            savePath = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    + "/chess_camera_" + System.currentTimeMillis() + ".jpg";
        }

        Log.d(TAG, "overlay len=" + (overlayB64 != null ? overlayB64.length() : 0)
                + ", alpha=" + overlayAlpha + ", savePath=" + savePath);

        // 检查权限
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }

        initUI();
        openCamera();
    }

    private void initUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 1. Camera2 预览 - TextureView
        textureView = new TextureView(this);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(textureView);

        // 2. 半透明网格叠加层
        overlayView = new OverlayView(this);
        overlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlayView.setClickable(false);
        overlayView.setFocusable(false);
        root.addView(overlayView);

        // 加载 overlay 图片
        if (overlayB64 != null && !overlayB64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(overlayB64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    overlayView.setOverlay(bmp, overlayAlpha);
                    Log.d(TAG, "overlay loaded: " + bmp.getWidth() + "x" + bmp.getHeight());
                }
            } catch (Exception e) {
                Log.w(TAG, "overlay decode failed: " + e.getMessage(), e);
            }
        }

        // 3. 底部按钮
        LinearLayout bottomBar = new LinearLayout(this);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.bottomMargin = dpToPx(40);
        bottomBar.setLayoutParams(bottomParams);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("✕ 取消");
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setTextSize(16);
        cancelBtn.setBackgroundColor(Color.argb(180, 200, 60, 60));
        cancelBtn.setPadding(dpToPx(24), dpToPx(10), dpToPx(24), dpToPx(10));
        cancelBtn.setOnClickListener(v -> {
            JSONObject ret = new JSONObject();
            ret.put("code", -1);
            ret.put("message", "用户取消");
            if (ChessCameraModule.sCallback != null) {
                ChessCameraModule.sCallback.invoke(ret);
                ChessCameraModule.sCallback = null;
            }
            finish();
        });

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));

        Button captureBtn = new Button(this);
        captureBtn.setText("📸 拍照");
        captureBtn.setTextSize(18);
        captureBtn.setTextColor(Color.WHITE);
        captureBtn.setBackgroundColor(Color.argb(220, 46, 204, 113));
        captureBtn.setPadding(dpToPx(36), dpToPx(12), dpToPx(36), dpToPx(12));
        captureBtn.setOnClickListener(v -> takePhoto());

        bottomBar.addView(cancelBtn);
        bottomBar.addView(spacer);
        bottomBar.addView(captureBtn);
        root.addView(bottomBar);

        setContentView(root);
    }

    // ===================== Camera2 =====================

    private void openCamera() {
        // TextureView 就绪后启动相机
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "TextureView ready: " + width + "x" + height);
                startBackgroundThread();
                openCameraInternal(width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        // 如果 TextureView 已就绪，直接打开
        if (textureView.isAvailable()) {
            startBackgroundThread();
            openCameraInternal(textureView.getWidth(), textureView.getHeight());
        }
    }

    private void openCameraInternal(int width, int height) {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            final String cameraId = null;

            // 选后置摄像头
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                Toast.makeText(this, "未找到后置摄像头", Toast.LENGTH_SHORT).show();
                finishWithError("未找到后置摄像头");
                return;
            }

            // 获取预览尺寸
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                finishWithError("无法获取摄像头配置");
                return;
            }
            previewSize = map.getOutputSizes(SurfaceTexture.class)[0];

            // 创建 ImageReader（拍照用）
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            Size captureSize = (jpegSizes != null && jpegSizes.length > 0)
                    ? jpegSizes[0] : previewSize;
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                if (isCapturing) return;
                isCapturing = true;
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    File file = new File(savePath);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(bytes);
                        fos.flush();
                    }
                    Log.d(TAG, "photo saved: " + savePath);

                    runOnUiThread(() -> {
                        JSONObject ret = new JSONObject();
                        ret.put("code", 0);
                        ret.put("photoPath", savePath);
                        if (ChessCameraModule.sCallback != null) {
                            ChessCameraModule.sCallback.invoke(ret);
                            ChessCameraModule.sCallback = null;
                        }
                        finish();
                    });
                } catch (IOException e) {
                    Log.e(TAG, "save photo failed: " + e.getMessage(), e);
                    runOnUiThread(() -> {
                        isCapturing = false;
                        Toast.makeText(this, "保存照片失败", Toast.LENGTH_SHORT).show();
                    });
                }
            }, backgroundHandler);

            // 打开相机
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice device) {
                        cameraDevice = device;
                        Log.d(TAG, "Camera opened: " + cameraId);
                        startPreview();
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice device) {
                        device.close();
                        cameraDevice = null;
                    }

                    @Override
                    public void onError(@NonNull CameraDevice device, int error) {
                        device.close();
                        cameraDevice = null;
                        Log.e(TAG, "Camera error: " + error);
                        runOnUiThread(() -> {
                            finishWithError("相机打开失败: " + error);
                        });
                    }
                }, backgroundHandler);
            } else {
                // 低于 Android M 的回退
                Toast.makeText(this, "Android 6.0 以下不支持 Camera2", Toast.LENGTH_LONG).show();
                finishWithError("Android 6.0 以下不支持 Camera2");
            }
        } catch (SecurityException e) {
            finishWithError("相机权限被拒绝");
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed: " + e.getMessage(), e);
            finishWithError("相机启动失败: " + e.getMessage());
        }
    }

    private void startPreview() {
        if (cameraDevice == null || textureView == null) return;
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) return;
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);

            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            previewBuilder.set(CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_AUTO);
                            try {
                                session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
                                Log.d(TAG, "Preview started");
                            } catch (Exception e) {
                                Log.e(TAG, "start preview failed: " + e.getMessage(), e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "configure failed");
                            runOnUiThread(() -> {
                                Toast.makeText(CameraActivity.this, "相机配置失败", Toast.LENGTH_SHORT).show();
                            });
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "startPreview error: " + e.getMessage(), e);
        }
    }

    private void takePhoto() {
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isCapturing) return;

        try {
            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            // 设置方向
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(rotation));

            captureSession.stopRepeating();
            captureSession.capture(captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull android.hardware.camera2.TotalCaptureResult result) {
                            Log.d(TAG, "capture completed");
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "takePhoto error: " + e.getMessage(), e);
            Toast.makeText(this, "拍照失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int getJpegOrientation(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    // ===================== 生命周期 =====================

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        stopBackgroundThread();
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    // ===================== 权限 =====================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initUI();
                openCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_LONG).show();
                finishWithError("相机权限被拒绝");
            }
        }
    }

    // ===================== 工具 =====================

    private void finishWithError(String message) {
        JSONObject ret = new JSONObject();
        ret.put("code", -1);
        ret.put("message", message);
        if (ChessCameraModule.sCallback != null) {
            ChessCameraModule.sCallback.invoke(ret);
            ChessCameraModule.sCallback = null;
        }
        finish();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
