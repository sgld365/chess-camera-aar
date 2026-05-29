package com.militarychess.camera;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class ChessCameraModule extends UniModule {

    private static final String TAG = "ChessCamera";
    public static UniJSCallback sCallback = null;

    /**
     * 打开 CameraX 相机界面
     *
     * JS 参数：
     *   overlay_image: string — 半透明网格图 base64 数据
     *   overlay_alpha: number — 透明度 0.0-1.0
     *
     * 回调：
     *   成功: { code: 0, photoPath: "..." }
     *   失败: { code: -1, message: "..." }
     */
    @UniJSMethod(uiThread = true)
    public void openCamera(JSONObject options, UniJSCallback callback) {
        if (mUniSDKInstance == null || mUniSDKInstance.getContext() == null) {
            JSONObject err = new JSONObject();
            err.put("code", -1);
            err.put("message", "插件未就绪");
            callback.invoke(err);
            return;
        }

        sCallback = callback;

        String overlayPath = options.getString("overlay_path");
        double alpha = options.getDoubleValue("alpha");
        if (alpha <= 0 || alpha > 1.0) alpha = 0.3;
        String savePath = options.getString("save_path");

        Log.d(TAG, "openCamera: overlay_image length=" + (overlayPath != null ? overlayPath.length() : 0)
                + ", alpha=" + alpha + ", savePath=" + savePath);

        Intent intent = new Intent(mUniSDKInstance.getContext(), CameraActivity.class);
        intent.putExtra("overlay_image", overlayPath);
        intent.putExtra("overlay_alpha", (float) alpha);
        if (savePath != null) intent.putExtra("save_path", savePath);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mUniSDKInstance.getContext().startActivity(intent);
            Log.d(TAG, "startActivity succeeded");
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed: " + e.getMessage(), e);
            JSONObject err = new JSONObject();
            err.put("code", -1);
            err.put("message", "启动相机失败: " + e.getMessage());
            sCallback.invoke(err);
            sCallback = null;
        }
    }
}
