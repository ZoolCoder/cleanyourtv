package com.zoolcoder.tv.sudannews;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The latest Sudan headlines from the feeds in config.xml, in an Arabic and an English tab. OK on a
 * headline moves focus to its story in the panel beside the list; the article itself stays on the
 * publisher's site. The feeds are read when the app opens, and again when it comes back after a quarter of
 * an hour; nothing runs in the background.
 */
public class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFF0B1A12, CARD = 0xFF16281D, RAISED = 0xFF20382A, DIVIDER = 0xFF294235,
            ACCENT = 0xFFD9364F, MUTED = 0xFF9DB8A8;
    private static final float FOCUS_SCALE = 1.03f;
    private static final int MAX_SHOWN = 60;
    private static final long STALE_MS = 15 * DateUtils.MINUTE_IN_MILLIS;

    private final List<Feed.Story> stories = new ArrayList<>();
    private final TextView[] tabs = new TextView[2];
    private TextView status;
    private LinearLayout list;
    private ScrollView listScroll, detail;
    private LinearLayout body;
    private View focusedRow;
    private String language;
    private long loadedAt;
    private int loadId;
    private boolean loading;
    private Thread loader;
    private Drawable.ConstantState rowBack;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        language = "ar".equals(Locale.getDefault().getLanguage()) ? "ar" : "en";

        boolean rtl = "ar".equals(language);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // The window background (styles.xml) is already this colour.
        page.setLayoutDirection(direction(rtl));
        page.setPadding(dp(48), dp(28), dp(48), dp(28));

        // Header: accent marker, title and update line on the start side, the language tabs on the other.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        marker.setBackground(shape(ACCENT, 3, ACCENT, 0));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(6), dp(48));
        markerParams.setMarginEnd(dp(14));
        header.addView(marker, markerParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = directed(text(getString(R.string.title), 28, Color.WHITE), rtl);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(title);
        status = directed(text(getString(R.string.loading), 16, MUTED), rtl);
        heading.addView(status);
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout tabRow = new LinearLayout(this);
        tabs[0] = tab(getString(R.string.tab_ar), "ar");
        tabs[1] = tab(getString(R.string.tab_en), "en");
        tabRow.addView(tabs[0]);
        tabRow.addView(tabs[1]);
        header.addView(tabRow);
        page.addView(header);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll = new ScrollView(this);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        listScroll.addView(list);

        // The panel is focusable so a long summary scrolls; Back from it returns to the row.
        detail = new ScrollView(this);
        detail.setFocusable(true);
        detail.setBackground(focusBack(RAISED, RAISED, 14));

        // Headlines 488 dp and story 352 dp with a 24 dp gap: the 864 dp of a 960 dp screen.
        body = new LinearLayout(this);
        body.addView(listScroll, new LinearLayout.LayoutParams(dp(488), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(dp(352),
                LinearLayout.LayoutParams.MATCH_PARENT);
        detailParams.setMarginStart(dp(24));
        detailParams.setMargins(detailParams.leftMargin, dp(8), detailParams.rightMargin, dp(8));
        body.addView(detail, detailParams);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = dp(12);
        page.addView(body, bodyParams);
        TextView hint = directed(text(getString(R.string.hint), 16, MUTED), rtl);
        hint.setPadding(0, dp(8), 0, 0);
        page.addView(hint);
        setContentView(page);
        showTabs();
        tabs["ar".equals(language) ? 0 : 1].requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!loading && (loadedAt == 0 || SystemClock.elapsedRealtime() - loadedAt > STALE_MS)) load();
    }

    @Override
    protected void onDestroy() {
        if (loader != null) loader.interrupt();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (detail.hasFocus() && focusedRow != null) {
            focusedRow.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    private void load() {
        int id = ++loadId;
        loading = true;
        status.setText(R.string.loading);
        List<Feed> feeds = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.feeds)) {
            Feed feed = Feed.parse(line);
            if (feed != null) feeds.add(feed);
        }
        loader = new Thread(() -> {
            List<Feed.Story> found = new ArrayList<>();
            int loaded = 0;
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(4, feeds.size())));
            try {
                List<Callable<List<Feed.Story>>> jobs = new ArrayList<>();
                for (Feed feed : feeds) jobs.add(feed::fetch);
                for (Future<List<Feed.Story>> result : pool.invokeAll(jobs, 35, TimeUnit.SECONDS)) {
                    try {
                        found.addAll(result.get());
                        loaded++;
                    } catch (Exception ignored) {
                        // One feed down leaves the others on screen.
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
            }
            Collections.sort(found, Comparator.comparingLong((Feed.Story s) -> s.time).reversed());
            boolean ok = loaded > 0;
            runOnUiThread(() -> {
                if (id != loadId || isDestroyed()) return;
                loading = false;
                if (ok) {
                    stories.clear();
                    stories.addAll(found);
                    loadedAt = SystemClock.elapsedRealtime();
                    status.setText(getString(R.string.updated,
                            DateFormat.getTimeFormat(this).format(System.currentTimeMillis())));
                } else {
                    status.setText(R.string.failed);
                }
                showStories(false);
            });
        });
        loader.start();
    }

    private TextView tab(String label, String code) {
        TextView tab = text(label, 18, Color.WHITE);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setMinHeight(dp(48));
        tab.setPadding(dp(24), dp(8), dp(24), dp(8));
        tab.setBackground(states(shape(CARD, 24, DIVIDER, 1), shape(ACCENT, 24, ACCENT, 0),
                ring(RAISED, ACCENT, 24), ring(ACCENT, BACKGROUND, 24)));
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setOnFocusChangeListener((v, focused) -> scale(v, focused));
        tab.setOnClickListener(v -> {
            if (code.equals(language)) return;
            language = code;
            showTabs();
            showStories(true);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(12));
        tab.setLayoutParams(params);
        return tab;
    }

    /** The chosen tab is filled with the accent; the focused one has the ring. */
    private void showTabs() {
        for (TextView tab : tabs) {
            if (tab != null) tab.setSelected(tab == tabs["ar".equals(language) ? 0 : 1]);
        }
    }

    private void showStories(boolean keepTabFocus) {
        focusedRow = null;
        list.removeAllViews();
        boolean rtl = "ar".equals(language);
        body.setLayoutDirection(direction(rtl));
        long now = System.currentTimeMillis();
        Feed.Story first = null;
        for (Feed.Story story : stories) {
            if (!story.language.equals(language)) continue;
            if (list.getChildCount() == MAX_SHOWN) break;
            if (first == null) first = story;
            list.addView(row(story, rtl, now));
        }
        if (list.getChildCount() == 0 && loadedAt != 0) {
            list.addView(directed(text(getString(R.string.empty), 18, MUTED), rtl));
        }
        listScroll.scrollTo(0, 0);
        showDetail(first, rtl);
        View firstRow = list.getChildAt(0);
        if (!keepTabFocus && firstRow != null && firstRow.isFocusable()) firstRow.requestFocus();
    }

    private View row(Feed.Story story, boolean rtl, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutDirection(direction(rtl));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (rowBack == null) rowBack = focusBack(CARD, RAISED, 10).getConstantState();
        row.setBackground(rowBack.newDrawable());
        row.setFocusable(true);
        row.setClickable(true);

        row.addView(directed(text(byline(story, now), 16, MUTED), rtl));
        TextView title = directed(text(story.title, 20, Color.WHITE), rtl);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (rtl) title.setLineSpacing(0, 1.15f);
        row.addView(title);

        // The panel beside the list follows the focused row.
        row.setOnFocusChangeListener((v, focused) -> {
            scale(v, focused);
            if (!focused) return;
            focusedRow = v;
            showDetail(story, rtl);
        });
        row.setOnClickListener(v -> {
            focusedRow = v;
            detail.requestFocus();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        row.setLayoutParams(params);
        return row;
    }

    /** The story in the panel beside the list; an empty panel cannot take focus. */
    private void showDetail(Feed.Story story, boolean rtl) {
        detail.removeAllViews();
        detail.setFocusable(story != null);
        if (story == null) return;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(direction(rtl));
        page.setPadding(dp(20), dp(20), dp(20), dp(20));
        View rule = new View(this);
        rule.setBackground(shape(ACCENT, 2, ACCENT, 0));
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        ruleParams.bottomMargin = dp(12);
        page.addView(rule, ruleParams);
        page.addView(directed(text(byline(story, System.currentTimeMillis()), 16, MUTED), rtl));
        TextView title = directed(text(story.title, 30, Color.WHITE), rtl);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(8), 0, dp(16));
        if (rtl) title.setLineSpacing(0, 1.15f);
        page.addView(title);
        if (!story.summary.isEmpty()) {
            TextView summary = directed(text(story.summary, 21, Color.WHITE), rtl);
            summary.setLineSpacing(0, 1.25f);
            page.addView(summary);
        }
        TextView back = directed(text(getString(R.string.back), 16, MUTED), rtl);
        back.setPadding(0, dp(20), 0, 0);
        page.addView(back);
        detail.addView(page);
        detail.scrollTo(0, 0);
    }

    /** "Source · 2 hours ago", in the TV's language. */
    private static String byline(Feed.Story story, long now) {
        if (story.time == 0) return story.source;
        return story.source + " · " + DateUtils.getRelativeTimeSpanString(story.time, now, DateUtils.MINUTE_IN_MILLIS);
    }

    private static TextView directed(TextView view, boolean rtl) {
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }

    private static int direction(boolean rtl) {
        return rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    /** The focus look is a static scale, not an animation. */
    private static void scale(View view, boolean focused) {
        float scale = focused ? FOCUS_SCALE : 1f;
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    private Drawable shape(int fill, int radius, int stroke, int strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    /** A white 1 dp outer stroke around a fill with a 2 dp stroke inside it. */
    private Drawable ring(int fill, int stroke, int radius) {
        LayerDrawable ring = new LayerDrawable(new Drawable[] {
                shape(Color.WHITE, radius, Color.WHITE, 0), shape(fill, radius - 1, stroke, 2)});
        ring.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
        return ring;
    }

    private static StateListDrawable states(Drawable normal, Drawable selected, Drawable focused,
            Drawable focusedSelected) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused, android.R.attr.state_selected}, focusedSelected);
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {android.R.attr.state_selected}, selected);
        states.addState(new int[0], normal);
        return states;
    }

    /** A card that turns raised with the accent ring when focused. Built once per view, not per focus change. */
    private Drawable focusBack(int fill, int focusedFill, int radius) {
        Drawable normal = shape(fill, radius, DIVIDER, 1);
        Drawable focused = ring(focusedFill, ACCENT, radius);
        return states(normal, normal, focused, focused);
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
