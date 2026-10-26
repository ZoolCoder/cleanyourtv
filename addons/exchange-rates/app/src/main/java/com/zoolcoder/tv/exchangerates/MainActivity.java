package com.zoolcoder.tv.exchangerates;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
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
    // Look: design/appletv-spec.md and design/tv-family-spec.md. Focus is a white 2 dp outline, a lighter fill and a small lift.
    private static final int BACKGROUND = 0xFF0B1A12, GLOW = 0x2629B6F6;
    private static final int SURFACE = 0x9916281D, SURFACE_FOCUSED = 0xFF2C4838, FOCUS_STROKE = 0xFFFFFFFF;
    private static final int TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF, ACCENT = 0xFF29B6F6;
    private static final int WARNING = 0xFFF2C94C, WARNING_FILL = 0x26F2C94C, ON_LIGHT = 0xFF0B1A12;
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
    private TextView updated, source, status;
    private View firstTile;
    private Rates.Snapshot snapshot;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::load;
    private Rates.Formatter formatter;
    private SimpleDateFormat dateEn, dateAr;
    private boolean arabic;
    private int loadId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        arabic = "ar".equals(Locale.getDefault().getLanguage());
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
        page.setPadding(dp(48), dp(16), dp(48), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        View marker = new View(this);
        marker.setBackground(rounded(ACCENT, 2));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(4), dp(26));
        markerParams.setMarginEnd(dp(12));
        header.addView(marker, markerParams);
        TextView title = text(getString(R.string.title), 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        // "Updated 26 Oct" at the top end, with the loading / offline / old-rates notice under it when there is one.
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        updated = text("", 15, TEXT_2);
        updated.setGravity(Gravity.END);
        meta.addView(updated);
        status = text(getString(R.string.loading), 15, TEXT_3);
        status.setGravity(Gravity.END);
        meta.addView(status);
        header.addView(meta, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(header);
        page.addView(banner(), bannerParams());

        // Fixed 204 x 120 dp tiles in rows of four, 16 dp apart: 864 dp, the content width at 1920 x 1080.
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
                if (i > 0) rowParams.topMargin = dp(16);
                grid.addView(row, rowParams);
            }
            row.addView(tile(currencies.get(i)), tileParams(i % COLUMNS));
        }
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(24);
        page.addView(grid, gridParams);

        source = text("", 14, TEXT_3);
        source.setPadding(0, dp(16), 0, dp(8));
        page.addView(source);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // Room for the lifted tile and its shadow above the first and below the last row.
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        scroll.setClipToOutline(true);
        scroll.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(page);

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackground(glow(arabic));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

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
            status.setTextColor(TEXT_3);
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
            updated.setText("");
            source.setText("");
            return;
        }
        Date date = new Date(snapshot.updatedMs);
        updated.setText(getString(R.string.updated, dateEn.format(date), dateAr.format(date)));
        source.setText(getString(arabic ? R.string.source_ar : R.string.source_en) + " \u00B7 " + getString(R.string.attribution));
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
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        focusable(tile, 14, 1.04f);
        if (firstTile == null) firstTile = tile;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(disc(currency.code), new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout codes = new LinearLayout(this);
        codes.setOrientation(LinearLayout.VERTICAL);
        codes.setPadding(dp(10), 0, 0, 0);
        TextView code = text(currency.code, 15, ACCENT);
        code.setTypeface(medium());
        code.setSingleLine(true);
        code.setTextDirection(View.TEXT_DIRECTION_LTR);
        code.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        codes.addView(code);
        if (!currency.english.isEmpty()) {
            TextView english = text(currency.english, 14, TEXT_2);
            english.setSingleLine(true);
            english.setEllipsize(TextUtils.TruncateAt.END);
            english.setTextDirection(View.TEXT_DIRECTION_LTR);
            english.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            codes.addView(english);
        }
        top.addView(codes, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tile.addView(top);

        {
            // Always present, even when empty, so every card has the same template and height.
            TextView name = text(currency.arabic.isEmpty() ? " " : currency.arabic, 18, Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = dp(6);
            tile.addView(name, nameParams);
        }

        TextView amount = text("", 19, Color.WHITE);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        amount.setSingleLine(true);
        amount.setTextDirection(View.TEXT_DIRECTION_LTR);
        amount.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        // A long value such as "1 = 1,495.32 SDG" shrinks a little rather than being cut.
        amount.setAutoSizeTextTypeUniformWithConfiguration(15, 19, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        amountParams.topMargin = dp(4);
        tile.addView(amount, amountParams);
        amounts.add(amount);
        return tile;
    }

    // The badge for a currency: a disc in a colour taken from its code, with the code's first two letters.
    private View disc(String code) {
        TextView badge = text(code.length() > 2 ? code.substring(0, 2) : code, 12, Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setTextDirection(View.TEXT_DIRECTION_LTR);
        int hue = Math.abs(code.hashCode()) % 360;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.HSVToColor(new float[] {hue, 0.55f, 0.55f}));
        badge.setBackground(shape);
        return badge;
    }

    private LinearLayout.LayoutParams tileParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(204), dp(120));
        if (column > 0) params.setMarginStart(dp(16));
        return params;
    }

    private TextView note(String value, int direction) {
        TextView view = text(value, 15, WARNING);
        view.setTextDirection(direction);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    // Focus: white outline, lighter fill, a small lift and an 8 dp shadow only while focused.
    // The two fills are built once and swapped by the view's state, never in the focus listener.
    private void focusable(View view, int radiusDp, float scale) {
        GradientDrawable focused = rounded(SURFACE_FOCUSED, radiusDp);
        focused.setStroke(dp(2), FOCUS_STROKE);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, focused);
        background.addState(new int[0], rounded(SURFACE, radiusDp));
        view.setBackground(background);
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        view.setFocusable(true);
        int lift = dp(8);
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.setElevation(hasFocus ? lift : 0);
            v.animate().scaleX(hasFocus ? scale : 1f).scaleY(hasFocus ? scale : 1f).setDuration(150).start();
        });
    }

    // The parallel-market warning, above the grid: a "!" badge, then the English and Arabic lines.
    private View banner() {
        TextView noteEn = note(getString(R.string.note_en), View.TEXT_DIRECTION_LTR);
        TextView noteAr = note(getString(R.string.note_ar), View.TEXT_DIRECTION_RTL);
        LinearLayout notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notes.addView(arabic ? noteAr : noteEn);
        notes.addView(arabic ? noteEn : noteAr);
        LinearLayout banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        banner.setBackground(rounded(WARNING_FILL, 14));
        TextView icon = text("!", 16, ON_LIGHT);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(WARNING);
        icon.setBackground(disc);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.setMarginEnd(dp(12));
        banner.addView(icon, iconParams);
        banner.addView(notes, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return banner;
    }

    private LinearLayout.LayoutParams bannerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        return params;
    }

    // The window base with the accent glow in the top-start corner, about 70 % of the width wide.
    private Drawable glow(boolean arabic) {
        GradientDrawable light = new GradientDrawable();
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        light.setColors(new int[] {GLOW, 0x0029B6F6});
        light.setGradientRadius(dp(672));
        light.setGradientCenter(arabic ? 1f : 0f, 0f);
        return new LayerDrawable(new Drawable[] {new ColorDrawable(BACKGROUND), light});
    }

    private static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
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
