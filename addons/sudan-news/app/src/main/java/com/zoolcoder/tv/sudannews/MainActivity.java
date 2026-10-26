package com.zoolcoder.tv.sudannews;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.view.ViewOutlineProvider;
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
    // The tvOS look of design/appletv-spec.md.
    private static final int BACKGROUND = 0xFF0B1A12, SURFACE = 0x9916281D, SURFACE_FOCUSED = 0xFF2C4838,
            TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF, PILL = 0x29FFFFFF, TRACK = 0x1FFFFFFF, SHEEN = 0x2EFFFFFF,
            ON_LIGHT = 0xFF0B1A12, ACCENT = 0xFFD9364F, GLOW = 0x26D9364F, SCRIM = 0xCC0B1A12;
    private static final float ROW_SCALE = 1.02f, TAB_SCALE = 1.04f;
    // What the remote hint bar describes: where focus is.
    private static final int HINT_TABS = 0, HINT_ROW = 1, HINT_DETAIL = 2;
    private static final int MAX_SHOWN = 60;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final long STALE_MS = 15 * DateUtils.MINUTE_IN_MILLIS;

    private final List<Feed.Story> stories = new ArrayList<>();
    private final TextView[] tabs = new TextView[2];
    private TextView status;
    private LinearLayout list;
    private ScrollView listScroll, detail;
    private TextView moreCue;
    private LinearLayout hintBar;
    private int hintKind = -1;
    private boolean rtlPage;
    private LinearLayout body;
    private View focusedRow, shownRow;
    private String language;
    private long loadedAt;
    private int loadId;
    private boolean loading;
    private Thread loader;
    private Drawable.ConstantState rowBack;
    private Drawable panelFocus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        language = "ar".equals(Locale.getDefault().getLanguage()) ? "ar" : "en";

        boolean rtl = "ar".equals(language);
        panelFocus = focusedFill(18);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(direction(rtl));
        page.setBackground(glow(rtl));
        page.setClipChildren(false);
        page.setPadding(dp(48), dp(28), dp(48), dp(24));

        // Header: accent marker, title and update line on the start side, the language tabs on the other.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        View marker = new View(this);
        marker.setBackground(shape(ACCENT, 2));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(4), dp(26));
        markerParams.setMarginEnd(dp(14));
        markerParams.gravity = Gravity.TOP;
        markerParams.topMargin = dp(8);
        header.addView(marker, markerParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = directed(text(getString(R.string.title), 30, Color.WHITE), rtl);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(title);
        status = directed(text(getString(R.string.loading), 14, TEXT_3), rtl);
        heading.addView(status);
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setClipChildren(false);
        tabRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabRow.setBackground(shape(TRACK, 26));
        tabs[0] = tab(getString(R.string.tab_ar), "ar");
        tabs[1] = tab(getString(R.string.tab_en), "en");
        tabRow.addView(tabs[0]);
        tabRow.addView(tabs[1]);
        header.addView(tabRow);
        page.addView(header);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        // The scroll view still cuts at its own edges: the scaled row and its shadow live in this padding.
        listScroll = new ScrollView(this);
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        listScroll.setClipChildren(false);
        listScroll.setClipToPadding(false);
        listScroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        listScroll.setClipToOutline(true);
        listScroll.setPadding(dp(16), dp(16), dp(16), dp(16));
        listScroll.addView(list);

        // The panel is focusable so a long summary scrolls; Back from it returns to the row.
        detail = new ScrollView(this);
        detail.setFocusable(true);
        detail.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        detail.setClipToOutline(true);
        detail.setOnFocusChangeListener((v, focused) -> v.setBackground(focused ? panelFocus : null));
        detail.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateMoreCue());
        // A ⌄ over the bottom edge while more of the story is below.
        moreCue = text("\u2304", 22, TEXT_2);
        moreCue.setGravity(Gravity.CENTER);
        moreCue.setPadding(dp(16), 0, dp(16), 0);
        moreCue.setBackground(shape(SCRIM, 14));
        moreCue.setVisibility(View.GONE);
        FrameLayout detailFrame = new FrameLayout(this);
        detailFrame.setClipChildren(false);
        detailFrame.addView(detail, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams cueParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cueParams.bottomMargin = dp(8);
        detailFrame.addView(moreCue, cueParams);

        // Headlines 488 dp and story 352 dp with a 24 dp gap: the 864 dp of a 960 dp screen. The scroll
        // view's 16 dp padding is taken back with negative margins so the rows line up with the title.
        body = new LinearLayout(this);
        body.setClipChildren(false);
        body.addView(listScroll, listParams());
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(dp(352),
                LinearLayout.LayoutParams.MATCH_PARENT);
        detailParams.setMarginStart(dp(8));
        body.addView(detailFrame, detailParams);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = dp(8);
        page.addView(body, bodyParams);
        hintBar = new LinearLayout(this);
        hintBar.setGravity(Gravity.CENTER_VERTICAL);
        hintBar.setLayoutDirection(direction(rtl));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        page.addView(hintBar, hintParams);
        setContentView(page);
        rtlPage = rtl;
        page.getViewTreeObserver().addOnGlobalFocusChangeListener((oldView, newView) -> showHint(newView));
        showTabs();
        tabs["ar".equals(language) ? 0 : 1].requestFocus();
    }

    private LinearLayout.LayoutParams listParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(520),
                LinearLayout.LayoutParams.MATCH_PARENT);
        params.setMarginStart(-dp(16));
        params.setMarginEnd(-dp(16));
        return params;
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
        TextView tab = text(label, 18, TEXT_2);
        tab.setTypeface(MEDIUM);
        tab.setGravity(Gravity.CENTER);
        tab.setMinHeight(dp(44));
        tab.setPadding(dp(20), 0, dp(20), 0);
        // Selected: white. Focused only: a light pill with the white outline. Both: white.
        tab.setBackground(states(shape(Color.TRANSPARENT, 22), shape(Color.WHITE, 22), outlined(PILL, 22),
                outlined(Color.WHITE, 22)));
        tab.setTextColor(new ColorStateList(new int[][] {
                {android.R.attr.state_selected}, {android.R.attr.state_focused}, {}},
                new int[] {ON_LIGHT, Color.WHITE, TEXT_2}));
        tab.setFocusable(true);
        tab.setClickable(true);
        lift(tab, TAB_SCALE, null);
        tab.setOnClickListener(v -> {
            if (code.equals(language)) return;
            language = code;
            showTabs();
            showStories(true);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (code.equals("en")) params.setMarginStart(dp(8));
        tab.setLayoutParams(params);
        return tab;
    }

    /** The chosen tab is the white one; the focused one lifts. */
    private void showTabs() {
        for (TextView tab : tabs) {
            if (tab != null) tab.setSelected(tab == tabs["ar".equals(language) ? 0 : 1]);
        }
    }

    private void showStories(boolean keepTabFocus) {
        focusedRow = null;
        shownRow = null;
        list.removeAllViews();
        boolean rtl = "ar".equals(language);
        body.setLayoutDirection(direction(rtl));
        hintBar.setLayoutDirection(direction(rtl));
        rtlPage = rtl;
        hintKind = -1;
        long now = System.currentTimeMillis();
        Feed.Story first = null;
        for (Feed.Story story : stories) {
            if (!story.language.equals(language)) continue;
            if (list.getChildCount() == MAX_SHOWN) break;
            if (first == null) first = story;
            list.addView(row(story, rtl, now));
        }
        if (list.getChildCount() == 0 && loadedAt != 0) {
            list.addView(directed(text(getString(R.string.empty), 18, TEXT_2), rtl));
        }
        listScroll.scrollTo(0, 0);
        showDetail(first, rtl);
        View firstRow = list.getChildAt(0);
        if (!keepTabFocus && firstRow != null && firstRow.isFocusable()) firstRow.requestFocus();
    }

    private View row(Feed.Story story, boolean rtl, long now) {
        FrameLayout row = new FrameLayout(this);
        row.setLayoutDirection(direction(rtl));
        row.setClipChildren(false);
        if (rowBack == null) rowBack = focusBack(14).getConstantState();
        row.setBackground(rowBack.newDrawable());
        row.setFocusable(true);
        row.setClickable(true);

        // The story open in the panel keeps an accent bar on the start edge, focused or not.
        View bar = new View(this);
        bar.setBackground(states(shape(Color.TRANSPARENT, 2), shape(ACCENT, 2), shape(Color.TRANSPARENT, 2),
                shape(ACCENT, 2)));
        bar.setDuplicateParentStateEnabled(true);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(dp(3), dp(40),
                Gravity.START | Gravity.CENTER_VERTICAL);
        barParams.setMarginStart(dp(6));
        row.addView(bar, barParams);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setClipChildren(false);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView meta = directed(text(byline(story, now), 15, TEXT_3), rtl);
        meta.setPadding(0, 0, 0, dp(4));
        texts.addView(meta);
        TextView title = directed(text(story.title, 19, Color.WHITE), rtl);
        title.setTypeface(MEDIUM);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (rtl) title.setLineSpacing(0, 1.25f);
        texts.addView(title);
        content.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = text(rtl ? "\u2039" : "\u203A", 28, TEXT_3);
        chevron.setPadding(dp(12), 0, 0, 0);
        content.addView(chevron);
        row.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // The panel beside the list follows the focused row.
        lift(row, ROW_SCALE, (v, focused) -> {
            if (!focused) return;
            focusedRow = v;
            if (shownRow != null) shownRow.setSelected(false);
            shownRow = v;
            v.setSelected(true);
            showDetail(story, rtl);
        });
        row.setOnClickListener(v -> {
            focusedRow = v;
            detail.requestFocus();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
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
        page.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = directed(text(story.title, 26, Color.WHITE), rtl);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        if (rtl) title.setLineSpacing(0, 1.25f);
        page.addView(title);
        TextView byline = directed(text(byline(story, System.currentTimeMillis()), 15, TEXT_2), rtl);
        byline.setPadding(0, 0, 0, dp(16));
        page.addView(byline);
        if (!story.summary.isEmpty()) {
            TextView summary = directed(text(story.summary, 18, Color.WHITE), rtl);
            summary.setLineSpacing(0, rtl ? 1.4f : 1.25f);
            page.addView(summary);
        }
        // Room under the last line for the ⌄ cue.
        page.setPadding(dp(20), dp(16), dp(20), dp(48));
        detail.addView(page);
        detail.scrollTo(0, 0);
        moreCue.setVisibility(View.GONE);
        detail.post(this::updateMoreCue);
    }

    private void updateMoreCue() {
        moreCue.setVisibility(detail.canScrollVertically(1) ? View.VISIBLE : View.GONE);
    }

    /** The remote hint bar: key chips and what they do where focus is now. */
    private void showHint(View focus) {
        if (focus == null) return;
        int kind = focus == detail ? HINT_DETAIL : focus.getParent() == list ? HINT_ROW : HINT_TABS;
        if (kind == hintKind) return;
        hintKind = kind;
        hintBar.removeAllViews();
        if (kind == HINT_DETAIL) {
            addHint(R.string.key_back, R.string.hint_back_headlines);
        } else {
            addHint(R.string.key_ok, kind == HINT_ROW ? R.string.hint_read : R.string.hint_language);
        }
    }

    private void addHint(int key, int label) {
        TextView chip = text(getString(key), 12, Color.WHITE);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        chip.setBackground(shape(PILL, 100));
        hintBar.addView(chip);
        TextView words = directed(text(getString(label), 14, TEXT_2), rtlPage);
        words.setPadding(dp(8), 0, 0, 0);
        hintBar.addView(words);
    }

    /** "Source · 2 hours ago", in the TV's language. */
    private static String byline(Feed.Story story, long now) {
        if (story.time == 0) return story.source;
        // Isolated, so an English "16 minutes ago" keeps its word order after an Arabic source name.
        return story.source + " · \u2068"
                + DateUtils.getRelativeTimeSpanString(story.time, now, DateUtils.MINUTE_IN_MILLIS) + "\u2069";
    }

    private static TextView directed(TextView view, boolean rtl) {
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }

    private static int direction(boolean rtl) {
        return rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
    }

    /**
     * The one focus look: the view scales and rises, the white outline comes from its background, and the
     * call-back (may be null) runs on every focus change. Needs a rounded background so the shadow has an
     * outline.
     */
    private void lift(View view, float scale, View.OnFocusChangeListener extra) {
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        float rise = dp(8);
        view.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? scale : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(focused ? rise : 0f).setDuration(150);
            if (extra != null) extra.onFocusChange(v, focused);
        });
    }

    private Drawable shape(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    /** A shape with the 2 dp white focus outline. */
    private Drawable outlined(int fill, int radius) {
        GradientDrawable shape = (GradientDrawable) shape(fill, radius);
        shape.setStroke(dp(2), Color.WHITE);
        return shape;
    }

    /** The accent glow from the top-start corner, over the page colour. */
    private Drawable glow(boolean rtl) {
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(rtl ? 1f : 0f, 0f);
        glow.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.7f);
        glow.setColors(new int[] {GLOW, ACCENT & 0x00FFFFFF});
        return new LayerDrawable(new Drawable[] {new ColorDrawable(BACKGROUND), glow});
    }

    /** The lifted fill: a brighter surface with a faint sheen over its top half. */
    private Drawable focusedFill(int radius) {
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {SHEEN, 0, 0});
        sheen.setCornerRadius(dp(radius));
        return new LayerDrawable(new Drawable[] {outlined(SURFACE_FOCUSED, radius), sheen});
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

    /** A surface that lifts to the brighter one when focused. Built once per view, not per focus change. */
    private Drawable focusBack(int radius) {
        StateListDrawable back = new StateListDrawable();
        back.addState(new int[] {android.R.attr.state_focused}, focusedFill(radius));
        back.addState(new int[0], shape(SURFACE, radius));
        return back;
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
