package com.zoolcoder.tv.notes;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

/** The tvOS look shared with the other ZoolCoder add-ons: tokens, a glow backdrop, panels, and the focus lift. */
final class Look {
    static final int BACKGROUND = 0xFF0B1A12, SURFACE = 0x9916281D, SURFACE_FOCUSED = 0xFF2C4838,
            TEXT = 0xFFFFFFFF, TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF, PILL = 0x29FFFFFF,
            ON_LIGHT = 0xFF0B1A12, WARN = 0xFFF2C94C, WARN_FILL = 0x26F2C94C,
            ACCENT = 0xFF2BB673, DESTRUCTIVE = 0xFFE5484D, FOCUS_STROKE = 0xFFFFFFFF;

    private Look() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Lines follow the direction of their own first letter, so Arabic notes read right to left. */
    static TextView directed(TextView view) {
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        view.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        return view;
    }

    static TextView medium(TextView view) {
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    /** The window base with an accent glow fading out from the top-start corner. */
    static Drawable backdrop(Context c) {
        boolean rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(0x26 << 24) | (ACCENT & 0xFFFFFF), ACCENT & 0xFFFFFF});
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(rtl ? 1f : 0f, 0f);
        glow.setGradientRadius(c.getResources().getDisplayMetrics().widthPixels * 0.7f);
        return new LayerDrawable(new Drawable[]{new ColorDrawable(BACKGROUND), glow});
    }

    /** A flat panel for things the remote cannot reach. */
    static void panel(Context c, View view, int fill, float radius) {
        view.setBackground(shape(c, fill, radius));
    }

    /** A focusable surface: {@code fill} at rest; focused, the lighter fill with a white outline, scaled and lifted. */
    static void row(Context c, View view, int fill, float radius, float scale) {
        focusStates(c, view, fill, SURFACE_FOCUSED, radius, scale);
    }

    /** A button or tab: a solid fill stays as it is when focused, a translucent one lightens; both get the outline. */
    static void pill(Context c, TextView view, int fill, int text) {
        int focusedFill = (fill >>> 24) == 0xFF ? fill : SURFACE_FOCUSED;
        focusStates(c, view, fill, focusedFill, 22, 1.04f);
        view.setTextColor(text);
    }

    /** Both drawables are built here, once; the focus listener only swaps state, scale and elevation. */
    private static void focusStates(Context c, View view, int fill, int focusedFill, float radius, float scale) {
        GradientDrawable focused = shape(c, focusedFill, radius);
        focused.setStroke(dp(c, 2), FOCUS_STROKE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], shape(c, fill, radius));
        view.setBackground(states);
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        float lifted = dp(c, 8);
        view.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(150).start();
            v.setElevation(has ? lifted : 0f);
        });
    }

    static GradientDrawable shape(Context c, int fill, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(c, radius));
        return shape;
    }
}
