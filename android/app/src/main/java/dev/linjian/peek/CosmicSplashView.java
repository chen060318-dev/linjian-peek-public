package dev.linjian.peek;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** Native black-and-silver celestial opening for LR Palm Window. */
public final class CosmicSplashView extends View {
    public interface Listener { void onOpeningComplete(); }

    private static final long DURATION_MS = 5200L;
    private static final String[] DATE_DIGITS = {"0", "7", "1", "4"};

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint finePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final Star[] stars = new Star[150];
    private final Orbit[] orbits = {
            new Orbit(.075f, 155f, 24, false), new Orbit(.112f, -92f, 36, true),
            new Orbit(.154f, 68f, 48, false), new Orbit(.207f, -43f, 64, true),
            new Orbit(.268f, 31f, 78, false), new Orbit(.342f, -20f, 92, true),
            new Orbit(.425f, 12f, 112, false)
    };

    private final float[][] pisces = {
            {-.92f, -.16f}, {-.66f, -.42f}, {-.38f, -.20f}, {-.10f, .02f},
            {.18f, .20f}, {.48f, .02f}, {.76f, -.28f}, {.94f, -.08f},
            {.72f, .20f}, {.45f, .38f}, {.16f, .20f}, {-.12f, .02f},
            {-.40f, .27f}, {-.70f, .43f}, {-.92f, .18f}, {-.92f, -.16f}
    };
    private final float[][] scorpio = {
            {-.94f, -.28f}, {-.72f, -.05f}, {-.50f, -.30f}, {-.26f, -.02f},
            {-.02f, .25f}, {.22f, .12f}, {.43f, .38f}, {.63f, .22f},
            {.82f, .42f}, {.95f, .18f}, {.80f, -.05f}, {.58f, -.16f}
    };

    private ValueAnimator animator;
    private float progress;
    private boolean completed;

    public CosmicSplashView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentDescription("LR掌心窗星轨开屏");
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        buildStars();
    }

    public void start() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { complete(); }
        });
        animator.start();
    }

    private void buildStars() {
        Random random = new Random(714318L);
        for (int i = 0; i < stars.length; i++) {
            Star star = new Star();
            star.x = random.nextFloat(); star.y = random.nextFloat();
            star.radius = .25f + random.nextFloat() * 1.15f;
            star.phase = random.nextFloat() * 6.28318f;
            star.strength = .22f + random.nextFloat() * .65f;
            stars[i] = star;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawSpace(canvas); drawStars(canvas);
        float cx = getWidth() * .5f, cy = getHeight() * .37f;
        float maxRadius = Math.min(getWidth() * .48f, getHeight() * .27f);
        drawFormingLines(canvas, cx, cy, maxRadius);
        drawOrbitSystem(canvas, cx, cy, maxRadius);
        drawConstellations(canvas); drawVerticalDate(canvas); drawQuote(canvas); drawExitVeil(canvas);
    }

    private void drawSpace(Canvas canvas) {
        float reveal = smooth(.00f, .16f, progress);
        canvas.drawColor(Color.BLACK);
        float radius = Math.max(getWidth(), getHeight()) * .72f;
        glowPaint.setShader(new RadialGradient(getWidth() * .5f, getHeight() * .38f, radius,
                new int[]{Color.rgb(20, 29, 38), Color.rgb(8, 12, 17), Color.BLACK},
                new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
        glowPaint.setAlpha(Math.round(255f * reveal));
        canvas.drawRect(0, 0, getWidth(), getHeight(), glowPaint); glowPaint.setShader(null);
    }

    private void drawStars(Canvas canvas) {
        float in = smooth(.04f, .30f, progress);
        if (in <= 0f) return;
        for (Star star : stars) {
            float pulse = .58f + .42f * (float) Math.sin(progress * 24f + star.phase);
            starPaint.setColor(Color.argb(Math.round(255f * in * star.strength * pulse), 207, 222, 232));
            canvas.drawCircle(star.x * getWidth(), star.y * getHeight(), dp(star.radius), starPaint);
        }
    }

    private void drawFormingLines(Canvas canvas, float cx, float cy, float maxRadius) {
        float grow = smooth(.05f, .30f, progress), fade = 1f - smooth(.28f, .50f, progress);
        if (grow <= 0f || fade <= 0f) return;
        linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(dp(.45f));
        linePaint.setColor(Color.argb(Math.round(145f * fade), 190, 207, 219));
        int spokes = 108; float inner = dp(10f);
        for (int i = 0; i < spokes; i++) {
            double angle = Math.PI * 2d * i / spokes;
            float stagger = smooth(i / (float) spokes * .11f, .20f + i / (float) spokes * .11f, progress);
            float length = maxRadius * grow * (.55f + .45f * stagger);
            canvas.drawLine(cx + (float) Math.cos(angle) * inner, cy + (float) Math.sin(angle) * inner,
                    cx + (float) Math.cos(angle) * length, cy + (float) Math.sin(angle) * length, linePaint);
        }
        float axis = getWidth() * smooth(.08f, .26f, progress);
        linePaint.setStrokeWidth(dp(.75f)); canvas.drawLine(cx - axis * .5f, cy, cx + axis * .5f, cy, linePaint);
    }

    private void drawOrbitSystem(Canvas canvas, float cx, float cy, float maxRadius) {
        float orbitIn = smooth(.16f, .44f, progress);
        if (orbitIn <= 0f) return;
        float systemAlpha = orbitIn * (1f - smooth(.92f, 1f, progress));
        for (int index = 0; index < orbits.length; index++) {
            Orbit orbit = orbits[index];
            float local = smooth(.14f + index * .025f, .34f + index * .025f, progress);
            float radius = maxRadius * orbit.radius / .425f;
            int save = canvas.save(); canvas.rotate(progress * orbit.speed, cx, cy);
            drawOrbit(canvas, cx, cy, radius, orbit, index, local * systemAlpha); canvas.restoreToCount(save);
        }
        float core = dp(4.5f + 4f * orbitIn);
        glowPaint.setShader(new RadialGradient(cx, cy, dp(38f),
                new int[]{Color.argb(Math.round(210f * systemAlpha), 231, 239, 244), Color.argb(65, 112, 139, 158), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, dp(38f), glowPaint); glowPaint.setShader(null);
        starPaint.setColor(Color.argb(Math.round(235f * systemAlpha), 236, 242, 246)); canvas.drawCircle(cx, cy, core, starPaint);
    }

    private void drawOrbit(Canvas canvas, float cx, float cy, float radius, Orbit orbit, int index, float alpha) {
        if (alpha <= 0f) return;
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(dp(index % 3 == 0 ? .8f : .48f));
        linePaint.setColor(Color.argb(Math.round((index % 2 == 0 ? 165f : 112f) * alpha), 192, 209, 220));
        linePaint.setPathEffect(orbit.dashed ? new DashPathEffect(new float[]{dp(4f), dp(5.5f)}, 0) : null);
        float sweep = 360f * smooth(.16f + index * .02f, .39f + index * .02f, progress);
        canvas.drawArc(oval, -90f + index * 21f, sweep, false, linePaint); linePaint.setPathEffect(null);

        finePaint.setStyle(Paint.Style.STROKE); finePaint.setStrokeWidth(dp(.55f));
        finePaint.setColor(Color.argb(Math.round(120f * alpha), 180, 201, 214));
        int shownTicks = Math.max(1, Math.round(orbit.ticks * sweep / 360f));
        for (int i = 0; i < shownTicks; i++) {
            double a = Math.PI * 2d * i / orbit.ticks; float tick = dp(i % 6 == 0 ? 5f : 2.1f);
            canvas.drawLine(cx + (float) Math.cos(a) * (radius - tick), cy + (float) Math.sin(a) * (radius - tick),
                    cx + (float) Math.cos(a) * (radius + tick), cy + (float) Math.sin(a) * (radius + tick), finePaint);
        }
        if (index == 2 || index == 4 || index == 6) {
            double a = progress * orbit.speed * Math.PI / 180d + index * 1.27d;
            starPaint.setColor(Color.argb(Math.round(230f * alpha), 225, 235, 241));
            canvas.drawCircle(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius, dp(index == 4 ? 2.8f : 1.8f), starPaint);
        }
    }

    private void drawConstellations(Canvas canvas) {
        float appear = smooth(.42f, .59f, progress), settle = smooth(.55f, .72f, progress);
        float fade = 1f - smooth(.80f, .92f, progress), alpha = appear * fade;
        if (alpha <= 0f) return;
        float y = getHeight() * .62f, scale = Math.min(getWidth(), getHeight()) * .105f;
        float leftX = lerp(getWidth() * .22f, getWidth() * .43f, settle);
        float rightX = lerp(getWidth() * .78f, getWidth() * .57f, settle);
        drawConstellation(canvas, pisces, leftX, y, scale, alpha, appear, "♓", "PISCES");
        drawConstellation(canvas, scorpio, rightX, y, scale, alpha, appear, "♏", "SCORPIO");
        float linkAlpha = smooth(.63f, .74f, progress) * fade;
        linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(dp(.55f));
        linePaint.setColor(Color.argb(Math.round(100f * linkAlpha), 178, 201, 216));
        canvas.drawLine(leftX + scale * .18f, y, rightX - scale * .18f, y, linePaint);
        starPaint.setColor(Color.argb(Math.round(225f * linkAlpha), 232, 239, 244));
        canvas.drawCircle((leftX + rightX) * .5f, y, dp(2.4f), starPaint);
    }

    private void drawConstellation(Canvas canvas, float[][] points, float cx, float cy, float scale,
                                   float alpha, float reveal, String symbol, String label) {
        linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(dp(.72f));
        linePaint.setColor(Color.argb(Math.round(145f * alpha), 184, 205, 219));
        float available = reveal * (points.length - 1);
        for (int i = 0; i < points.length - 1; i++) {
            float part = clamp(available - i, 0f, 1f); if (part <= 0f) break;
            float x1 = cx + points[i][0] * scale, y1 = cy + points[i][1] * scale;
            float x2 = cx + points[i + 1][0] * scale, y2 = cy + points[i + 1][1] * scale;
            canvas.drawLine(x1, y1, lerp(x1, x2, part), lerp(y1, y2, part), linePaint);
        }
        for (int i = 0; i < points.length; i++) {
            float dotIn = clamp(available - i + .4f, 0f, 1f); if (dotIn <= 0f) continue;
            float x = cx + points[i][0] * scale, y = cy + points[i][1] * scale;
            glowPaint.setShader(new RadialGradient(x, y, dp(8f), Color.argb(Math.round(135f * alpha * dotIn), 217, 231, 240), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, dp(8f), glowPaint); glowPaint.setShader(null);
            starPaint.setColor(Color.argb(Math.round(240f * alpha * dotIn), 226, 236, 242));
            canvas.drawCircle(x, y, dp(i % 4 == 0 ? 2.2f : 1.35f), starPaint);
        }
        textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        textPaint.setTextSize(sp(15f)); textPaint.setColor(Color.argb(Math.round(190f * alpha), 208, 222, 231));
        canvas.drawText(symbol, cx, cy + scale * .92f, textPaint);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); textPaint.setTextSize(sp(6.5f));
        textPaint.setLetterSpacing(.22f); textPaint.setColor(Color.argb(Math.round(120f * alpha), 150, 173, 189));
        canvas.drawText(label, cx, cy + scale * 1.18f, textPaint);
    }

    private void drawVerticalDate(Canvas canvas) {
        float in = smooth(.67f, .80f, progress), out = 1f - smooth(.91f, 1f, progress), alpha = in * out;
        if (alpha <= 0f) return;
        float x = getWidth() * .82f, firstY = getHeight() * .47f;
        textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        textPaint.setTextSize(sp(12f)); textPaint.setLetterSpacing(.08f);
        for (int i = 0; i < DATE_DIGITS.length; i++) {
            float digitIn = smooth(.67f + i * .025f, .75f + i * .025f, progress);
            textPaint.setColor(Color.argb(Math.round(205f * alpha * digitIn), 203, 220, 230));
            canvas.drawText(DATE_DIGITS[i], x, firstY + dp(19f) * i, textPaint);
        }
        finePaint.setStyle(Paint.Style.STROKE); finePaint.setStrokeWidth(dp(.55f));
        finePaint.setColor(Color.argb(Math.round(110f * alpha), 154, 180, 196));
        canvas.drawLine(x, firstY - dp(27f), x, firstY - dp(10f), finePaint);
        canvas.drawLine(x, firstY + dp(72f), x, firstY + dp(89f), finePaint);
    }

    private void drawQuote(Canvas canvas) {
        float in = smooth(.72f, .88f, progress), out = 1f - smooth(.95f, 1f, progress), alpha = in * out;
        if (alpha <= 0f) return;
        float y = getHeight() * .82f, sharpen = smooth(.78f, .92f, progress);
        textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        textPaint.setTextSize(sp(15.5f)); textPaint.setLetterSpacing(.12f);
        textPaint.setColor(Color.argb(Math.round(220f * alpha), 219, 230, 237));
        textPaint.setShadowLayer(dp(7f) * (1f - sharpen), 0, 0, Color.argb(Math.round(160f * alpha), 194, 220, 235));
        canvas.drawText("这浩瀚宇宙诞生的意义", getWidth() * .5f, y, textPaint);
        textPaint.setTextSize(sp(12.5f)); textPaint.setLetterSpacing(.18f);
        textPaint.setColor(Color.argb(Math.round(170f * alpha), 170, 193, 207));
        canvas.drawText("只为出现在我的眼底", getWidth() * .5f, y + dp(31f), textPaint); textPaint.clearShadowLayer();
    }

    private void drawExitVeil(Canvas canvas) {
        float exit = smooth(.94f, 1f, progress); if (exit <= 0f) return;
        glowPaint.setShader(new LinearGradient(0, 0, 0, getHeight(), Color.argb(Math.round(110f * exit), 10, 15, 20), Color.argb(Math.round(215f * exit), 4, 7, 10), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), glowPaint); glowPaint.setShader(null);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && progress > .28f) { if (animator != null) animator.end(); return true; }
        return true;
    }

    @Override protected void onDetachedFromWindow() { if (animator != null) animator.cancel(); super.onDetachedFromWindow(); }
    private void complete() { if (completed) return; completed = true; if (listener != null) listener.onOpeningComplete(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private static float lerp(float from, float to, float amount) { return from + (to - from) * amount; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float smooth(float from, float to, float value) {
        if (value <= from) return 0f; if (value >= to) return 1f;
        float x = (value - from) / (to - from); return x * x * (3f - 2f * x);
    }
    private static final class Star { float x, y, radius, phase, strength; }
    private static final class Orbit {
        final float radius, speed; final int ticks; final boolean dashed;
        Orbit(float radius, float speed, int ticks, boolean dashed) { this.radius = radius; this.speed = speed; this.ticks = ticks; this.dashed = dashed; }
    }
}
