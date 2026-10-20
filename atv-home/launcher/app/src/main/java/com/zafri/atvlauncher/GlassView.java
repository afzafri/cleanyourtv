package com.zafri.atvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** The single translucent focus highlight. It springs from one target rect to the next. */
final class GlassView extends View {
    private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    private float curR, fromR, toR;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);

    GlassView(Context ctx, float dp) {
        super(ctx);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(0x73FFFFFF);
        anim.setDuration(380);
        anim.setInterpolator(new OvershootInterpolator(1.2f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            cur.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            curR = lerp(fromR, toR, t);
            invalidate();
        });
    }

    void moveTo(RectF target, float radius, boolean animate) {
        if (target.equals(to) && radius == toR && (anim.isRunning() || cur.equals(to))) return;
        to.set(target);
        toR = radius;
        anim.cancel();
        if (!animate || cur.isEmpty()) {
            cur.set(to);
            curR = toR;
            invalidate();
            return;
        }
        from.set(cur);
        fromR = curR;
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cur.isEmpty()) return;
        fill.setShader(new LinearGradient(0, cur.top, 0, cur.bottom, 0x33FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(cur, curR, curR, fill);
        canvas.drawRoundRect(cur, curR, curR, stroke);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
