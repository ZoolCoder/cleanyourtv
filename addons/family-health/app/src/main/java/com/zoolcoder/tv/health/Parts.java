package com.zoolcoder.tv.health;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The pieces both screens and the banner are made of: initial discs, icons, status badges and cards. */
final class Parts {
    private Parts() {
    }

    /** A member's coloured disc with their initial: no photos anywhere. */
    static TextView disc(Context c, Member m, int size) {
        TextView disc = Ui.bold(Ui.text(c, m.initial(), Math.round(size * 0.45f), Look.TEXT));
        disc.setGravity(Gravity.CENTER);
        disc.setIncludeFontPadding(false);
        GradientDrawable round = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Look.lighten(Look.WHO[m.color], 0.18f), Look.WHO[m.color]});
        round.setShape(GradientDrawable.OVAL);
        round.setStroke(Look.dp(c, 1.5f), 0x66FFFFFF);
        disc.setBackground(round);
        disc.setLayoutParams(new LinearLayout.LayoutParams(Look.dp(c, size), Look.dp(c, size)));
        return disc;
    }

    static ImageView icon(Context c, int res, int color, int size) {
        ImageView icon = new ImageView(c);
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setLayoutParams(new LinearLayout.LayoutParams(Look.dp(c, size), Look.dp(c, size)));
        return icon;
    }

    /** A round badge: the icon in white on a solid colour (taken, missed), or tinted on clear glass. */
    static ImageView badge(Context c, int res, int color, boolean solid, int size) {
        ImageView badge = icon(c, res, solid ? Look.TEXT : color, size);
        int pad = Math.round(size * (solid ? 0.2f : 0.22f));
        badge.setPadding(Look.dp(c, pad), Look.dp(c, pad), Look.dp(c, pad), Look.dp(c, pad));
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        if (solid) round.setColor(color);
        else {
            round.setColor(0x1AFFFFFF);
            round.setStroke(Look.dp(c, 1), 0x40FFFFFF);
        }
        badge.setBackground(round);
        return badge;
    }

    /** An empty ring: a day still to come in the week row. */
    static View ring(Context c, int alpha, int size) {
        View ring = new View(c);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setStroke(Look.dp(c, 2), (alpha << 24) | 0xFFFFFF);
        ring.setBackground(round);
        ring.setLayoutParams(new LinearLayout.LayoutParams(Look.dp(c, size), Look.dp(c, size)));
        return ring;
    }

    /** A section heading: bilingual label, then its icon. */
    static LinearLayout heading(Context c, String label, int res, int color) {
        LinearLayout row = Ui.row(c);
        TextView text = Look.medium(Ui.text(c, label, 18, Look.TEXT));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text);
        ImageView icon = icon(c, res, color, 20);
        ((LinearLayout.LayoutParams) icon.getLayoutParams()).setMarginStart(Look.dp(c, 8));
        row.addView(icon);
        return row;
    }

    /** A single-line title and a single-line line under it, both following their own script's direction. */
    static LinearLayout lines(Context c, String title, String sub, int titleSp) {
        LinearLayout column = Ui.column(c);
        TextView top = Look.directed(Look.medium(Ui.text(c, title, titleSp, Look.TEXT)));
        top.setSingleLine(true);
        top.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(top);
        if (!sub.isEmpty()) {
            TextView bottom = Look.directed(Ui.text(c, sub, titleSp - 4, Look.TEXT_2));
            bottom.setSingleLine(true);
            bottom.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(bottom, Ui.spaced(c, 2, 0));
        }
        return column;
    }

    /** A focusable glass row: disc, lines, and an end badge. */
    static LinearLayout card(Context c, Member m, String title, String sub, View end) {
        LinearLayout card = Ui.row(c);
        card.setPadding(Look.dp(c, 12), Look.dp(c, 10), Look.dp(c, 12), Look.dp(c, 10));
        card.setMinimumHeight(Look.dp(c, 64));
        card.setFocusable(true);
        card.setClickable(true);
        Look.row(c, card, Look.SURFACE, 18, 1.04f);
        if (m != null) card.addView(disc(c, m, 36));
        LinearLayout text = lines(c, title, sub, 16);
        LinearLayout.LayoutParams fill = Ui.fill(1f);
        fill.setMarginStart(Look.dp(c, m == null ? 0 : 12));
        fill.setMarginEnd(Look.dp(c, 8));
        card.addView(text, fill);
        if (end != null) card.addView(end);
        return card;
    }

    /**
     * A people tab: disc and name, white when selected like the header tabs. The name follows the tab's state
     * so its colour flips with the fill.
     */
    static LinearLayout tab(Context c, Member m, String label) {
        LinearLayout tab = Ui.row(c);
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setMinimumHeight(Look.dp(c, 48));
        tab.setPadding(Look.dp(c, m == null ? 22 : 8), 0, Look.dp(c, 20), 0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, Look.lit(c, Look.TEXT, 24));
        states.addState(new int[]{android.R.attr.state_focused}, Look.lit(c, Look.SURFACE_FOCUSED, 24));
        states.addState(new int[]{android.R.attr.state_selected}, Look.shape(c, Look.TEXT, 24));
        states.addState(new int[0], Look.shape(c, Look.PILL, 24));
        tab.setBackground(states);
        tab.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        tab.setOnFocusChangeListener((v, has) -> Look.lift(v, has, 1.05f));
        if (m != null) tab.addView(disc(c, m, 32));
        TextView name = Look.medium(Ui.text(c, label, 16, Look.TEXT));
        name.setSingleLine(true);
        name.setDuplicateParentStateEnabled(true);
        name.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{Look.ON_LIGHT, Look.TEXT}));
        LinearLayout.LayoutParams params = Ui.start(c, m == null ? 0 : 10);
        tab.addView(name, params);
        return tab;
    }

    /**
     * A panel's scrolling list, clipped to the scroll so rows that scroll out never cover the heading. The scroll
     * reaches 8dp into the panel's side padding and the list pads it back, so a focused row has room to grow.
     */
    static LinearLayout scrollList(Context c, LinearLayout panel) {
        panel.setClipChildren(true); // the parent's flag is what clips the scroll to its own bounds
        panel.setClipToPadding(false);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = Ui.column(c);
        list.setClipToPadding(false);
        int side = Look.dp(c, 14), pad = Look.dp(c, 6);
        list.setPadding(side, pad, side, pad);
        scroll.addView(list);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        fill.topMargin = Look.dp(c, 6);
        fill.setMarginStart(-Look.dp(c, 8));
        fill.setMarginEnd(-Look.dp(c, 8));
        panel.addView(scroll, fill);
        return list;
    }

    static TextView quiet(Context c, String text) {
        TextView view = Ui.text(c, text, 15, Look.TEXT_3);
        view.setPadding(Look.dp(c, 6), Look.dp(c, 8), Look.dp(c, 6), Look.dp(c, 8));
        return view;
    }
}
