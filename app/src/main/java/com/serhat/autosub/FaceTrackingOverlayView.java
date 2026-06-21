package com.serhat.autosub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Review-only face lock visualization. It is never part of the exported video. */
public final class FaceTrackingOverlayView extends View {
    private static final int ACCENT = Color.rgb(82, 255, 178);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF face = new RectF();
    private boolean visible;

    public FaceTrackingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        glow.setColor(Color.argb(60, 82, 255, 178));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(5f * density);
        line.setColor(ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * density);
        line.setStrokeCap(Paint.Cap.SQUARE);
        scan.setColor(Color.argb(150, 82, 255, 178));
        scan.setStrokeWidth(density);
        label.setColor(Color.BLACK);
        label.setTextSize(8f * density);
        label.setFakeBoldText(true);
        labelBackground.setColor(ACCENT);
        setWillNotDraw(false);
    }

    public void setFaceBounds(float left, float top, float right, float bottom) {
        face.set(left, top, right, bottom);
        visible = face.width() > 2f && face.height() > 2f;
        invalidate();
    }

    public void clearFace() {
        if (!visible) return;
        visible = false;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;
        float density = getResources().getDisplayMetrics().density;
        float pad = 5f * density;
        RectF box = new RectF(face.left - pad, face.top - pad, face.right + pad, face.bottom + pad);
        float corner = Math.max(10f * density, Math.min(box.width(), box.height()) * 0.22f);
        drawCorners(canvas, box, corner, glow);
        drawCorners(canvas, box, corner, line);

        float phase = (SystemClock.uptimeMillis() % 1_100L) / 1_100f;
        float scanY = box.top + box.height() * phase;
        canvas.drawLine(box.left + 3f * density, scanY, box.right - 3f * density, scanY, scan);
        float cross = 5f * density;
        canvas.drawLine(box.centerX() - cross, box.centerY(), box.centerX() + cross, box.centerY(), line);
        canvas.drawLine(box.centerX(), box.centerY() - cross, box.centerX(), box.centerY() + cross, line);

        String text = "FACE LOCK";
        float textWidth = label.measureText(text);
        float labelTop = Math.max(1f, box.top - 13f * density);
        RectF background = new RectF(box.left, labelTop, box.left + textWidth + 8f * density,
                labelTop + 12f * density);
        canvas.drawRoundRect(background, 2f * density, 2f * density, labelBackground);
        canvas.drawText(text, background.left + 4f * density, background.bottom - 3f * density, label);
        postInvalidateOnAnimation();
    }

    private static void drawCorners(Canvas canvas, RectF box, float length, Paint paint) {
        canvas.drawLine(box.left, box.top, box.left + length, box.top, paint);
        canvas.drawLine(box.left, box.top, box.left, box.top + length, paint);
        canvas.drawLine(box.right, box.top, box.right - length, box.top, paint);
        canvas.drawLine(box.right, box.top, box.right, box.top + length, paint);
        canvas.drawLine(box.left, box.bottom, box.left + length, box.bottom, paint);
        canvas.drawLine(box.left, box.bottom, box.left, box.bottom - length, paint);
        canvas.drawLine(box.right, box.bottom, box.right - length, box.bottom, paint);
        canvas.drawLine(box.right, box.bottom, box.right, box.bottom - length, paint);
    }
}
