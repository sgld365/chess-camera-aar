package com.militarychess.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

/**
 * 半透明叠加层 View
 * 画在 CameraX PreviewView 之上，显示标定网格图作为取景辅助
 *
 * debug 模式：当 overlayBitmap 为空时，画红色测试网格
 */
public class OverlayView extends View {

    private static final String TAG = "OverlayView";

    private Bitmap overlayBitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float alpha = 0.3f;

    public OverlayView(Context context) {
        super(context);
        init();
    }

    private void init() {
        paint.setFilterBitmap(true);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setOverlay(Bitmap bitmap, float alpha) {
        this.overlayBitmap = bitmap;
        this.alpha = Math.max(0.1f, Math.min(1.0f, alpha));
        Log.d(TAG, "setOverlay: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null")
                + ", alpha=" + this.alpha);
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int vw = getWidth();
        int vh = getHeight();

        if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
            int bw = overlayBitmap.getWidth();
            int bh = overlayBitmap.getHeight();
            if (bw > 0 && bh > 0 && vw > 0 && vh > 0) {
                float scale = Math.min((float) vw / bw, (float) vh / bh);
                int drawW = (int) (bw * scale);
                int drawH = (int) (bh * scale);
                int offsetX = (vw - drawW) / 2;
                int offsetY = (vh - drawH) / 2;

                paint.setAlpha((int) (alpha * 255));
                paint.setColor(Color.WHITE);
                canvas.drawBitmap(overlayBitmap,
                        new Rect(0, 0, bw, bh),
                        new Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH),
                        paint);
                Log.d(TAG, "drew overlay bitmap " + drawW + "x" + drawH);
                return;
            }
        }

        // 🚨 DEBUGBUILD: 没有 overlay 时画红色测试网格
        Log.w(TAG, "no overlay bitmap, drawing test grid");
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setAlpha(180);

        int cols = 17;
        int rows = 17;
        float cellW = (float) vw / cols;
        float cellH = (float) vh / rows;

        // 红色网格线
        paint.setColor(Color.RED);
        for (int i = 0; i <= cols; i++) {
            float x = i * cellW;
            canvas.drawLine(x, 0, x, vh, paint);
        }
        for (int i = 0; i <= rows; i++) {
            float y = i * cellH;
            canvas.drawLine(0, y, vw, y, paint);
        }

        // 中心十字（绿色）
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(8);
        float cx = vw / 2f;
        float cy = vh / 2f;
        canvas.drawLine(cx - 40, cy, cx + 40, cy, paint);
        canvas.drawLine(cx, cy - 40, cx, cy + 40, paint);

        // 文字
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(48);
        paint.setColor(Color.YELLOW);
        canvas.drawText("调试模式 - 无overlay图片", vw / 6f, vh / 2f + 60, paint);
    }
}
