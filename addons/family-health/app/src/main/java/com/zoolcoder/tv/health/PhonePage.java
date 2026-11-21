package com.zoolcoder.tv.health;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Add from a phone: the switch and what a phone can do on the start side, the three steps with the QR code and
 * PIN on the end side. The same flow as Family Calendar and Family Notes.
 */
final class PhonePage {
    private static final int PIN_CHANGED_MS = 3000;

    private final Activity a;
    private final Phone phone;
    private final LinearLayout view;
    private LinearLayout powerRow;
    private Switch power;
    private TextView state, stateShort, address, unavailable, pin, pinChanged;
    private final Runnable hidePinChanged = () -> pinChanged.setVisibility(View.INVISIBLE);
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> refresh();
    private QrView qr;
    private boolean binding;

    PhonePage(Activity a, Phone phone) {
        this.a = a;
        this.phone = phone;
        view = Ui.row(a);
        view.setGravity(Gravity.TOP);
        view.setClipChildren(false);
        LinearLayout start = Ui.column(a);
        start.setClipChildren(false);
        int p = dp(8);
        start.setPadding(p, p, p, p);
        start.addView(powerRow());
        start.addView(whatCard(), Ui.spaced(a, 16, 0));
        view.addView(start, new LinearLayout.LayoutParams(dp(400), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        panelParams.setMarginStart(dp(16));
        panelParams.topMargin = p;
        view.addView(connectPanel(), panelParams);
    }

    View view() {
        return view;
    }

    void focus() {
        powerRow.requestFocus();
    }

    /** The whole row is the focus target and toggles the switch; the switch itself only shows the state. */
    private View powerRow() {
        powerRow = card();
        powerRow.setOrientation(LinearLayout.HORIZONTAL);
        powerRow.setGravity(Gravity.CENTER_VERTICAL);
        powerRow.setFocusable(true);
        powerRow.setClickable(true);
        Look.row(a, powerRow, Look.SURFACE, 20, 1.02f);
        LinearLayout words = Ui.column(a);
        words.addView(Look.medium(Ui.text(a, a.getString(R.string.phone_switch), 19, Look.TEXT)));
        state = Ui.text(a, "", 15, Look.TEXT_2);
        words.addView(state);
        powerRow.addView(words, Ui.fill(1f));
        stateShort = Ui.bold(Ui.text(a, "", 16, Look.TEXT));
        stateShort.setPaddingRelative(dp(8), 0, dp(8), 0);
        powerRow.addView(stateShort);
        power = new Switch(a);
        power.setFocusable(false);
        power.setClickable(false);
        power.setThumbTintList(tint(Look.TEXT, Look.TEXT_2));
        power.setTrackTintList(tint(Look.ACCENT, Look.PILL));
        power.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            phone.setEnabled(on);
            if (on) phone.setState("");
            HealthService.sync(a);
            refresh();
        });
        powerRow.setOnClickListener(v -> power.toggle());
        powerRow.addView(power);
        return powerRow;
    }

    private View whatCard() {
        LinearLayout card = card();
        card.addView(Look.medium(Ui.text(a, a.getString(R.string.phone_can), 17, Look.TEXT)));
        for (int line : new int[]{R.string.phone_can_people, R.string.phone_can_medicines,
                R.string.phone_can_visits}) {
            card.addView(Ui.text(a, "•  " + a.getString(line), 15, Look.TEXT_2), Ui.spaced(a, 6, 0));
        }
        TextView home = Ui.text(a, a.getString(R.string.phone_home_only, phone.minutes()), 14, Look.TEXT_3);
        card.addView(home, Ui.spaced(a, 10, 0));
        return card;
    }

    /** Three numbered steps, the QR code on the end side. The QR code opens the page with the PIN filled in. */
    private View connectPanel() {
        LinearLayout panel = card();
        panel.addView(Look.medium(Ui.text(a, a.getString(R.string.connect_label), 20, Look.TEXT)));

        LinearLayout body = Ui.row(a);
        body.setGravity(Gravity.TOP);
        LinearLayout steps = Ui.column(a);
        steps.addView(step(1, a.getString(R.string.step_open)), Ui.spaced(a, 12, 0));
        steps.addView(step(2, a.getString(R.string.step_scan)), Ui.spaced(a, 12, 0));
        body.addView(steps, Ui.fill(1f));
        qr = new QrView(a);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(132), dp(132));
        qrParams.setMarginStart(dp(12));
        qrParams.topMargin = dp(12);
        body.addView(qr, qrParams);
        panel.addView(body);

        unavailable = Ui.text(a, "", 15, Look.TEXT_2);
        panel.addView(unavailable, Ui.spaced(a, 8, 0));

        panel.addView(step(3, a.getString(R.string.pin_label)), Ui.spaced(a, 12, 0));
        LinearLayout pinRow = Ui.row(a);
        pinRow.setClipChildren(false);
        pinRow.setPaddingRelative(dp(36), 0, 0, 0);
        pin = Ui.text(a, "", 24, Look.TEXT);
        pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pin.setPadding(dp(16), dp(6), dp(16), dp(6));
        Look.panel(a, pin, Look.PILL, 20);
        pinRow.addView(pin);
        TextView newPin = Ui.button(a, a.getString(R.string.new_pin), v -> {
            phone.newPin();
            refresh();
            pinChanged.setVisibility(View.VISIBLE);
            pinChanged.removeCallbacks(hidePinChanged);
            pinChanged.postDelayed(hidePinChanged, PIN_CHANGED_MS);
        });
        Look.pill(a, newPin, Look.PILL, Look.TEXT);
        pinRow.addView(newPin, Ui.start(a, 12));
        panel.addView(pinRow, Ui.spaced(a, 8, 0));

        // Invisible, not gone: the status appearing must not move the panel.
        pinChanged = Ui.text(a, a.getString(R.string.pin_changed), 14, Look.ACCENT);
        pinChanged.setVisibility(View.INVISIBLE);
        pinChanged.setPaddingRelative(dp(36), 0, 0, 0);
        panel.addView(pinChanged, Ui.spaced(a, 4, 0));
        TextView lockout = Ui.text(a, a.getString(R.string.pin_hint), 14, Look.TEXT_3);
        lockout.setPaddingRelative(dp(36), 0, 0, 0);
        panel.addView(lockout);

        address = Ui.text(a, "", 13, Look.TEXT_3);
        address.setTypeface(Typeface.MONOSPACE);
        address.setPaddingRelative(dp(36), dp(8), 0, 0);
        panel.addView(address);
        return panel;
    }

    private View step(int number, String label) {
        LinearLayout step = Ui.row(a);
        TextView badge = Ui.bold(Ui.text(a, String.valueOf(number), 14, Look.TEXT));
        badge.setGravity(Gravity.CENTER);
        Look.panel(a, badge, Look.ACCENT, 12);
        step.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView words = Ui.text(a, label, 16, Look.TEXT);
        words.setPaddingRelative(dp(12), 0, 0, 0);
        step.addView(words, Ui.fill(1f));
        return step;
    }

    void refresh() {
        // A page past its time shows as off even before the service gets round to switching it off.
        boolean on = phone.enabled() && !phone.expired(System.currentTimeMillis());
        binding = true;
        power.setChecked(on);
        binding = false;

        String current = phone.state();
        stateShort.setText(on ? R.string.state_on : R.string.state_off_short);
        if (!on) state.setText(R.string.state_off);
        else if ("port".equals(current)) state.setText(R.string.state_port);
        else if ("running".equals(current)) state.setText(a.getString(R.string.state_running, offAt()));
        else state.setText(R.string.state_starting);

        showAddress(on && "running".equals(current), on, Net.homeAddresses());
        pin.setText(grouped(phone.pin()));
    }

    /** The time the page switches itself off, in the TV's clock format. */
    private String offAt() {
        LocalTime at = Instant.ofEpochMilli(phone.onUntil()).atZone(ZoneId.systemDefault()).toLocalTime();
        return new Dates(a).time(at);
    }

    /** 1234 5678: two groups of four are easier to read off the screen and type. */
    static String grouped(String pin) {
        int half = pin.length() / 2;
        return pin.substring(0, half) + " " + pin.substring(half);
    }

    /** While shown, the page follows the server starting or failing, and a PIN replaced after wrong guesses. */
    void start() {
        phone.listen(changed);
        refresh();
    }

    void stop() {
        phone.unlisten(changed);
        pinChanged.removeCallbacks(hidePinChanged);
    }

    /** Until the server listens, the panel says so: a phone that scans or types the address would find nothing. */
    private void showAddress(boolean running, boolean on, List<String> addresses) {
        if (!running || addresses.isEmpty()) {
            qr.setVisibility(View.INVISIBLE);
            unavailable.setText(!on ? R.string.address_off : running ? R.string.no_address : R.string.address_wait);
            unavailable.setVisibility(View.VISIBLE);
            address.setVisibility(View.GONE);
            return;
        }
        int port = HealthService.port(a);
        StringBuilder lines = new StringBuilder(a.getString(R.string.address_label));
        for (String ip : addresses) lines.append("\nhttp://").append(ip).append(':').append(port);
        // The PIN rides in the fragment, which the browser keeps to itself: the page reads it and fills it in.
        qr.show(QrCode.encode("http://" + addresses.get(0) + ":" + port + "/#" + phone.pin()));
        qr.setVisibility(View.VISIBLE);
        unavailable.setVisibility(View.GONE);
        address.setText(lines);
        address.setVisibility(View.VISIBLE);
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(a);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        Look.panel(a, card, Look.SURFACE, 20);
        return card;
    }

    private static ColorStateList tint(int on, int off) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{on, off});
    }

    private int dp(float v) {
        return Look.dp(a, v);
    }
}
