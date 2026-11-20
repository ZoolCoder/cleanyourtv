package com.zoolcoder.tv.notes;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

/** The tvOS Liquid Glass look shared with the other ZoolCoder add-ons: tokens, a glow backdrop, panels, and the focus lift. */
final class Look {
    static final int BACKGROUND = 0xFF0A1A16, SURFACE = 0x17FFFFFF, SURFACE_FOCUSED = 0xFF2C4A40,
            TEXT = 0xFFFFFFFF, TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF, PILL = 0x29FFFFFF,
            ON_LIGHT = 0xFF0A1A16, WARN = 0xFFF2C94C, WARN_FILL = 0x26F2C94C,
            RIM = 0x2EFFFFFF, FOCUS_RIM = 0xFFFFFFFF, DEPTH = 0xFF5E5CE6,
            ACCENT = 0xFF3CC07A, DESTRUCTIVE = 0xFFE5484D;

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

    /**
     * The window base with two pools of light for the glass to sit over: the accent from the top-start corner
     * and a deep indigo from the bottom-end one. No blur: the TVs this runs on cannot afford it.
     */
    static Drawable backdrop(Context c) {
        boolean rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float width = c.getResources().getDisplayMetrics().widthPixels;
        return new LayerDrawable(new Drawable[]{new ColorDrawable(BACKGROUND),
                light(ACCENT, 0x59, rtl ? 1f : 0f, 0f, width * 0.75f),
                light(DEPTH, 0x33, rtl ? 0f : 1f, 1f, width * 0.6f)});
    }

    private static Drawable light(int colour, int alpha, float x, float y, float radius) {
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(alpha << 24) | (colour & 0xFFFFFF), colour & 0xFFFFFF});
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(x, y);
        glow.setGradientRadius(radius);
        return glow;
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
        GradientDrawable focused = lit(c, focusedFill, radius);
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

    /** The app's own icon for the header, clipped to a glass tile's corners. */
    static ImageView appIcon(Context c) {
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int radius = dp(c, 10);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        icon.setClipToOutline(true);
        return icon;
    }

    /**
     * A translucent fill becomes glass: brighter at the top as if lit from above, with a hairline rim catching
     * the light. An opaque fill (a badge, a white button), a nearly clear one and a thin bar stay flat.
     */
    static GradientDrawable shape(Context c, int fill, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(c, radius));
        int alpha = fill >>> 24;
        if (alpha == 0xFF || alpha < 0x10 || radius < 8) {
            shape.setColor(fill);
            return shape;
        }
        shape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        shape.setColors(new int[]{(Math.min(0xFF, alpha * 2) << 24) | (fill & 0xFFFFFF), fill});
        shape.setStroke(dp(c, 1), RIM);
        return shape;
    }

    /** The focused fill: lit at the top, with a bright rim; an opaque one also casts the lift's shadow. */
    static GradientDrawable lit(Context c, int fill, float radius) {
        int top = fill >>> 24 == 0xFF ? lighten(fill, 0.14f) : (Math.min(0xFF, (fill >>> 24) * 2) << 24) | (fill & 0xFFFFFF);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, fill});
        shape.setCornerRadius(dp(c, radius));
        shape.setStroke(dp(c, 2), FOCUS_RIM);
        return shape;
    }

    /** {@code colour} with white laid over it by {@code amount} (0 to 1), opaque. */
    static int lighten(int colour, float amount) {
        int r = (colour >> 16) & 0xFF, g = (colour >> 8) & 0xFF, b = colour & 0xFF;
        r += Math.round((0xFF - r) * amount);
        g += Math.round((0xFF - g) * amount);
        b += Math.round((0xFF - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
