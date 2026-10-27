package com.zoolcoder.tv.calendar;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view builders shared by the pages, so every screen has the same type sizes and spacing. */
final class Ui {
    private Ui() {
    }

    /** Plain text only: setText with a String never reads markup. */
    static TextView text(Context c, String value, int sp, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    static TextView bold(TextView view) {
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static TextView button(Context c, String label, View.OnClickListener onClick) {
        TextView button = Look.medium(text(c, label, 18, Look.TEXT));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Look.dp(c, 44));
        button.setPadding(Look.dp(c, 20), 0, Look.dp(c, 20), 0);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(onClick);
        return button;
    }

    static LinearLayout column(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        return column;
    }

    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        return row;
    }

    static LinearLayout.LayoutParams spaced(Context c, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Look.dp(c, top), 0, Look.dp(c, bottom));
        return params;
    }

    static LinearLayout.LayoutParams start(Context c, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(Look.dp(c, margin));
        return params;
    }

    static LinearLayout.LayoutParams fill(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    /** A key chip for the hint bar. */
    static TextView chip(Context c, String key) {
        TextView chip = bold(text(c, key, 12, Look.TEXT));
        chip.setPadding(Look.dp(c, 6), Look.dp(c, 2), Look.dp(c, 6), Look.dp(c, 2));
        Look.panel(c, chip, Look.PILL, 10);
        return chip;
    }
}
