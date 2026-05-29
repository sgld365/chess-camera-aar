package com.militarychess.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alibaba.fastjson.JSONObject;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX 拍照 Activity
 *
 * 接收参数:
 *   overlay_image: string — 半透明网格图 base64 数据
 *   overlay_alpha: float — 透明度 0.0-1.0
 *
 * 回调返回:
 *   成功: { code: 0, photoPath: "..." }
 *   失败: { code: -1, message: "..." }
 */
public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    private PreviewView previewView;
    private ImageView overlayView;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private String savePath;
    private boolean isCapturing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }

        initUI();
        startCamera();
    }

    private void initUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 1. CameraX 预览
        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        // 🔥 强制使用 TextureView 模式（对应 SurfaceView 时上层 View 可能不显示）
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(previewView);

        // 2. 半透明网格叠加层 ✅ 使用标准 ImageView，不用自定义 View
        overlayView = new ImageView(this);
        overlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlayView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        overlayView.setClickable(false);
        overlayView.setFocusable(false);
        overlayView.setAlpha(0.5f); // 50% 透明度
        root.addView(overlayView);

        // 从 Intent 读取 base64 图片数据
        String overlayB64 = getIntent().getStringExtra("overlay_image");
        Log.d(TAG, "overlay_image length=" + (overlayB64 != null ? overlayB64.length() : 0));
        if (overlayB64 != null && !overlayB64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(overlayB64, Base64.DEFAULT);
                Bitmap overlayBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                float alpha = getIntent().getFloatExtra("overlay_alpha", 0.3f);
                if (overlayBmp != null) {
                    overlayView.setImageBitmap(overlayBmp);
                    Log.d(TAG, "overlay loaded: " + overlayBmp.getWidth() + "x" + overlayBmp.getHeight());
                } else {
                    Log.w(TAG, "overlay decodeByteArray returned null");
                }
            } catch (Exception e) {
                Log.w(TAG, "overlay decode failed: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "no overlay_image provided");
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

        savePath = getIntent().getStringExtra("save_path");
        if (savePath == null || savePath.isEmpty()) {
            savePath = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    + "/chess_camera_" + System.currentTimeMillis() + ".jpg";
        }

        setContentView(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation()).build();
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                Log.d(TAG, "CameraX started");
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX start failed: " + e.getMessage());
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show();
                finishWithError("相机启动失败: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isCapturing) return;
        isCapturing = true;

        File photoFile = new File(savePath);
        File parentDir = photoFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
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
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "photo error: " + exception.getMessage());
                        runOnUiThread(() -> {
                            isCapturing = false;
                            Toast.makeText(CameraActivity.this,
                                    "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initUI();
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_LONG).show();
                finishWithError("相机权限被拒绝");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
