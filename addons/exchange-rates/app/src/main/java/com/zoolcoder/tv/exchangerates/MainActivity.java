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
import android.view.ViewGroup;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Sudanese pound against the currencies in config.xml, one tile each: "1 USD = N SDG". It is the
 * official rate of ExchangeRate-API, not the parallel-market rate, and the screen says so in Arabic and
 * English. The rates are read when the app opens and again when it comes back after an hour; the last good
 * answer is kept so the screen still shows something offline, and the one before it for the change arrows.
 * OK on a tile opens its large view, where the currency can be pinned to the front. Nothing runs in the background.
 */
public class MainActivity extends Activity {
    // Look: design/appletv-spec.md and design/tv-family-spec.md. Focus is a white 2 dp outline, a lighter fill and a small lift.
    private static final int BACKGROUND = 0xFF0B1A12, GLOW = 0x2629B6F6;
    private static final int SURFACE = 0x9916281D, SURFACE_FOCUSED = 0xFF2C4838, FOCUS_STROKE = 0xFFFFFFFF;
    private static final int TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF, ACCENT = 0xFF29B6F6;
    private static final int WARNING = 0xFFF2C94C, WARNING_FILL = 0x26F2C94C, ON_LIGHT = 0xFF0B1A12;
    private static final int CHIP = 0x29FFFFFF;
    private static final int COLUMNS = 4;
    private static final String URL = "https://open.er-api.com/v6/latest/USD";
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BYTES = 512 * 1024;
    private static final long TOTAL_TIMEOUT_MS = 30000;
    private static final long REFRESH_MS = DateUtils.HOUR_IN_MILLIS;
    private static final int OLD_DAYS = 2;
    // The newest good answer, the one before it (an older update of the source) and the pinned codes.
    private static final String PREFS = "rates", SAVED_BODY = "body", PREVIOUS_BODY = "previous", PINNED = "pinned";

    /** The views of one currency's tile, kept so a refresh or a new order only changes text and position. */
    private static final class Tile {
        View view;
        TextView amount, change, star;
    }

    private final List<Rates.Currency> currencies = new ArrayList<>();
    private final Map<String, Tile> tiles = new HashMap<>();
    private final Set<String> pinned = new HashSet<>();
    private TextView updated, source, status, hint;
    private View retry;
    private LinearLayout grid;
    private ScrollView scroll;
    private Detail detail;
    private Rates.Snapshot snapshot, previous;
    private boolean loading;
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
        dateEn = new SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH);
        dateAr = new SimpleDateFormat("d MMM، HH:mm", new Locale("ar"));
        for (String line : getResources().getStringArray(R.array.currencies)) {
            Rates.Currency currency = Rates.parseCurrency(line);
            if (currency != null) currencies.add(currency);
        }
        pinned.addAll(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(PINNED, new HashSet<>()));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        // The lifted first and last tiles draw into the 48 dp margin; clipping there cut their outline.
        page.setClipToPadding(false);
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
        // "Updated 26 Oct, 00:02" at the top end, with the loading / offline / old-rates notice under it when there
        // is one, and a Try again pill beside them after a failed refresh.
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        updated = text("", 15, TEXT_2);
        updated.setGravity(Gravity.END);
        meta.addView(updated);
        status = text(getString(R.string.loading), 15, TEXT_3);
        status.setGravity(Gravity.END);
        meta.addView(status);
        header.addView(meta, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        retry = pill(getString(R.string.retry));
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            if (!loading) load();
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        retryParams.setMarginStart(dp(16));
        header.addView(retry, retryParams);
        page.addView(header);
        page.addView(banner(), bannerParams());

        // Fixed 204 x 120 dp tiles in rows of four, 16 dp apart: 864 dp, the content width at 1920 x 1080.
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        for (Rates.Currency currency : currencies) tiles.put(currency.code, tile(currency));
        layoutGrid();
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(24);
        page.addView(grid, gridParams);

        // The remote hints at the bottom start, the source at the bottom end.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(16), 0, dp(8));
        hint = text(getString(R.string.hint_details), 14, TEXT_2);
        bottom.addView(hints(hint, getString(R.string.hint_home)));
        source = text("", 14, TEXT_3);
        source.setGravity(Gravity.END);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sourceParams.setMarginStart(dp(24));
        bottom.addView(source, sourceParams);
        page.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
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
        detail = new Detail();
        root.addView(detail.view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        showRates();
        View first = firstTile();
        if (first != null) first.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (detail.isOpen()) detail.close();
        else super.onBackPressed();
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
        loading = true;
        status.setTextColor(TEXT_3);
        status.setText(R.string.loading);
        new Thread(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String savedBody = prefs.getString(SAVED_BODY, null);
            Rates.Snapshot saved = Rates.parse(savedBody);
            Rates.Snapshot before = Rates.parse(prefs.getString(PREVIOUS_BODY, null));
            if (needCache && saved != null) {
                // The saved answer first, so something shows while the network answers; a corrupt one is ignored.
                runOnUiThread(() -> {
                    if (id == loadId && snapshot == null) {
                        snapshot = saved;
                        previous = Rates.isNewer(before, saved) ? before : null;
                        showRates();
                    }
                });
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
                loading = false;
                if (result != null) {
                    SharedPreferences.Editor editor = prefs.edit().putString(SAVED_BODY, good);
                    // A newer update of the source: the saved one becomes the previous update for the arrows.
                    if (Rates.isNewer(saved, result)) {
                        editor.putString(PREVIOUS_BODY, savedBody);
                        previous = saved;
                    } else {
                        previous = Rates.isNewer(before, result) ? before : null;
                    }
                    editor.apply();
                    snapshot = result;
                    status.setText("");
                    if (retry.hasFocus()) {
                        View first = firstTile();
                        if (first != null) first.requestFocus();
                    }
                    retry.setVisibility(View.GONE);
                } else {
                    status.setTextColor(WARNING);
                    if (snapshot != null) {
                        Date date = new Date(snapshot.updatedMs);
                        status.setText(getString(R.string.offline, dateEn.format(date), dateAr.format(date)));
                    } else {
                        status.setText(R.string.failed);
                    }
                    retry.setVisibility(View.VISIBLE);
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
        for (Rates.Currency currency : currencies) {
            Tile tile = tiles.get(currency.code);
            double sdg = snapshot == null ? Double.NaN : snapshot.sdgPer(currency.code);
            // A missing rate is a dash, never "1 = 0 SDG".
            tile.amount.setText(Double.isNaN(sdg) ? "—" : rateLine(getString(R.string.unit), formatter.format(sdg)));
            String change = formatter.change(Rates.change(snapshot, previous, currency.code), sdg);
            tile.change.setText(change);
            tile.change.setVisibility(change.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (detail != null && detail.isOpen()) detail.show();
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
        if (days >= OLD_DAYS && status.getText().length() == 0 && !loading) {
            status.setTextColor(WARNING);
            status.setText(getString(R.string.old, days));
        }
    }

    private String rateLine(String unit, String amount) {
        return unit + " " + amount + " " + getString(R.string.sdg);
    }

    // Pinned currencies first, then the rest; the same tile views move, nothing is rebuilt.
    private void layoutGrid() {
        for (int i = 0; i < grid.getChildCount(); i++) ((ViewGroup) grid.getChildAt(i)).removeAllViews();
        grid.removeAllViews();
        List<Rates.Currency> order = Rates.ordered(currencies, pinned);
        LinearLayout row = null;
        for (int i = 0; i < order.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = dp(16);
                grid.addView(row, rowParams);
            }
            row.addView(tiles.get(order.get(i).code).view, tileParams(i % COLUMNS));
        }
    }

    private View firstTile() {
        List<Rates.Currency> order = Rates.ordered(currencies, pinned);
        return order.isEmpty() ? null : tiles.get(order.get(0).code).view;
    }

    private void togglePin(Rates.Currency currency) {
        if (!pinned.remove(currency.code)) pinned.add(currency.code);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(PINNED, new HashSet<>(pinned)).apply();
        tiles.get(currency.code).star.setVisibility(pinned.contains(currency.code) ? View.VISIBLE : View.INVISIBLE);
        layoutGrid();
    }

    private Tile tile(Rates.Currency currency) {
        Tile holder = new Tile();
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setClipChildren(false);
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        focusable(tile, 14, 1.04f);
        tile.setOnClickListener(v -> detail.open(currency));
        View.OnFocusChangeListener lift = tile.getOnFocusChangeListener();
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            lift.onFocusChange(v, hasFocus);
            if (hasFocus) hint.setText(R.string.hint_details);
        });
        holder.view = tile;

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
        // The change since the previous update: an arrow and the number in TEXT_2, never green or red,
        // because a dearer dollar is good news for some families and bad for others.
        holder.change = text("", 13, TEXT_2);
        holder.change.setFontFeatureSettings("tnum");
        holder.change.setTextDirection(View.TEXT_DIRECTION_LTR);
        holder.change.setVisibility(View.GONE);
        top.addView(holder.change);
        tile.addView(top);

        {
            // Always present, even when empty, so every card has the same template and height.
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(currency.arabic.isEmpty() ? " " : currency.arabic, 18, Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            holder.star = text("\u2605", 16, TEXT_2);
            holder.star.setVisibility(pinned.contains(currency.code) ? View.VISIBLE : View.INVISIBLE);
            line.addView(holder.star);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = dp(6);
            tile.addView(line, nameParams);
        }

        TextView amount = text("", 21, Color.WHITE);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        amount.setFontFeatureSettings("tnum");
        amount.setSingleLine(true);
        amount.setTextDirection(View.TEXT_DIRECTION_LTR);
        amount.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        // A long value such as "1 = 1,495.32 SDG" shrinks a little rather than being cut.
        amount.setAutoSizeTextTypeUniformWithConfiguration(15, 21, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        amountParams.topMargin = dp(4);
        tile.addView(amount, amountParams);
        holder.amount = amount;
        return holder;
    }

    // The badge for a currency: a disc in a colour taken from its code, with the code's first two letters.
    private View disc(String code) {
        return disc(code, 12);
    }

    private View disc(String code, int sp) {
        TextView badge = text(code.length() > 2 ? code.substring(0, 2) : code, sp, Color.WHITE);
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

    // A focusable pill button: Try again, Pin.
    private TextView pill(String label) {
        TextView pill = text(label, 16, Color.WHITE);
        pill.setTypeface(medium());
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(20), 0, dp(20), 0);
        pill.setClickable(true);
        focusable(pill, 20, 1.04f);
        View.OnFocusChangeListener lift = pill.getOnFocusChangeListener();
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            lift.onFocusChange(v, hasFocus);
            if (hasFocus && hint != null && v == retry) hint.setText(R.string.hint_retry);
        });
        return pill;
    }

    // The remote hint bar: [OK] label · [Back] label. The OK label is the view passed in, so it can change.
    private View hints(TextView okLabel, String backLabel) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(chip(getString(R.string.key_ok)));
        okLabel.setPadding(dp(8), 0, dp(16), 0);
        bar.addView(okLabel);
        bar.addView(chip(getString(R.string.key_back)));
        TextView back = text(backLabel, 14, TEXT_2);
        back.setPadding(dp(8), 0, 0, 0);
        bar.addView(back);
        return bar;
    }

    private View chip(String key) {
        TextView chip = text(key, 12, Color.WHITE);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextDirection(View.TEXT_DIRECTION_LTR);
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        chip.setBackground(rounded(CHIP, 10));
        return chip;
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

    /**
     * The large view of one currency, over the grid: code and both names, "1 USD = 458.6 SDG", the inverse,
     * the change since the previous update, source and time, and the Pin button. Back returns to its tile.
     */
    private final class Detail {
        final FrameLayout view = new FrameLayout(MainActivity.this);
        private final FrameLayout badge = new FrameLayout(MainActivity.this);
        private final TextView code = text("", 34, ACCENT), english = text("", 20, TEXT_2), arabicName = text("", 26, Color.WHITE);
        private final TextView rate = text("", 56, Color.WHITE), inverse = text("", 24, TEXT_2);
        private final TextView change = text("", 18, TEXT_2), meta = text("", 15, TEXT_3);
        private final TextView pin = pill("");
        private Rates.Currency currency;

        Detail() {
            view.setVisibility(View.GONE);
            view.setClickable(true);
            view.setBackground(glow(arabic));
            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            content.setPadding(dp(64), dp(32), dp(64), dp(32));

            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(badge, new LinearLayout.LayoutParams(dp(64), dp(64)));
            LinearLayout names = new LinearLayout(MainActivity.this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(16), 0, 0, 0);
            LinearLayout codeLine = new LinearLayout(MainActivity.this);
            codeLine.setGravity(Gravity.BOTTOM);
            code.setTypeface(Typeface.DEFAULT_BOLD);
            code.setTextDirection(View.TEXT_DIRECTION_LTR);
            codeLine.addView(code);
            english.setTextDirection(View.TEXT_DIRECTION_LTR);
            english.setPadding(dp(12), 0, dp(12), dp(4));
            codeLine.addView(english);
            names.addView(codeLine);
            // Under the code, not right-aligned under the English name.
            arabicName.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            names.addView(arabicName);
            top.addView(names);
            content.addView(top);

            rate.setTypeface(Typeface.DEFAULT_BOLD);
            rate.setFontFeatureSettings("tnum");
            rate.setTextDirection(View.TEXT_DIRECTION_LTR);
            rate.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            rate.setPadding(0, dp(24), 0, 0);
            content.addView(rate);
            inverse.setFontFeatureSettings("tnum");
            inverse.setTextDirection(View.TEXT_DIRECTION_LTR);
            inverse.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            content.addView(inverse);
            change.setFontFeatureSettings("tnum");
            change.setPadding(0, dp(16), 0, 0);
            content.addView(change);
            meta.setPadding(0, dp(8), 0, 0);
            content.addView(meta);

            pin.setOnClickListener(v -> {
                togglePin(currency);
                show();
            });
            LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            pinParams.topMargin = dp(32);
            content.addView(pin, pinParams);
            view.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            View bar = hints(text(getString(R.string.hint_pin), 14, TEXT_2), getString(R.string.hint_rates));
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            barParams.setMarginStart(dp(64));
            barParams.bottomMargin = dp(24);
            view.addView(bar, barParams);
        }

        boolean isOpen() {
            return view.getVisibility() == View.VISIBLE;
        }

        void open(Rates.Currency chosen) {
            if (currency == null || !currency.code.equals(chosen.code)) {
                badge.removeAllViews();
                badge.addView(disc(chosen.code, 22), new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
            currency = chosen;
            show();
            // The grid underneath must not take focus while this covers it.
            scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            view.setVisibility(View.VISIBLE);
            pin.requestFocus();
        }

        void close() {
            view.setVisibility(View.GONE);
            scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            tiles.get(currency.code).view.requestFocus();
        }

        void show() {
            code.setText(currency.code);
            english.setText(currency.english);
            arabicName.setText(currency.arabic);
            double sdg = snapshot == null ? Double.NaN : snapshot.sdgPer(currency.code);
            rate.setText(Double.isNaN(sdg) ? "—" : rateLine("1 " + currency.code + " =", formatter.format(sdg)));
            inverse.setText(Double.isNaN(sdg) ? ""
                    : "1 " + getString(R.string.sdg) + " = " + formatter.inverse(sdg) + " " + currency.code);
            double diff = Rates.change(snapshot, previous, currency.code);
            if (Double.isNaN(diff) || Double.isNaN(sdg)) {
                change.setVisibility(View.GONE);
            } else {
                String shown = formatter.change(diff, sdg);
                String when = dateEn.format(new Date(previous.updatedMs));
                change.setText(shown.isEmpty() ? getString(R.string.no_change, when) : getString(R.string.change, shown, when));
                change.setVisibility(View.VISIBLE);
            }
            if (snapshot == null) {
                meta.setText(arabic ? R.string.source_ar : R.string.source_en);
            } else {
                Date date = new Date(snapshot.updatedMs);
                meta.setText(getString(arabic ? R.string.source_ar : R.string.source_en) + " \u00B7 "
                        + getString(R.string.updated, dateEn.format(date), dateAr.format(date)));
            }
            pin.setText(pinned.contains(currency.code) ? R.string.unpin : R.string.pin);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
