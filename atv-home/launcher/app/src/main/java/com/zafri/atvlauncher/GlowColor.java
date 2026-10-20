package com.zafri.atvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** Samples a vivid colour from a banner: saturated, bright pixels count most. */
final class GlowColor {
    private GlowColor() {}

    static int sample(Drawable d) {
        if (d == null) return 0xFF808080;
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, 32, 18);
        copy.draw(new Canvas(b));

        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, w = 0, ar = 0, ag = 0, ab = 0;
        int n = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double wt = hsv[1] * hsv[2];
                wt = wt * wt;
                r += Color.red(p) * wt;
                g += Color.green(p) * wt;
                bl += Color.blue(p) * wt;
                w += wt;
                ar += Color.red(p);
                ag += Color.green(p);
                ab += Color.blue(p);
                n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF808080;

        int c = w > 0.02 * n
                ? Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w))
                : Color.rgb((int) (ar / n), (int) (ag / n), (int) (ab / n));
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.1f) {
            hsv[2] = 0.75f; // grey/white banner: soft neutral glow
        } else {
            hsv[1] = Math.min(1f, hsv[1] * 1.15f);
            hsv[2] = Math.max(hsv[2], 0.9f);
        }
        return Color.HSVToColor(hsv);
    }
}
