package com.zoolcoder.tv.notes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pop-up: a card in the top-end corner over whatever is playing. It never takes focus or touches, so the
 * remote keeps working underneath. One window and one set of views are reused: notes that arrive while one
 * shows wait in a queue of 5 (the oldest is dropped past that) and show in turn. Main thread only.
 */
final class OverlayCard {
    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::advance;
    private final NoteQueue queue = new NoteQueue();
    private LinearLayout card;
    private TextView from, time, body;
    private boolean attached;
    private int seconds;

    OverlayCard(Context context) {
        this.context = context;
        this.windows = context.getSystemService(WindowManager.class);
    }

    static boolean allowed(Context context) {
        return Settings.canDrawOverlays(context);
    }

    void show(Note note, int seconds) {
        this.seconds = seconds;
        queue.add(note);
        if (!attached) advance();
    }

    /** Shows the next waiting note in the same window, or takes the window down when none waits. */
    private void advance() {
        main.removeCallbacks(advance);
        Note note = queue.next();
        if (note == null) {
            detach();
            return;
        }
        // The permission can be revoked while the service runs: then nothing shows and nothing waits.
        if (!allowed(context)) {
            hide();
            return;
        }
        if (card == null) build();
        from.setText(note.name);
        time.setText(DateUtils.formatDateTime(context, note.time, DateUtils.FORMAT_SHOW_TIME));
        body.setText(note.text);
        try {
            if (attached) windows.updateViewLayout(card, params());
            else windows.addView(card, params());
            attached = true;
        } catch (RuntimeException e) {
            // BadTokenException and friends: the window cannot be had, so drop what waits too.
            hide();
            return;
        }
        main.postDelayed(advance, seconds * 1000L);
    }

    /** Opaque card, 1 dp edge, 4 dp accent stripe on the leading side, clipped to the rounded corners. */
    private void build() {
        card = new LinearLayout(context);
        card.setPadding(Look.dp(context, 1), Look.dp(context, 1), Look.dp(context, 1), Look.dp(context, 1));
        Look.panel(context, card, Look.BACKGROUND, 14);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);

        View stripe = new View(context);
        stripe.setBackgroundColor(Look.FOCUS);
        card.addView(stripe, new LinearLayout.LayoutParams(Look.dp(context, 4), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Look.dp(context, 16), Look.dp(context, 14), Look.dp(context, 16), Look.dp(context, 16));
        card.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        from = text(20, Color.WHITE);
        from.setTypeface(Typeface.DEFAULT_BOLD);
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        time = text(16, Look.MUTED);
        time.setPaddingRelative(Look.dp(context, 8), 0, 0, 0);
        top.addView(from, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(time);
        body = text(19, Color.WHITE);
        body.setPadding(0, Look.dp(context, 6), 0, 0);
        body.setMaxLines(Sanitizer.MAX_LINES + 2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(top);
        content.addView(body);
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Look.dp(context, 320), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = Look.dp(context, 24);
        params.y = Look.dp(context, 24);
        return params;
    }

    /** Takes the window down and forgets every waiting note. */
    void hide() {
        main.removeCallbacks(advance);
        queue.clear();
        detach();
    }

    private void detach() {
        if (!attached) return;
        attached = false;
        try {
            windows.removeView(card);
        } catch (RuntimeException ignored) {
            // Already gone with its window.
        }
    }

    /** Plain text only: setText with a String never reads markup. */
    private TextView text(int sp, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return Look.directed(view);
    }
}
