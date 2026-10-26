package com.zoolcoder.tv.notes;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * The card opens this screen: the on/off switch, the address to type on the phone, the PIN, whether
 * pop-ups are allowed, and the last 10 notes. The page itself is served by NoteService.
 */
public class MainActivity extends Activity {
    private static final String ADB_LINE = "adb shell appops set com.zoolcoder.tv.notes SYSTEM_ALERT_WINDOW allow";

    private Store store;
    private Switch power;
    private TextView state, address, pin, overlay, pinChanged, clear;
    private boolean clearArmed;
    private LinearLayout notes;
    /** Kept in a field: SharedPreferences holds its listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (p, key) -> refresh();
    private boolean binding;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(28), dp(48), dp(28));
        page.setClipChildren(false);

        page.addView(header());

        // Controls on the left, history on the right, in Arabic as well as English.
        LinearLayout columns = new LinearLayout(this);
        columns.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        columns.setClipChildren(false);
        columns.addView(settingsColumn(), new LinearLayout.LayoutParams(dp(392), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(dp(448), LinearLayout.LayoutParams.WRAP_CONTENT);
        historyParams.setMarginStart(dp(24));
        columns.addView(historyColumn(), historyParams);
        page.addView(columns, spaced(20, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Look.BACKGROUND);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(page);
        setContentView(scroll);
        power.requestFocus();
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        marker.setBackgroundColor(Look.FOCUS);
        header.addView(marker, new LinearLayout.LayoutParams(dp(6), dp(28)));
        TextView title = bold(text(getString(R.string.title), 28, Color.WHITE));
        title.setPaddingRelative(dp(12), 0, 0, 0);
        header.addView(title);
        return header;
    }

    private LinearLayout settingsColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        column.setClipChildren(false);

        power = new Switch(this);
        power.setText(R.string.switch_label);
        power.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        power.setTextColor(Color.WHITE);
        power.setPadding(dp(16), dp(12), dp(16), dp(12));
        power.setThumbTintList(tint(Color.WHITE, Look.MUTED));
        power.setTrackTintList(tint(Look.FOCUS, Look.DIVIDER));
        Look.ring(this, power, Look.CARD, 10);
        power.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            store.setEnabled(on);
            NoteService.sync(this);
            refresh();
        });
        column.addView(power, spaced(0, 4));

        state = text("", 16, Look.MUTED);
        state.setPadding(dp(4), 0, dp(4), 0);
        column.addView(state);

        LinearLayout addressCard = card();
        addressCard.addView(label(R.string.address_label));
        address = text("", 24, Color.WHITE);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addressCard.addView(address);
        column.addView(addressCard, spaced(12, 0));

        LinearLayout pinCard = card();
        pinCard.addView(label(R.string.pin_label));
        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        pinRow.setClipChildren(false);
        pin = bold(text("", 28, Look.FOCUS));
        pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinRow.addView(pin, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pinRow.addView(button(R.string.new_pin, v -> {
            store.newPin();
            refresh();
            pinChanged.setVisibility(View.VISIBLE);
        }));
        pinCard.addView(pinRow);
        pinChanged = text(getString(R.string.pin_changed), 16, Look.FOCUS);
        pinChanged.setVisibility(View.GONE);
        pinCard.addView(pinChanged);
        pinCard.addView(text(getString(R.string.pin_hint), 16, Look.MUTED));
        column.addView(pinCard, spaced(12, 0));

        LinearLayout overlayCard = card();
        overlayCard.addView(label(R.string.overlay_label));
        overlay = text("", 18, Look.MUTED);
        overlayCard.addView(overlay);
        column.addView(overlayCard, spaced(12, 0));
        return column;
    }

    private LinearLayout historyColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        column.setClipChildren(false);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        TextView notesLabel = bold(text(getString(R.string.notes_label), 20, Color.WHITE));
        head.addView(notesLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        clear = button(R.string.clear, v -> {
            if (!clearArmed) {
                clearArmed = true;
                clear.setText(R.string.clear_confirm);
                return;
            }
            disarmClear();
            store.clearNotes();
            refresh();
        });
        clear.setOnFocusChangeListener(chain(clear.getOnFocusChangeListener(), (v, focused) -> {
            if (!focused) disarmClear();
        }));
        head.addView(clear);
        column.addView(head, spaced(0, 8));

        notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setClipChildren(false);
        column.addView(notes);
        return column;
    }

    private void disarmClear() {
        clearArmed = false;
        clear.setText(R.string.clear);
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener first, View.OnFocusChangeListener second) {
        return (v, focused) -> {
            first.onFocusChange(v, focused);
            second.onFocusChange(v, focused);
        };
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setClipChildren(false);
        Look.panel(this, card, Look.CARD, 10);
        return card;
    }

    private static ColorStateList tint(int on, int off) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{on, off});
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.prefs().registerOnSharedPreferenceChangeListener(changes);
        store.pin();
        // Starts the server again if the system dropped it.
        NoteService.sync(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStop() {
        store.prefs().unregisterOnSharedPreferenceChangeListener(changes);
        super.onStop();
    }

    private void refresh() {
        boolean on = store.enabled();
        binding = true;
        power.setChecked(on);
        binding = false;

        String current = store.state();
        if (!on) state.setText(R.string.state_off);
        else if ("port".equals(current)) state.setText(R.string.state_port);
        else if ("running".equals(current)) state.setText(R.string.state_running);
        else state.setText(R.string.state_starting);

        List<String> addresses = Net.homeAddresses();
        if (addresses.isEmpty()) {
            address.setText(R.string.no_address);
        } else {
            StringBuilder lines = new StringBuilder();
            int port = getResources().getInteger(R.integer.port);
            for (String ip : addresses) {
                if (lines.length() > 0) lines.append('\n');
                lines.append("http://").append(ip).append(':').append(port);
            }
            address.setText(lines.toString());
        }
        pin.setText(store.pin());

        if (OverlayCard.allowed(this)) {
            overlay.setText(R.string.overlay_ok);
            overlay.setTextColor(Look.FOCUS);
        } else {
            overlay.setText(getString(R.string.overlay_missing, ADB_LINE));
            overlay.setTextColor(Look.WARN);
        }

        notes.removeAllViews();
        List<Note> list = store.notes();
        if (list.isEmpty()) notes.addView(text(getString(R.string.no_notes), 18, Look.MUTED));
        for (Note note : list) notes.addView(row(note));
    }

    private View row(Note note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setFocusable(true);
        Look.ring(this, row, Look.CARD, 10);
        String when = DateUtils.formatDateTime(this, note.time,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL);
        row.addView(Look.directed(text(note.name + " · " + when, 16, Look.MUTED)));
        row.addView(Look.directed(text(note.text, 19, Color.WHITE)));
        row.setLayoutParams(spaced(0, 8));
        return row;
    }

    private TextView button(int label, View.OnClickListener onClick) {
        TextView button = bold(text(getString(label), 18, Color.WHITE));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(20), dp(8), dp(20), dp(8));
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(onClick);
        Look.ring(this, button, Look.RAISED, 10);
        return button;
    }

    private TextView label(int resource) {
        TextView label = text(getString(resource), 18, Look.MUTED);
        label.setPadding(0, 0, 0, dp(4));
        return label;
    }

    private TextView bold(TextView view) {
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private int dp(float v) {
        return Look.dp(this, v);
    }
}
