package com.zoolcoder.tv.exchangerates;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.text.TextUtils;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Sudanese pound against the currencies in config.xml, one tile each: "1 USD = N SDG". It is the
 * official rate of ExchangeRate-API, not the parallel-market rate, and the screen says so in Arabic and
 * English. The rates are read when the app opens and again when it comes back after an hour; the last good
 * answer is kept so the screen still shows something offline. Nothing runs in the background.
 */
public class MainActivity extends Activity {
    private static final int SURFACE = 0xFF16281D, RAISED = 0xFF20382A;
    private static final int DIVIDER = 0xFF294235, MUTED = 0xFF9DB8A8, ACCENT = 0xFF29B6F6;
    private static final int WARNING = 0xFFF2C14E;
    private static final int COLUMNS = 4;
    private static final String URL = "https://open.er-api.com/v6/latest/USD";
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BYTES = 512 * 1024;
    private static final long TOTAL_TIMEOUT_MS = 30000;
    private static final long REFRESH_MS = DateUtils.HOUR_IN_MILLIS;
    private static final int OLD_DAYS = 2;
    private static final String PREFS = "rates", SAVED_BODY = "body";

    private final List<Rates.Currency> currencies = new ArrayList<>();
    private final List<TextView> amounts = new ArrayList<>();
    private TextView sourceEn, sourceAr, status;
    private View firstTile;
    private Rates.Snapshot snapshot;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::load;
    private Rates.Formatter formatter;
    private SimpleDateFormat dateEn, dateAr;
    private int loadId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean arabic = "ar".equals(Locale.getDefault().getLanguage());
        formatter = new Rates.Formatter(Locale.getDefault());
        dateEn = new SimpleDateFormat("d MMM", Locale.ENGLISH);
        dateAr = new SimpleDateFormat("d MMM", new Locale("ar"));
        for (String line : getResources().getStringArray(R.array.currencies)) {
            Rates.Currency currency = Rates.parseCurrency(line);
            if (currency != null) currencies.add(currency);
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setPadding(dp(48), dp(28), dp(48), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        marker.setBackground(rounded(ACCENT, 0, 0, 3));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(6), dp(30));
        markerParams.setMarginEnd(dp(12));
        header.addView(marker, markerParams);
        TextView title = text(getString(R.string.title), 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        status = text(getString(R.string.loading), 16, MUTED);
        status.setGravity(Gravity.END);
        header.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(header);

        // Fixed 204 x 104 dp tiles in rows of four, 12 dp apart: 864 dp, the content width at 1920 x 1080.
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout row = null;
        for (int i = 0; i < currencies.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = dp(12);
                grid.addView(row, rowParams);
            }
            row.addView(tile(currencies.get(i)), tileParams(i % COLUMNS));
        }
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(20);
        page.addView(grid, gridParams);

        sourceEn = text("", 16, MUTED);
        sourceAr = text("", 16, MUTED);
        sourceEn.setTextDirection(View.TEXT_DIRECTION_LTR);
        sourceAr.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        sources.setPadding(0, dp(10), 0, dp(10));
        sources.addView(arabic ? sourceAr : sourceEn);
        sources.addView(arabic ? sourceEn : sourceAr);
        page.addView(sources);

        TextView noteEn = note(getString(R.string.note_en), View.TEXT_DIRECTION_LTR);
        TextView noteAr = note(getString(R.string.note_ar), View.TEXT_DIRECTION_RTL);
        LinearLayout notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.setPadding(dp(16), dp(10), dp(16), dp(10));
        notes.setBackground(rounded(SURFACE, DIVIDER, 1, 14));
        notes.addView(arabic ? noteAr : noteEn);
        notes.addView(arabic ? noteEn : noteAr);
        page.addView(notes);

        TextView attribution = text(getString(R.string.attribution), 16, MUTED);
        attribution.setPadding(0, dp(8), 0, 0);
        page.addView(attribution);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(page);
        setContentView(scroll);

        showRates();
        if (firstTile != null) firstTile.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    // The hourly refresh runs only while the screen is visible.
    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        loadId++;
        super.onDestroy();
    }

    private void load() {
        handler.removeCallbacks(refresh);
        int id = ++loadId;
        boolean needCache = snapshot == null;
        if (snapshot == null) {
            status.setTextColor(MUTED);
            status.setText(R.string.loading);
        }
        new Thread(() -> {
            if (needCache) {
                // The saved answer first, so something shows while the network answers; a corrupt one is ignored.
                Rates.Snapshot saved = Rates.parse(getSharedPreferences(PREFS, MODE_PRIVATE).getString(SAVED_BODY, null));
                if (saved != null) {
                    runOnUiThread(() -> {
                        if (id == loadId && snapshot == null) {
                            snapshot = saved;
                            showRates();
                        }
                    });
                }
            }
            String body = null;
            Rates.Snapshot fresh = null;
            try {
                body = download();
                fresh = Rates.parse(body);
            } catch (Exception ignored) {
                // No network or a bad answer: the saved rates stay on screen.
            }
            Rates.Snapshot result = fresh;
            String good = body;
            runOnUiThread(() -> {
                if (id != loadId || isDestroyed()) return;
                if (result != null) {
                    snapshot = result;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(SAVED_BODY, good).apply();
                    status.setText("");
                } else {
                    status.setTextColor(WARNING);
                    status.setText(snapshot != null ? R.string.offline : R.string.failed);
                }
                showRates();
                handler.postDelayed(refresh, result != null ? REFRESH_MS : REFRESH_MS / 6);
            });
        }, "rates").start();
    }

    private static String download() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            long deadline = SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            try (InputStream in = connection.getInputStream()) {
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    // Too big, or a server that trickles bytes: not a rates answer.
                    if (out.size() > MAX_BYTES || SystemClock.elapsedRealtime() > deadline) {
                        throw new IOException("too big or too slow");
                    }
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private void showRates() {
        for (int i = 0; i < amounts.size(); i++) {
            double sdg = snapshot == null ? Double.NaN : snapshot.sdgPer(currencies.get(i).code);
            amounts.get(i).setText(getString(R.string.unit) + " " + formatter.format(sdg) + " " + getString(R.string.sdg));
        }
        if (snapshot == null) {
            sourceEn.setText("");
            sourceAr.setText("");
            return;
        }
        Date updated = new Date(snapshot.updatedMs);
        sourceEn.setText(getString(R.string.source_en, dateEn.format(updated)));
        sourceAr.setText(getString(R.string.source_ar, dateAr.format(updated)));
        // Saved rates can be days old; say so rather than let them pass for today's. A future time (wrong TV clock) says nothing.
        int days = Rates.ageDays(snapshot.updatedMs, System.currentTimeMillis());
        if (days >= OLD_DAYS && status.getText().length() == 0) {
            status.setTextColor(WARNING);
            status.setText(getString(R.string.old, days));
        }
    }

    private View tile(Rates.Currency currency) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setClipChildren(false);
        tile.setPadding(dp(14), dp(10), dp(14), dp(10));
        // Both backgrounds are built once per tile; focus only swaps them. The white stroke sits outside the accent one.
        Drawable normal = rounded(SURFACE, DIVIDER, 1, 10);
        LayerDrawable focused = new LayerDrawable(new Drawable[] {
                rounded(Color.TRANSPARENT, Color.WHITE, 1, 10), rounded(RAISED, ACCENT, 2, 9)});
        focused.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
        tile.setBackground(normal);
        tile.setFocusable(true);
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(hasFocus ? focused : normal);
            v.setScaleX(hasFocus ? 1.03f : 1f);
            v.setScaleY(hasFocus ? 1.03f : 1f);
        });
        if (firstTile == null) firstTile = tile;

        if (!currency.arabic.isEmpty()) {
            TextView name = text(currency.arabic, 18, Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            tile.addView(name);
        }

        LinearLayout codes = new LinearLayout(this);
        codes.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = text(currency.code, 16, ACCENT);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextDirection(View.TEXT_DIRECTION_LTR);
        chip.setPadding(dp(8), dp(1), dp(8), dp(1));
        chip.setBackground(rounded(Color.TRANSPARENT, ACCENT, 1, 6));
        codes.addView(chip);
        if (!currency.english.isEmpty()) {
            TextView english = text(currency.english, 16, MUTED);
            english.setSingleLine(true);
            english.setEllipsize(TextUtils.TruncateAt.END);
            english.setTextDirection(View.TEXT_DIRECTION_LTR);
            english.setPadding(dp(8), 0, 0, 0);
            codes.addView(english, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams codesParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        codesParams.topMargin = dp(2);
        tile.addView(codes, codesParams);

        TextView amount = text("", 22, ACCENT);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        amount.setSingleLine(true);
        amount.setTextDirection(View.TEXT_DIRECTION_LTR);
        amount.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        // A long value such as "1 = 1,495.32 SDG" shrinks a little rather than being cut.
        amount.setAutoSizeTextTypeUniformWithConfiguration(18, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        amountParams.topMargin = dp(2);
        tile.addView(amount, amountParams);
        amounts.add(amount);
        return tile;
    }

    private LinearLayout.LayoutParams tileParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(204), dp(104));
        if (column > 0) params.setMarginStart(dp(12));
        return params;
    }

    private TextView note(String value, int direction) {
        TextView view = text(value, 16, WARNING);
        view.setTextDirection(direction);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
