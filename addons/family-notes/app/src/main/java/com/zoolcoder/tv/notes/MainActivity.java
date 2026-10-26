package com.zoolcoder.tv.notes;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * The card opens this screen. Start column: the receive switch and the "Connect a phone" steps (address, QR code,
 * PIN), then the pop-up permission. End column, the main one: the last 10 notes and Clear all notes. The page
 * itself is served by NoteService.
 */
public class MainActivity extends Activity {
    private static final String ADB_LINE = "adb shell appops set com.zoolcoder.tv.notes SYSTEM_ALERT_WINDOW allow";
    private static final int PIN_CHANGED_MS = 3000;

    private Store store;
    private Switch power;
    private TextView state, stateShort, address, unavailable, pin, overlay, overlayGlyph, pinChanged, clear;
    private LinearLayout overlayCard, notes;
    private QrView qr;
    private Dialog confirm;
    private final Runnable hidePinChanged = () -> pinChanged.setVisibility(View.INVISIBLE);
    /** Kept in a field: SharedPreferences holds its listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (p, key) -> refresh();
    private boolean binding;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Look.backdrop(this));
        page.setPadding(dp(48), dp(16), dp(48), dp(24));
        page.setClipChildren(false);

        page.addView(header());

        // Controls on the left, history on the right, in Arabic as well as English.
        LinearLayout columns = new LinearLayout(this);
        columns.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        columns.setClipChildren(false);
        columns.addView(settingsColumn(), new LinearLayout.LayoutParams(dp(392), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(dp(448), LinearLayout.LayoutParams.MATCH_PARENT);
        historyParams.setMarginStart(dp(24));
        columns.addView(historyColumn(), historyParams);
        LinearLayout.LayoutParams columnsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        columnsParams.topMargin = dp(8);
        page.addView(columns, columnsParams);

        page.addView(hintBar(), spaced(8, 0));
        setContentView(page);
        power.requestFocus();
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        View marker = new View(this);
        marker.setBackground(Look.shape(this, Look.ACCENT, 2));
        header.addView(marker, new LinearLayout.LayoutParams(dp(4), dp(26)));
        TextView title = bold(text(getString(R.string.title), 30, Look.TEXT));
        title.setPaddingRelative(dp(12), 0, 0, 0);
        header.addView(title);
        return header;
    }

    /** The start column scrolls as a whole when the pop-up warning makes it taller than the screen. */
    private View settingsColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        column.setClipChildren(false);
        column.addView(powerRow(), spaced(0, 0));
        column.addView(connectPanel(), spaced(16, 0));
        column.addView(overlayCard(), spaced(16, 0));

        // 8 dp around everything: room for a focused row's outline and scale.
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        scroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        scroll.setClipToOutline(true);
        scroll.addView(column);
        return scroll;
    }

    /** The whole row is the focus target and toggles the switch; the switch itself only shows the state. */
    private View powerRow() {
        LinearLayout powerRow = card();
        powerRow.setOrientation(LinearLayout.HORIZONTAL);
        powerRow.setGravity(Gravity.CENTER_VERTICAL);
        powerRow.setFocusable(true);
        powerRow.setClickable(true);
        Look.row(this, powerRow, Look.SURFACE, 14, 1.02f);
        LinearLayout powerText = new LinearLayout(this);
        powerText.setOrientation(LinearLayout.VERTICAL);
        powerText.addView(medium(text(getString(R.string.switch_label), 19, Look.TEXT)));
        state = text("", 15, Look.TEXT_2);
        powerText.addView(state);
        powerRow.addView(powerText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        stateShort = bold(text("", 16, Look.TEXT));
        stateShort.setPaddingRelative(dp(8), 0, dp(8), 0);
        powerRow.addView(stateShort);
        power = new Switch(this);
        power.setFocusable(false);
        power.setClickable(false);
        power.setThumbTintList(tint(Look.TEXT, Look.TEXT_2));
        power.setTrackTintList(tint(Look.ACCENT, Look.PILL));
        power.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            store.setEnabled(on);
            NoteService.sync(this);
            refresh();
        });
        powerRow.setOnClickListener(v -> power.toggle());
        powerRow.addView(power);
        return powerRow;
    }

    /** Three numbered steps, the QR code on the end side. The QR code opens the page with the PIN filled in. */
    private View connectPanel() {
        LinearLayout panel = card();
        panel.addView(medium(text(getString(R.string.connect_label), 20, Look.TEXT)));

        LinearLayout body = new LinearLayout(this);
        body.setClipChildren(false);
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.addView(step(1, getString(R.string.step_open)), spaced(12, 0));
        steps.addView(step(2, getString(R.string.step_scan)), spaced(12, 0));
        body.addView(steps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        qr = new QrView(this);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(112), dp(112));
        qrParams.setMarginStart(dp(12));
        qrParams.topMargin = dp(12);
        body.addView(qr, qrParams);
        panel.addView(body);

        unavailable = text("", 15, Look.TEXT_2);
        panel.addView(unavailable, spaced(8, 0));

        panel.addView(step(3, getString(R.string.pin_label)), spaced(12, 0));
        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        pinRow.setClipChildren(false);
        pinRow.setPaddingRelative(dp(36), 0, 0, 0);
        pin = text("", 24, Look.TEXT);
        pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pin.setPadding(dp(16), dp(6), dp(16), dp(6));
        Look.panel(this, pin, Look.PILL, 20);
        pinRow.addView(pin);
        TextView newPin = button(R.string.new_pin, v -> {
            store.newPin();
            refresh();
            pinChanged.setVisibility(View.VISIBLE);
            pinChanged.removeCallbacks(hidePinChanged);
            pinChanged.postDelayed(hidePinChanged, PIN_CHANGED_MS);
        });
        Look.pill(this, newPin, Look.ACCENT, Look.ON_LIGHT);
        LinearLayout.LayoutParams newPinParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newPinParams.setMarginStart(dp(12));
        pinRow.addView(newPin, newPinParams);
        panel.addView(pinRow, spaced(8, 0));

        // Invisible, not gone: the status appearing must not move the panel.
        pinChanged = text(getString(R.string.pin_changed), 14, Look.ACCENT);
        pinChanged.setVisibility(View.INVISIBLE);
        pinChanged.setPaddingRelative(dp(36), 0, 0, 0);
        panel.addView(pinChanged, spaced(4, 0));
        TextView lockout = text(getString(R.string.pin_hint), 14, Look.TEXT_3);
        lockout.setPaddingRelative(dp(36), 0, 0, 0);
        panel.addView(lockout);

        address = text("", 13, Look.TEXT_3);
        address.setTypeface(Typeface.MONOSPACE);
        address.setPaddingRelative(dp(36), dp(8), 0, 0);
        panel.addView(address);
        return panel;
    }

    private View step(int number, String label) {
        LinearLayout step = new LinearLayout(this);
        step.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = bold(text(String.valueOf(number), 14, Look.ON_LIGHT));
        badge.setGravity(Gravity.CENTER);
        Look.panel(this, badge, Look.ACCENT, 12);
        step.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView words = text(label, 16, Look.TEXT);
        words.setPaddingRelative(dp(12), 0, 0, 0);
        step.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return step;
    }

    private View overlayCard() {
        overlayCard = card();
        overlayCard.setOrientation(LinearLayout.HORIZONTAL);
        overlayCard.setGravity(Gravity.CENTER_VERTICAL);
        overlayGlyph = bold(text("", 18, Look.ACCENT));
        overlayGlyph.setGravity(Gravity.CENTER);
        overlayCard.addView(overlayGlyph, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout overlayText = new LinearLayout(this);
        overlayText.setOrientation(LinearLayout.VERTICAL);
        overlayText.setPaddingRelative(dp(12), 0, 0, 0);
        overlayText.addView(label(R.string.overlay_label));
        overlay = text("", 15, Look.TEXT_2);
        overlayText.addView(overlay);
        overlayCard.addView(overlayText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return overlayCard;
    }

    /** The primary area: heading with Clear all notes at its end, then the notes in a scroller, newest first. */
    private LinearLayout historyColumn() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        panel.setPadding(dp(16), dp(16), dp(16), dp(8));
        panel.setClipChildren(false);
        Look.panel(this, panel, Look.SURFACE, 18);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        TextView notesLabel = medium(text(getString(R.string.notes_label), 20, Look.TEXT));
        notesLabel.setPaddingRelative(dp(4), 0, 0, 0);
        top.addView(notesLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        clear = button(R.string.clear, v -> showConfirm());
        Look.pill(this, clear, Look.DESTRUCTIVE, Look.TEXT);
        top.addView(clear);
        panel.addView(top);

        notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setClipChildren(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(0, dp(8), 0, dp(8));
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        scroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        scroll.setClipToOutline(true);
        scroll.addView(notes);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    /** Nothing is deleted until the second button: focus starts on Cancel and Back cancels. */
    private void showConfirm() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setClipChildren(false);
        Look.panel(this, box, 0xFF16281D, 18);
        box.addView(medium(text(getString(R.string.confirm_title), 22, Look.TEXT)));
        TextView body = text(getString(R.string.confirm_body), 16, Look.TEXT_2);
        body.setPadding(0, dp(8), 0, dp(24));
        box.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        buttons.setGravity(Gravity.END);
        TextView cancel = button(R.string.cancel, v -> dialog.dismiss());
        Look.pill(this, cancel, Look.PILL, Look.TEXT);
        buttons.addView(cancel);
        TextView confirmClear = button(R.string.clear, v -> {
            dialog.dismiss();
            store.clearNotes();
            refresh();
        });
        Look.pill(this, confirmClear, Look.DESTRUCTIVE, Look.TEXT);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        confirmParams.setMarginStart(dp(12));
        buttons.addView(confirmClear, confirmParams);
        box.addView(buttons);

        dialog.setContentView(box, new ViewGroup.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.setOnShowListener(d -> cancel.requestFocus());
        confirm = dialog;
        dialog.show();
    }

    /** Key chips and what they do, at the bottom start. */
    private View hintBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        bar.addView(chip(R.string.key_ok));
        bar.addView(hintLabel(R.string.hint_select, 8));
        bar.addView(chip(R.string.key_back), spacedStart(24));
        bar.addView(hintLabel(R.string.hint_home, 8));
        return bar;
    }

    private TextView chip(int key) {
        TextView chip = bold(text(getString(key), 12, Look.TEXT));
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        Look.panel(this, chip, Look.PILL, 10);
        return chip;
    }

    private TextView hintLabel(int label, int startMargin) {
        TextView view = text(getString(label), 14, Look.TEXT_2);
        view.setPaddingRelative(dp(startMargin), 0, 0, 0);
        return view;
    }

    private LinearLayout.LayoutParams spacedStart(int start) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(start));
        return params;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setClipChildren(false);
        Look.panel(this, card, Look.SURFACE, 14);
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
        if (confirm != null) confirm.dismiss();
        pinChanged.removeCallbacks(hidePinChanged);
        store.prefs().unregisterOnSharedPreferenceChangeListener(changes);
        super.onStop();
    }

    private void refresh() {
        boolean on = store.enabled();
        binding = true;
        power.setChecked(on);
        binding = false;

        String current = store.state();
        stateShort.setText(on ? R.string.state_on : R.string.state_off_short);
        if (!on) state.setText(R.string.state_off);
        else if ("port".equals(current)) state.setText(R.string.state_port);
        else if ("running".equals(current)) state.setText(R.string.state_running);
        else state.setText(R.string.state_starting);

        showAddress(on, Net.homeAddresses());
        pin.setText(store.pin());

        if (OverlayCard.allowed(this)) {
            overlay.setText(R.string.overlay_ok);
            overlay.setTextColor(Look.ACCENT);
            overlayGlyph.setText("\u2713");
            overlayGlyph.setTextColor(Look.ACCENT);
            Look.panel(this, overlayCard, Look.SURFACE, 14);
        } else {
            overlay.setText(getString(R.string.overlay_missing, ADB_LINE));
            overlay.setTextColor(Look.WARN);
            overlayGlyph.setText("!");
            overlayGlyph.setTextColor(Look.WARN);
            Look.panel(this, overlayCard, Look.WARN_FILL, 14);
        }

        notes.removeAllViews();
        List<Note> list = store.notes();
        if (list.isEmpty()) notes.addView(text(getString(R.string.no_notes), 18, Look.TEXT_3));
        for (Note note : list) notes.addView(row(note));
        clear.setEnabled(!list.isEmpty());
        clear.setFocusable(!list.isEmpty());
        clear.setAlpha(list.isEmpty() ? 0.4f : 1f);
    }

    /** Off, the panel says so: a phone that scans or types the address then would find nothing. */
    private void showAddress(boolean on, List<String> addresses) {
        if (!on || addresses.isEmpty()) {
            qr.setVisibility(View.INVISIBLE);
            unavailable.setText(on ? R.string.no_address : R.string.address_off);
            unavailable.setVisibility(View.VISIBLE);
            address.setVisibility(View.GONE);
            return;
        }
        int port = getResources().getInteger(R.integer.port);
        StringBuilder lines = new StringBuilder(getString(R.string.address_label));
        for (String ip : addresses) lines.append("\nhttp://").append(ip).append(':').append(port);
        // The PIN rides in the fragment, which the browser keeps to itself: the page reads it and fills it in.
        qr.show(QrCode.encode("http://" + addresses.get(0) + ":" + port + "/#" + store.pin()));
        qr.setVisibility(View.VISIBLE);
        unavailable.setVisibility(View.GONE);
        address.setText(lines);
        address.setVisibility(View.VISIBLE);
    }

    private View row(Note note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setFocusable(true);
        row.setClipChildren(false);
        Look.row(this, row, 0x14FFFFFF, 14, 1.02f);
        TextView body = Look.directed(text(note.text, 18, Look.TEXT));
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(body);

        LinearLayout meta = new LinearLayout(this);
        TextView sender = Look.directed(text(note.name, 15, Look.TEXT_2));
        sender.setSingleLine(true);
        sender.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(sender, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String when = DateUtils.formatDateTime(this, note.time,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL);
        TextView time = text(when, 14, Look.TEXT_3);
        time.setPaddingRelative(dp(12), 0, 0, 0);
        meta.addView(time);
        row.addView(meta, spaced(4, 0));
        row.setLayoutParams(spaced(0, 8));
        return row;
    }

    private TextView button(int label, View.OnClickListener onClick) {
        TextView button = medium(text(getString(label), 18, Look.TEXT));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(onClick);
        return button;
    }

    private TextView label(int resource) {
        TextView label = text(getString(resource), 15, Look.TEXT_2);
        label.setPadding(0, 0, 0, dp(4));
        return label;
    }

    private TextView medium(TextView view) {
        return Look.medium(view);
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
