package dev.linjian.peek;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * First-pass rainy-glass opening for LR Palm Window.
 *
 * The soft image is the base layer. The sharper image is only revealed inside
 * animated droplets, with a tiny displacement to suggest refraction. Everything
 * is native Canvas so startup does not depend on a WebView or a network asset.
 */
public final class RainSplashView extends View {
    public interface Listener { void onOpeningComplete(); }

    private static final long DURATION_MS = 4300L;
    private static final String CN_LINE = "誓言是一场有时差的雨";
    private static final String EN_LINE = "𝒜 𝓋𝑜𝓌 𝒾𝓈 𝓇𝒶𝒾𝓃 𝓉𝒽𝒶𝓉 𝒻𝒶𝓁𝓁𝓈 𝑜𝓊𝓉 𝑜𝒻 𝓉𝒾𝓂𝑒.";

    private final Bitmap softImage;
    private final Bitmap clearImage;
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Matrix coverMatrix = new Matrix();
    private final List<Drop> drops = new ArrayList<>();
    private final Random noiseRandom = new Random(318L);
    private final Listener listener;
    private ValueAnimator animator;
    private float progress;
    private boolean completed;

    public RainSplashView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        softImage = BitmapFactory.decodeResource(getResources(), R.drawable.rain_window_soft);
        clearImage = BitmapFactory.decodeResource(getResources(), R.drawable.rain_window_clear);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentDescription("LR掌心窗雨幕开屏");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        buildDrops();
    }

    public void start() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { complete(); }
        });
        animator.start();
    }

    private void buildDrops() {
        Random random = new Random(20260318L);
        for (int i = 0; i < 18; i++) {
            Drop drop = new Drop();
            drop.x = .07f + random.nextFloat() * .86f;
            drop.y = .07f + random.nextFloat() * .68f;
            drop.radius = 3.8f + random.nextFloat() * (i < 6 ? 12f : 6f);
            drop.start = .14f + random.nextFloat() * .30f;
            drop.speed = i < 8 ? .035f + random.nextFloat() * .095f : 0f;
            drop.wobble = random.nextFloat() * 6.28318f;
            drop.seed = random.nextInt();
            drops.add(drop);
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (clearImage == null) return;
        float scale = Math.max(width / (float) clearImage.getWidth(), height / (float) clearImage.getHeight());
        float dx = (width - clearImage.getWidth() * scale) * .5f;
        float dy = (height - clearImage.getHeight() * scale) * .5f;
        coverMatrix.reset();
        coverMatrix.postScale(scale, scale);
        coverMatrix.postTranslate(dx, dy);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(239, 244, 247));
        if (softImage == null || clearImage == null) return;

        float imageIn = smooth(0.02f, .26f, progress);
        imagePaint.setAlpha(Math.round(255f * imageIn));
        canvas.drawBitmap(softImage, coverMatrix, imagePaint);

        // A cool fog/noise veil clears before the rain settles on the glass.
        float fog = 1f - smooth(.03f, .40f, progress);
        fogPaint.setColor(Color.argb(Math.round(225f * fog), 236, 243, 247));
        canvas.drawRect(0, 0, getWidth(), getHeight(), fogPaint);
        drawMistNoise(canvas, fog);

        if (progress > .12f) {
            for (Drop drop : drops) drawDrop(canvas, drop);
        }
        drawIntroText(canvas);

        // The last frames soften into the already-laid-out home screen.
        float exit = smooth(.91f, 1f, progress);
        if (exit > 0f) {
            fogPaint.setColor(Color.argb(Math.round(42f * exit), 240, 245, 247));
            canvas.drawRect(0, 0, getWidth(), getHeight(), fogPaint);
        }
    }

    private void drawMistNoise(Canvas canvas, float fog) {
        if (fog <= .02f) return;
        noiseRandom.setSeed(318L);
        fogPaint.setStrokeWidth(dp(.7f));
        fogPaint.setColor(Color.argb(Math.round(34f * fog), 102, 130, 147));
        for (int i = 0; i < 95; i++) {
            float x = noiseRandom.nextFloat() * getWidth();
            float y = noiseRandom.nextFloat() * getHeight();
            float length = dp(1f + noiseRandom.nextFloat() * 4f);
            canvas.drawLine(x, y, x + length, y, fogPaint);
        }
    }

    private void drawDrop(Canvas canvas, Drop drop) {
        float born = smooth(drop.start, drop.start + .11f, progress);
        if (born <= 0f) return;
        float age = Math.max(0f, progress - drop.start);
        float radius = dp(drop.radius) * (.68f + .32f * born);
        float x = drop.x * getWidth() + (float) Math.sin(age * 18f + drop.wobble) * dp(1.5f);
        float y = drop.y * getHeight() + drop.speed * getHeight() * age * 2.2f;
        if (y > getHeight() + radius) return;

        if (drop.speed > 0f && age > .045f) drawBrokenTrail(canvas, x, y, radius, age, born);

        Path shape = irregularDrop(x, y, radius, drop.seed, drop.speed > 0f ? 1.16f : 1f);
        int save = canvas.save();
        canvas.clipPath(shape);
        canvas.translate((float) Math.sin(drop.wobble) * dp(2.2f), -dp(2.6f));
        imagePaint.setAlpha(Math.round(242f * born));
        canvas.drawBitmap(clearImage, coverMatrix, imagePaint);
        canvas.restoreToCount(save);

        // Upper refracted highlight and a restrained lower shadow give volume.
        RectF bounds = new RectF(x - radius, y - radius * 1.08f, x + radius, y + radius * 1.18f);
        dropPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{Color.argb(Math.round(190f * born), 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(Math.round(92f * born), 29, 52, 68)},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, dropPaint);
        dropPaint.setShader(null);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(.55f));
        edgePaint.setColor(Color.argb(Math.round(115f * born), 238, 249, 255));
        canvas.drawPath(shape, edgePaint);
        edgePaint.setStyle(Paint.Style.FILL);
    }

    private void drawBrokenTrail(Canvas canvas, float x, float y, float radius, float age, float alpha) {
        float length = Math.min(dp(105f), age * getHeight() * .13f + radius * 1.7f);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeWidth(Math.max(dp(1f), radius * .18f));
        trailPaint.setColor(Color.argb(Math.round(70f * alpha), 222, 238, 246));
        float top = y - radius - length;
        float gap = dp(6f);
        canvas.drawLine(x, top, x, top + length * .42f, trailPaint);
        canvas.drawLine(x, top + length * .42f + gap, x, y - radius, trailPaint);
        trailPaint.setStyle(Paint.Style.FILL);
    }

    private Path irregularDrop(float cx, float cy, float radius, int seed, float stretch) {
        Random random = new Random(seed);
        Path path = new Path();
        int points = 11;
        for (int i = 0; i < points; i++) {
            double angle = -Math.PI / 2d + Math.PI * 2d * i / points;
            float uneven = .90f + random.nextFloat() * .18f;
            float px = cx + (float) Math.cos(angle) * radius * uneven;
            float py = cy + (float) Math.sin(angle) * radius * uneven * stretch;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        return path;
    }

    private void drawIntroText(Canvas canvas) {
        float alpha = smooth(.48f, .74f, progress) * (1f - smooth(.92f, 1f, progress));
        if (alpha <= 0f) return;
        float sharpen = smooth(.55f, .80f, progress);
        float centerX = getWidth() * .5f;
        float baseline = getHeight() * .72f;

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        textPaint.setTextSize(dp(18f));
        textPaint.setLetterSpacing(.10f);
        textPaint.setColor(Color.argb(Math.round(220f * alpha), 47, 67, 80));
        textPaint.setShadowLayer(dp(5f) * (1f - sharpen), 0, 0, Color.argb(Math.round(150f * alpha), 244, 249, 252));
        drawBreathingText(canvas, CN_LINE, centerX, baseline, alpha, sharpen);

        textPaint.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        textPaint.setTextSize(dp(11.5f));
        textPaint.setLetterSpacing(.035f);
        textPaint.setColor(Color.argb(Math.round(185f * alpha), 67, 88, 101));
        textPaint.setShadowLayer(dp(4f) * (1f - sharpen), 0, 0, Color.argb(Math.round(140f * alpha), 247, 250, 252));
        drawBreathingText(canvas, EN_LINE, centerX, baseline + dp(28f), alpha, sharpen);
        textPaint.clearShadowLayer();
    }

    private void drawBreathingText(Canvas canvas, String text, float x, float y, float alpha, float sharpen) {
        if (sharpen < .9f) {
            int ghostAlpha = Math.round(38f * alpha * (1f - sharpen));
            int original = textPaint.getColor();
            textPaint.setColor((original & 0x00ffffff) | (ghostAlpha << 24));
            canvas.drawText(text, x - dp(1.4f), y, textPaint);
            canvas.drawText(text, x + dp(1.2f), y + dp(.7f), textPaint);
            textPaint.setColor(original);
        }
        float tremble = (1f - sharpen) * (float) Math.sin(progress * 80f) * dp(.55f);
        canvas.drawText(text, x + tremble, y, textPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && progress > .30f) {
            if (animator != null) animator.end();
            return true;
        }
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    private void complete() {
        if (completed) return;
        completed = true;
        if (listener != null) listener.onOpeningComplete();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private static float smooth(float from, float to, float value) {
        if (value <= from) return 0f;
        if (value >= to) return 1f;
        float x = (value - from) / (to - from);
        return x * x * (3f - 2f * x);
    }

    private static final class Drop {
        float x;
        float y;
        float radius;
        float start;
        float speed;
        float wobble;
        int seed;
    }
}
