package com.zoolcoder.tv.notes;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/** The palette shared with the other ZoolCoder add-ons, and helpers for text, panels and the focus ring. */
final class Look {
    static final int BACKGROUND = 0xFF0B1A12, CARD = 0xFF16281D, RAISED = 0xFF20382A, DIVIDER = 0xFF294235,
            FOCUS = 0xFF2BB673, MUTED = 0xFF9DB8A8, WARN = 0xFFF2C94C, WHITE = 0xFFFFFFFF;
    private static final float FOCUS_SCALE = 1.03f;

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

    /** A flat panel with a 1 dp divider edge, for things the remote cannot reach. */
    static void panel(Context c, View view, int fill, float radius) {
        view.setBackground(shape(c, fill, radius, 1, DIVIDER));
    }

    /**
     * The focus look: a raised fill, a 2 dp accent stroke inside a 1 dp white one, and a static 1.03 scale.
     * Both drawables are built once per view, so a focus change only switches between them.
     */
    static void ring(Context c, View view, int fill, float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused(c, radius));
        states.addState(new int[0], shape(c, fill, radius, 1, DIVIDER));
        view.setBackground(states);
        view.setOnFocusChangeListener((v, has) -> {
            v.setScaleX(has ? FOCUS_SCALE : 1f);
            v.setScaleY(has ? FOCUS_SCALE : 1f);
        });
    }

    private static Drawable focused(Context c, float radius) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                shape(c, RAISED, radius, 1, WHITE), shape(c, 0, radius - 1, 2, FOCUS)});
        layers.setLayerInset(1, dp(c, 1), dp(c, 1), dp(c, 1), dp(c, 1));
        return layers;
    }

    private static GradientDrawable shape(Context c, int fill, float radius, int stroke, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(c, radius));
        shape.setStroke(dp(c, stroke), strokeColor);
        return shape;
    }
}
