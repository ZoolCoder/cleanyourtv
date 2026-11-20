package com.zoolcoder.tv.health;

import android.content.Context;
import android.content.res.ColorStateList;
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
    static final int BACKGROUND = 0xFF1E0A2C, SURFACE = 0x17FFFFFF, SURFACE_FOCUSED = 0xFF3F2E52,
            TEXT = 0xFFFFFFFF, TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF, PILL = 0x29FFFFFF,
            ON_LIGHT = 0xFF1E0A2C, RIM = 0x2EFFFFFF, FOCUS_RIM = 0xFFFFFFFF,
            GLOW = 0xFF9A3A8E, DEPTH = 0xFF14B8A6,
            ACCENT = 0xFF14B8A6, GREEN = 0xFF0D9B65,
            TAKEN = 0xFF22C55E, DUE = 0xFFF59E0B, MISSED = 0xFFEF4444, LOW = 0xFFF87171, UPCOMING = 0xFF3B82F6;

    /** The member colours, indexed by Member.color: blue, pink, orange, green, violet, red. */
    static final int[] WHO = {0xFF3B82F6, 0xFFEC4899, 0xFFF59E0B, 0xFF22C55E, 0xFFA66BFF, 0xFFEF4444};

    private Look() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Lines follow the direction of their own first letter, so Arabic titles read right to left. */
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
     * The window base with two pools of light for the glass to sit over: plum from the top-start corner and a
     * little of the teal accent from the bottom-end one. No blur: the TVs this runs on cannot afford it.
     */
    static Drawable backdrop(Context c) {
        boolean rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float width = c.getResources().getDisplayMetrics().widthPixels;
        return new LayerDrawable(new Drawable[]{new ColorDrawable(BACKGROUND),
                light(GLOW, 0x66, rtl ? 1f : 0f, 0f, width * 0.75f),
                light(DEPTH, 0x26, rtl ? 0f : 1f, 1f, width * 0.6f)});
    }

    private static Drawable light(int colour, int alpha, float x, float y, float radius) {
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(alpha << 24) | (colour & 0xFFFFFF), colour & 0xFFFFFF});
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(x, y);
        glow.setGradientRadius(radius);
        return glow;
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

    /** A header tab: the selected one is filled white with dark text; focus lights it and adds the rim and the lift. */
    static void tab(Context c, TextView view) {
        GradientDrawable focused = lit(c, SURFACE_FOCUSED, 22);
        GradientDrawable focusedSelected = lit(c, TEXT, 22);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, focusedSelected);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, shape(c, TEXT, 22));
        states.addState(new int[0], shape(c, PILL, 22));
        view.setBackground(states);
        view.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}}, new int[]{ON_LIGHT, TEXT}));
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }

    /** Both drawables are built here, once; the focus listener only swaps state, scale and elevation. */
    private static void focusStates(Context c, View view, int fill, int focusedFill, float radius, float scale) {
        GradientDrawable focused = lit(c, focusedFill, radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], shape(c, fill, radius));
        view.setBackground(states);
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        view.setOnFocusChangeListener((v, has) -> lift(v, has, scale));
    }

    /** The focus motion: scale up in 150 ms and lift 8 dp; a view with its own focus listener calls this too. */
    static void lift(View view, boolean has, float scale) {
        view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(150).start();
        view.setElevation(has ? dp(view.getContext(), 8) : 0f);
        // A row just drawn has no size yet: its scroll brings it into view once laid out.
        if (has && view.isLaidOut()) {
            // Inside a scroll, bring the grown row and its rim fully into view, not just its resting bounds.
            int m = dp(view.getContext(), 8);
            view.requestRectangleOnScreen(new android.graphics.Rect(-m, -m, view.getWidth() + m, view.getHeight() + m));
        }
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

    /** A small icon tile of coloured glass: the colour at 45% over 25%, lit from the top, with a light rim. */
    static GradientDrawable tile(Context c, int colour, float radius) {
        int rgb = colour & 0xFFFFFF;
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x73000000 | rgb, 0x40000000 | rgb});
        shape.setCornerRadius(dp(c, radius));
        shape.setStroke(dp(c, 1), 0x40FFFFFF);
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
