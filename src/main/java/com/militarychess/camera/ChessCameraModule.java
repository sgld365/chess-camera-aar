package com.militarychess.camera;

import android.app.Activity;
import android.content.Context;
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
     * 获取应用上下文（不依赖 mUniSDKInstance 字段，兼容不同版本 uni-app SDK）
     */
    private Context getAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Exception e) {
            Log.e(TAG, "getAppContext failed", e);
            return null;
        }
    }

    @UniJSMethod(uiThread = true)
    public void openCamera(JSONObject options, UniJSCallback callback) {
        try {
            sCallback = callback;
            Context ctx = getAppContext();
            if (ctx == null) {
                JSONObject err = new JSONObject();
                err.put("code", -1);
                err.put("message", "无法获取应用上下文");
                callback.invoke(err);
                return;
            }

            String overlayPath = options.getString("overlay_path");
            double alpha = options.getDoubleValue("alpha");
            if (alpha <= 0 || alpha > 1.0) alpha = 0.3;
            String savePath = options.getString("save_path");

            Log.d(TAG, "openCamera: overlay len=" + (overlayPath != null ? overlayPath.length() : 0)
                    + ", alpha=" + alpha + ", savePath=" + savePath);

            Intent intent = new Intent(ctx, CameraActivity.class);
            intent.putExtra("overlay_image", overlayPath);
            intent.putExtra("overlay_alpha", (float) alpha);
            if (savePath != null) intent.putExtra("save_path", savePath);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Log.d(TAG, "startActivity succeeded");
        } catch (Throwable t) {
            Log.e(TAG, "openCamera ERROR: " + t.getMessage(), t);
            JSONObject err = new JSONObject();
            err.put("code", -1);
            err.put("message", "" + t.getClass().getSimpleName() + ": " + t.getMessage());
            if (sCallback != null) {
                try { sCallback.invoke(err); } catch (Throwable ignored) {}
                sCallback = null;
            }
        }
    }
}
