package com.zafri.atvlauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A banner card, optionally with the app name underneath that only shows while the tile is focused. */
final class Tile {
    static final float FOCUS_SCALE = 1.1f;

    final LinearLayout root;
    final FrameLayout card;
    final ImageView image;
    final TextView label;
    private final int height;
    AppInfo app;

    Tile(Context ctx, int width, int height, float dp, Typeface font, boolean showLabel) {
        this.height = height;
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setTag(this);

        card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(12 * dp);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        image = new ImageView(ctx);
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(card, new LinearLayout.LayoutParams(width, height));

        label = new TextView(ctx);
        label.setTypeface(font);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(0xF2FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * dp);
        if (showLabel) root.addView(label, lp);
    }

    void bind(AppInfo a) {
        app = a;
        if (a.banner != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setPadding(0, 0, 0, 0);
            image.setImageDrawable(a.banner);
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = (int) (height * 0.22f);
            image.setPadding(p, p, p, p);
            image.setImageDrawable(a.icon);
        }
        label.setText(a.label);
        setFocused(false, false);
    }

    void setFocused(boolean focused, boolean animate) {
        float s = focused ? FOCUS_SCALE : 1f;
        float alpha = focused ? 1f : 0f;
        card.animate().cancel();
        label.animate().cancel();
        if (animate) {
            card.animate().scaleX(s).scaleY(s).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            label.animate().alpha(alpha).setDuration(180).start();
        } else {
            card.setScaleX(s);
            card.setScaleY(s);
            label.setAlpha(alpha);
        }
    }
}
