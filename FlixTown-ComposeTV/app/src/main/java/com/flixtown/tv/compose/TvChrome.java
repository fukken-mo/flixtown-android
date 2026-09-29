package com.flixtown.tv.compose;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Small native drawables: gradients and strokes without blur or off-screen layers. */
public final class TvChrome {
    private TvChrome() {}

    public static final int BLACK = Color.rgb(7, 8, 11);
    public static final int PANEL = Color.rgb(18, 22, 32);
    public static final int RED = Color.rgb(231, 21, 30);

    public static GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(24, 17, 25), BLACK, Color.rgb(9, 12, 19) });
    }

    public static GradientDrawable rail() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(27, 20, 29), PANEL, BLACK });
        drawable.setCornerRadius(0);
        return drawable;
    }

    public static GradientDrawable heroScrim() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.TRANSPARENT, Color.argb(110, 7, 8, 11), BLACK });
    }

    public static GradientDrawable heroSideScrim() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { BLACK, Color.argb(195, 7, 8, 11), Color.TRANSPARENT });
    }

    public static GradientDrawable panel(float radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(31, 35, 47), PANEL, Color.rgb(11, 13, 20) });
        drawable.setCornerRadius(radius);
        return drawable;
    }

    public static GradientDrawable action(float radius, boolean focused, int strokeWidth) {
        GradientDrawable drawable = focused
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] { RED, RED })
                : new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] { Color.rgb(42, 43, 54), Color.rgb(24, 27, 38) });
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, focused ? RED : Color.rgb(67, 72, 87));
        return drawable;
    }
}
