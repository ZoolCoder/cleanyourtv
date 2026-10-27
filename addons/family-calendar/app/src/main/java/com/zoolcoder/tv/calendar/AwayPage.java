package com.zoolcoder.tv.calendar;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Family away: the switch, how the relay is doing, New link and Delete box on the start side, the invite QR code
 * and link on the end side. Shown only when the build sets a relay. The relay calls run on the Family away
 * thread; the page redraws from the box's preferences when they finish.
 */
final class AwayPage {
    private static final int LINK_CHANGED_MS = 3000;

    private final Activity a;
    private final Store store;
    private final AwayBox box;
    private final AwayPoller poller;
    private final EventDialog dialog;
    private final String relay;
    private final LinearLayout view;
    private LinearLayout powerRow;
    private Switch power;
    private TextView state, stateShort, unavailable, link, linkChanged;
    private View newLink, delete;
    private final Runnable hideLinkChanged = () -> linkChanged.setVisibility(View.INVISIBLE);
    private QrView qr;
    private boolean binding;

    AwayPage(Activity a, Store store, AwayBox box, AwayPoller poller, EventDialog dialog) {
        this.a = a;
        this.store = store;
        this.box = box;
        this.poller = poller;
        this.dialog = dialog;
        relay = a.getString(R.string.relay_url);
        view = Ui.row(a);
        view.setGravity(Gravity.TOP);
        LinearLayout start = Ui.column(a);
        int p = dp(8);
        start.setPadding(p, p, p, p);
        start.addView(powerRow());
        start.addView(actions(), Ui.spaced(a, 16, 0));
        view.addView(start, new LinearLayout.LayoutParams(dp(392), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        panelParams.setMarginStart(dp(16));
        panelParams.topMargin = p;
        view.addView(invitePanel(), panelParams);
    }

    View view() {
        return view;
    }

    void focus() {
        powerRow.requestFocus();
    }

    /** The whole row is the focus target and toggles the switch, as on the Connect tab. */
    private View powerRow() {
        powerRow = card();
        powerRow.setOrientation(LinearLayout.HORIZONTAL);
        powerRow.setGravity(Gravity.CENTER_VERTICAL);
        powerRow.setFocusable(true);
        powerRow.setClickable(true);
        Look.row(a, powerRow, Look.SURFACE, 14, 1.02f);
        LinearLayout words = Ui.column(a);
        words.addView(Look.medium(Ui.text(a, a.getString(R.string.away_switch), 19, Look.TEXT)));
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
            box.setOn(on);
            // The service polls once on every start command, so turning it on sets the box up right away.
            CalendarService.sync(a);
            refresh();
        });
        powerRow.setOnClickListener(v -> power.toggle());
        powerRow.addView(power);
        return powerRow;
    }

    private View actions() {
        LinearLayout row = Ui.row(a);
        TextView fresh = Ui.button(a, a.getString(R.string.away_new_link), v -> dialog.confirm(
                a.getString(R.string.away_new_link_title), a.getString(R.string.away_new_link_body),
                a.getString(R.string.away_new_link), this::rotate));
        Look.pill(a, fresh, Look.ACCENT, Look.TEXT);
        row.addView(fresh);
        TextView remove = Ui.button(a, a.getString(R.string.away_delete), v -> dialog.confirm(
                a.getString(R.string.away_delete_title), a.getString(R.string.away_delete_body),
                a.getString(R.string.away_delete), this::delete));
        Look.pill(a, remove, Look.DESTRUCTIVE, Look.TEXT);
        row.addView(remove, Ui.start(a, 12));
        newLink = fresh;
        delete = remove;
        return row;
    }

    private View invitePanel() {
        LinearLayout panel = card();
        panel.addView(Look.medium(Ui.text(a, a.getString(R.string.away_invite), 20, Look.TEXT)));
        LinearLayout body = Ui.row(a);
        body.setGravity(Gravity.TOP);
        LinearLayout words = Ui.column(a);
        words.addView(Ui.text(a, a.getString(R.string.away_invite_hint), 16, Look.TEXT), Ui.spaced(a, 12, 0));
        unavailable = Ui.text(a, "", 15, Look.TEXT_2);
        words.addView(unavailable, Ui.spaced(a, 8, 0));
        body.addView(words, Ui.fill(1f));
        qr = new QrView(a);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(150), dp(150));
        qrParams.setMarginStart(dp(12));
        qrParams.topMargin = dp(12);
        body.addView(qr, qrParams);
        panel.addView(body);

        // The link is Latin either way: left to right, so its dots and slashes stay in order in Arabic.
        link = Ui.text(a, "", 13, Look.TEXT_3);
        link.setTypeface(Typeface.MONOSPACE);
        link.setTextDirection(View.TEXT_DIRECTION_LTR);
        link.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panel.addView(link, Ui.spaced(a, 12, 0));
        // Invisible, not gone: the note appearing must not move the panel.
        linkChanged = Ui.text(a, a.getString(R.string.away_link_changed), 14, Look.ACCENT);
        linkChanged.setVisibility(View.INVISIBLE);
        panel.addView(linkChanged, Ui.spaced(a, 4, 0));
        return panel;
    }

    private void rotate() {
        AwayPoller.THREAD.execute(() -> {
            boolean done = poller.rotate();
            a.runOnUiThread(() -> {
                refresh();
                if (!done) return;
                linkChanged.setVisibility(View.VISIBLE);
                linkChanged.removeCallbacks(hideLinkChanged);
                linkChanged.postDelayed(hideLinkChanged, LINK_CHANGED_MS);
            });
        });
    }

    private void delete() {
        AwayPoller.THREAD.execute(() -> {
            poller.delete();
            a.runOnUiThread(() -> {
                CalendarService.sync(a);
                refresh();
            });
        });
    }

    void refresh() {
        boolean on = box.on();
        binding = true;
        power.setChecked(on);
        binding = false;
        stateShort.setText(on ? R.string.state_on : R.string.state_off_short);
        String status = box.status();
        boolean ready = on && store.enabled() && box.created() && !AwayBox.STATUS_GONE.equals(status);
        if (!on) state.setText(R.string.away_off);
        else if (!store.enabled()) state.setText(R.string.away_needs_server);
        else if (AwayBox.STATUS_GONE.equals(status)) state.setText(R.string.away_revoked);
        else if (AwayBox.STATUS_OFFLINE.equals(status)) state.setText(R.string.away_offline);
        else if (!box.created()) state.setText(R.string.away_setting_up);
        else state.setText(lastContact());
        state.setTextColor(on && (AwayBox.STATUS_GONE.equals(status) || AwayBox.STATUS_OFFLINE.equals(status))
                ? Look.WARN : Look.TEXT_2);
        newLink.setEnabled(ready);
        newLink.setFocusable(ready);
        newLink.setAlpha(ready ? 1f : 0.4f);
        boolean any = !box.boxId().isEmpty();
        delete.setEnabled(any);
        delete.setFocusable(any);
        delete.setAlpha(any ? 1f : 0.4f);
        showInvite(ready);
    }

    private String lastContact() {
        long minutes = (System.currentTimeMillis() - box.lastContact()) / 60_000L;
        return minutes < 1 ? a.getString(R.string.away_just_now) : a.getString(R.string.away_minutes, (int) minutes);
    }

    private void showInvite(boolean ready) {
        if (!ready) {
            qr.setVisibility(View.INVISIBLE);
            link.setVisibility(View.GONE);
            unavailable.setText(state.getText());
            unavailable.setVisibility(View.VISIBLE);
            return;
        }
        String lang = "ar".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? "ar" : "en";
        String invite = AwayPoller.invite(relay, box, lang);
        qr.show(QrCode.encode(invite));
        qr.setVisibility(View.VISIBLE);
        link.setText(invite);
        link.setVisibility(View.VISIBLE);
        unavailable.setVisibility(View.GONE);
    }

    void stop() {
        linkChanged.removeCallbacks(hideLinkChanged);
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(a);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        Look.panel(a, card, Look.SURFACE, 14);
        return card;
    }

    private static ColorStateList tint(int on, int off) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{on, off});
    }

    private int dp(float v) {
        return Look.dp(a, v);
    }
}
