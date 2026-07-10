package com.yilancioglu.barcodekeyboard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class ScanOverlayView extends View {
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private ValueAnimator animator;

    public ScanOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        cornerPaint.setColor(Color.rgb(242, 140, 40));
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(4));
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
        linePaint.setColor(Color.argb(215, 255, 85, 70));
        linePaint.setStrokeWidth(dp(2));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1700);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float marginX = getWidth() * 0.12f;
        float marginY = getHeight() * 0.17f;
        RectF box = new RectF(marginX, marginY, getWidth() - marginX, getHeight() - marginY);
        float c = Math.min(box.width(), box.height()) * 0.18f;

        canvas.drawLine(box.left, box.top, box.left + c, box.top, cornerPaint);
        canvas.drawLine(box.left, box.top, box.left, box.top + c, cornerPaint);
        canvas.drawLine(box.right - c, box.top, box.right, box.top, cornerPaint);
        canvas.drawLine(box.right, box.top, box.right, box.top + c, cornerPaint);
        canvas.drawLine(box.left, box.bottom, box.left + c, box.bottom, cornerPaint);
        canvas.drawLine(box.left, box.bottom - c, box.left, box.bottom, cornerPaint);
        canvas.drawLine(box.right - c, box.bottom, box.right, box.bottom, cornerPaint);
        canvas.drawLine(box.right, box.bottom - c, box.right, box.bottom, cornerPaint);

        float y = box.top + box.height() * progress;
        canvas.drawLine(box.left + dp(10), y, box.right - dp(10), y, linePaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
