package com.t8numen.hongguocache1080;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

final class VipExclusiveBadgeDrawable extends Drawable {
    private static final String LABEL = "VIP专享";
    private static final int FALLBACK_HEIGHT_PX = 48;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int intrinsicWidth;
    private final int intrinsicHeight;

    VipExclusiveBadgeDrawable(Drawable original) {
        int originalWidth = Math.max(
                original.getBounds().width(),
                Math.max(original.getMinimumWidth(), original.getIntrinsicWidth()));
        int originalHeight = Math.max(
                original.getBounds().height(),
                Math.max(original.getMinimumHeight(), original.getIntrinsicHeight()));
        intrinsicHeight = originalHeight > 0 ? originalHeight : FALLBACK_HEIGHT_PX;

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(intrinsicHeight * 0.52f);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int labelWidth = (int) Math.ceil(textPaint.measureText(LABEL) + intrinsicHeight * 0.8f);
        intrinsicWidth = Math.max(originalWidth, labelWidth);
        setBounds(0, 0, intrinsicWidth, intrinsicHeight);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        backgroundPaint.setShader(new LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                Color.rgb(255, 102, 54),
                Color.rgb(255, 181, 92),
                Shader.TileMode.CLAMP));
        float radius = bounds.height() / 2.0f;
        canvas.drawRoundRect(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                radius,
                radius,
                backgroundPaint);

        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float baseline = bounds.exactCenterY()
                - (fontMetrics.ascent + fontMetrics.descent) / 2.0f;
        canvas.drawText(LABEL, bounds.exactCenterX(), baseline, textPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        backgroundPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        backgroundPaint.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicHeight;
    }

    @Override
    public int getMinimumWidth() {
        return intrinsicWidth;
    }

    @Override
    public int getMinimumHeight() {
        return intrinsicHeight;
    }
}
