package com.hush.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Shared, deliberately quiet screen for all five preview apps. */
final class DemoView extends View {

    private static final int CREAM = 0xFFF2EEE5;
    private static final int MUTED = 0xFF8B919A;
    private static final int DIM = 0xFF555C65;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final String label;
    private final int accent;
    private final int index;
    private final float density;

    private LinearGradient background;
    private RadialGradient glow;

    DemoView(Context context, String label, int accent, int index) {
        super(context);
        this.label = label;
        this.accent = accent;
        this.index = index;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF08090B);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        background = new LinearGradient(0, 0, 0, h,
                new int[]{0xFF0B0C10, 0xFF08090B, 0xFF060708},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glow = new RadialGradient(w * 0.78f, h * 0.22f, w * 0.68f,
                new int[]{withAlpha(accent, 0x3C), withAlpha(accent, 0x12), 0x00000000},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(background);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(glow);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // slow rings on the right — a quiet visual signature
        float cx = w * 0.80f;
        float cy = h * 0.54f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        for (int i = 0; i < 3; i++) {
            float radius = dp(110 + i * 48);
            paint.setColor(withAlpha(accent, 0x34 - i * 0x0D));
            canvas.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    -70f, 300f, false, paint);
        }
        paint.setColor(withAlpha(accent, 0xB0));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx + dp(110) * 0.34f, cy - dp(110) * 0.94f, dp(4.5f), paint);

        // brand
        text.setTypeface(medium);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0.18f);
        draw(canvas, "HUSH", dp(64), dp(63), sp(14), CREAM);
        float brandWidth = text.measureText("HUSH");
        text.setTypeface(regular);
        text.setLetterSpacing(0.12f);
        draw(canvas, "演示应用", dp(64) + brandWidth + dp(12), dp(63), sp(9), 0xFF7D838C);

        text.setTypeface(regular);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setLetterSpacing(0.2f);
        draw(canvas, String.format("DEMO  /  0%d", index), w - dp(64), dp(63), sp(8), 0xFF676D76);

        // accent rule
        paint.setColor(accent);
        canvas.drawRect(dp(66), dp(195), dp(66) + dp(36), dp(196.5f), paint);

        // title
        text.setTypeface(light);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(-0.02f);
        float titleSize = sp(94);
        text.setTextSize(titleSize);
        float width = text.measureText(label);
        if (width > w * 0.48f) {
            titleSize *= (w * 0.48f) / width;
        }
        draw(canvas, label, dp(64), dp(316), titleSize, CREAM);

        text.setTypeface(regular);
        text.setLetterSpacing(0.16f);
        draw(canvas, "留白桌面 · 应用预览", dp(66), dp(360), sp(10), 0xFF8A9099);

        text.setLetterSpacing(0.06f);
        draw(canvas, "这是一个演示应用。按 BACK 回到桌面，", dp(66), dp(404), sp(12), DIM);
        draw(canvas, "然后继续用方向键探索。", dp(66), dp(428), sp(12), DIM);

        // footer
        float baseline = h - dp(46);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF3B3F46);
        canvas.drawRoundRect(dp(64), baseline - dp(14), dp(95), baseline + dp(4), dp(5), dp(5), paint);
        paint.setStyle(Paint.Style.FILL);
        text.setTypeface(medium);
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.08f);
        draw(canvas, "BACK", dp(79.5f), baseline - dp(3.5f), sp(8), 0xFFC4BEB4);

        text.setTypeface(regular);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0.08f);
        draw(canvas, "返回桌面", dp(103), baseline - dp(1), sp(10), 0xFF7A8089);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setLetterSpacing(0.16f);
        draw(canvas, "HUSH  /  留白", w - dp(64), baseline - dp(1), sp(8), 0xFF4E545C);
    }

    private void draw(Canvas canvas, String value, float x, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        text.setStyle(Paint.Style.FILL);
        canvas.drawText(value, x, y, text);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
