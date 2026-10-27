package com.zoolcoder.tv.calendar;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The detail of one event, with Delete. Focus starts on Close. Delete asks again, with focus on Cancel, and
 * Back cancels at every step.
 */
final class EventDialog {
    private final Activity activity;
    private final EventStore store;
    private Dialog open;

    EventDialog(Activity activity, EventStore store) {
        this.activity = activity;
        this.store = store;
    }

    void show(Occurrence o) {
        Event e = o.event;
        Dates dates = new Dates(activity);
        LinearLayout box = box();

        LinearLayout head = Ui.row(activity);
        View bar = new View(activity);
        bar.setBackground(Look.shape(activity, Look.WHO[e.color], 2));
        head.addView(bar, new LinearLayout.LayoutParams(Look.dp(activity, 4), Look.dp(activity, 28)));
        TextView title = Look.directed(Look.medium(Ui.text(activity, e.title, 24, Look.TEXT)));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPaddingRelative(Look.dp(activity, 12), 0, 0, 0);
        head.addView(title, Ui.fill(1f));
        box.addView(head);

        line(box, dates.day(o.day), Look.TEXT, 16);
        line(box, dates.hijri(o.day), Look.TEXT_2, 4);
        String time = e.allDay() ? activity.getString(R.string.all_day)
                : e.end == null ? dates.time(e.start) : dates.time(e.start) + " – " + dates.time(e.end);
        line(box, time, Look.TEXT, 12);
        if (e.who != null) line(box, activity.getString(R.string.for_who, e.who), Look.TEXT_2, 4);
        if (e.away) line(box, activity.getString(R.string.from_abroad), Look.ACCENT, 4);
        if (e.repeat != Event.Repeat.NONE) {
            line(box, activity.getString(e.repeat == Event.Repeat.WEEKLY ? R.string.repeat_weekly : R.string.repeat_yearly),
                    Look.TEXT_2, 4);
        }
        line(box, activity.getString(reminder(e.reminder)), Look.TEXT_2, 4);

        Dialog dialog = dialog();
        TextView close = Ui.button(activity, activity.getString(R.string.close), v -> dialog.dismiss());
        TextView delete = Ui.button(activity, activity.getString(R.string.delete), v -> {
            dialog.dismiss();
            confirm(e);
        });
        box.addView(buttons(close, delete), Ui.spaced(activity, 24, 0));
        present(dialog, box, close);
    }

    private void confirm(Event e) {
        int body = e.repeat == Event.Repeat.NONE ? R.string.confirm_body : R.string.confirm_body_repeat;
        confirm(activity.getString(R.string.confirm_title), activity.getString(body), activity.getString(R.string.delete),
                () -> store.delete(e.id));
    }

    /** Asks before something that cannot be undone, with focus on Cancel; Back cancels. */
    void confirm(String title, String body, String action, Runnable yes) {
        LinearLayout box = box();
        box.addView(Look.medium(Ui.text(activity, title, 22, Look.TEXT)));
        line(box, body, Look.TEXT_2, 8);
        Dialog dialog = dialog();
        TextView cancel = Ui.button(activity, activity.getString(R.string.cancel), v -> dialog.dismiss());
        TextView go = Ui.button(activity, action, v -> {
            dialog.dismiss();
            yes.run();
        });
        box.addView(buttons(cancel, go), Ui.spaced(activity, 24, 0));
        present(dialog, box, cancel);
    }

    void dismiss() {
        if (open != null) open.dismiss();
        open = null;
    }

    private static int reminder(Event.Reminder r) {
        switch (r) {
            case AT_TIME: return R.string.reminder_at;
            case MIN_10: return R.string.reminder_10m;
            case HOUR_1: return R.string.reminder_1h;
            case DAY_1: return R.string.reminder_1d;
            default: return R.string.reminder_none;
        }
    }

    private LinearLayout box() {
        LinearLayout box = Ui.column(activity);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        int p = Look.dp(activity, 24);
        box.setPadding(p, p, p, p);
        Look.panel(activity, box, 0xFF1C1D2E, 18);
        return box;
    }

    private void line(LinearLayout box, String value, int color, int top) {
        TextView view = Look.directed(Ui.text(activity, value, 16, color));
        box.addView(view, Ui.spaced(activity, top, 0));
    }

    private LinearLayout buttons(TextView quiet, TextView destructive) {
        LinearLayout buttons = Ui.row(activity);
        buttons.setGravity(Gravity.END);
        Look.pill(activity, quiet, Look.PILL, Look.TEXT);
        buttons.addView(quiet);
        Look.pill(activity, destructive, Look.DESTRUCTIVE, Look.TEXT);
        buttons.addView(destructive, Ui.start(activity, 12));
        return buttons;
    }

    private Dialog dialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    private void present(Dialog dialog, LinearLayout box, View first) {
        dialog.setContentView(box, new ViewGroup.LayoutParams(Look.dp(activity, 480), ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.setOnShowListener(d -> first.requestFocus());
        open = dialog;
        dialog.show();
    }
}
