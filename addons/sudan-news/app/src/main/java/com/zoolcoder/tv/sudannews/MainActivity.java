package com.zoolcoder.tv.sudannews;

import android.app.Activity;
import android.content.SharedPreferences;
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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static final int HINT_TABS = 0, HINT_ROW = 1, HINT_DETAIL = 2, HINT_RETRY = 3, HINT_ACTION = 4;
    private static final int MAX_SHOWN = 60;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final long STALE_MS = 15 * DateUtils.MINUTE_IN_MILLIS;
    private static final String KEPT = "stories.json";
    private static final long MAX_KEPT_BYTES = 1024 * 1024;
    private static final String SAVED_FILE = "saved.json", SAVED_TAB = "saved", PREFS = "news", TAB_KEY = "tab";

    private final List<Feed.Story> stories = new ArrayList<>();
    private final TextView[] tabs = new TextView[3];
    private TextView status, retry;
    private LinearLayout list;
    private ScrollView listScroll, detail;
    private TextView moreCue;
    private LinearLayout hintBar;
    private int hintKind = -1;
    private boolean rtlPage;
    private LinearLayout body;
    private View focusedRow, shownRow;
    private String language;
    private long loadedAt, keptAt;
    private int loadId;
    private boolean loading;
    // The feeds' own status line; the saved tab shows its own line instead.
    private CharSequence feedStatus = "";
    private boolean feedRetry;
    private Thread loader;
    private Drawable.ConstantState rowBack;
    private Drawable panelFocus, panelActive;
    private Saved saved = new Saved();
    private boolean savedReady;
    // The story in the panel, and its Listen pill.
    private Feed.Story shownStory;
    private TextView listen, saveButton;
    private boolean firstRowPending = true;
    private TextToSpeech speech;
    private boolean speechArabic, speechEnglish, speaking;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean rtl = "ar".equals(Locale.getDefault().getLanguage());
        // The tab the viewer used last; the TV's language on the first run.
        language = prefs().getString(TAB_KEY, rtl ? "ar" : "en");
        panelFocus = focusedFill(18);
        panelActive = shape(SURFACE, 18);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(direction(rtl));
        page.setBackground(glow(rtl));
        page.setClipChildren(false);
        // The list reaches 16 dp into the margin so a lifted row is not cut at the screen edge.
        page.setClipToPadding(false);
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
        // The update line, with a Try again pill beside it when the feeds could not be read.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setClipChildren(false);
        status = directed(text(getString(R.string.loading), 14, TEXT_3), rtl);
        statusRow.addView(status);
        retry = pill(getString(R.string.retry));
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            // Focus leaves the pill before it hides, so it does not fall somewhere random.
            tabs[tabIndex()].requestFocus();
            load();
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        retryParams.setMarginStart(dp(12));
        statusRow.addView(retry, retryParams);
        heading.addView(statusRow);
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setClipChildren(false);
        // A lifted end tab reaches past the 4 dp track padding.
        tabRow.setClipToPadding(false);
        tabRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabRow.setBackground(shape(TRACK, 26));
        tabs[0] = tab(getString(R.string.tab_ar), "ar");
        tabs[1] = tab(getString(R.string.tab_en), "en");
        tabs[2] = tab(getString(R.string.tab_saved), SAVED_TAB);
        for (TextView tab : tabs) tabRow.addView(tab);
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
        // The line cut by the bottom edge fades out above the ⌄ cue.
        detail.setVerticalFadingEdgeEnabled(true);
        detail.setFadingEdgeLength(dp(48));
        detail.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateMoreCue());
        // A ⌄ over the bottom edge while more of the story is below.
        moreCue = text("\u2304", 22, TEXT_2);
        moreCue.setGravity(Gravity.CENTER);
        moreCue.setPadding(dp(16), 0, dp(16), 0);
        moreCue.setBackground(shape(SCRIM, 14));
        moreCue.setVisibility(View.GONE);
        FrameLayout detailFrame = new FrameLayout(this);
        detailFrame.setClipChildren(false);
        // The panel stops above a strip kept for the cue, so the cue never sits on a line of the story.
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.bottomMargin = dp(32);
        detailFrame.addView(detail, panelParams);
        FrameLayout.LayoutParams cueParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(28), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
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
        page.getViewTreeObserver().addOnGlobalFocusChangeListener((oldView, newView) -> {
            // The outline when the panel itself scrolls; a quiet fill while one of its pills has focus.
            detail.setBackground(newView == detail ? panelFocus : detail.hasFocus() ? panelActive : null);
            showHint(newView);
        });
        showTabs();
        tabs[tabIndex()].requestFocus();
        showKept();
        startSpeech();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int tabIndex() {
        return SAVED_TAB.equals(language) ? 2 : "ar".equals(language) ? 0 : 1;
    }

    /** The system voice, if the TV has one; the Listen pill shows only for a language it speaks. */
    private void startSpeech() {
        speech = new TextToSpeech(this, result -> {
            if (result != TextToSpeech.SUCCESS || speech == null) return;
            speechArabic = speaks(new Locale("ar"));
            speechEnglish = speaks(Locale.ENGLISH);
            speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    runOnUiThread(() -> showSpeaking(false));
                }

                @Override
                public void onError(String id) {
                    runOnUiThread(() -> showSpeaking(false));
                }
            });
            showListen();
        });
    }

    private boolean speaks(Locale locale) {
        return speech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE;
    }

    private boolean canSpeak(Feed.Story story) {
        return story != null && ("ar".equals(story.language) ? speechArabic : speechEnglish);
    }

    private void showListen() {
        if (listen != null) listen.setVisibility(canSpeak(shownStory) ? View.VISIBLE : View.GONE);
    }

    private void toggleSpeech() {
        if (speaking) {
            stopSpeech();
            return;
        }
        Feed.Story story = shownStory;
        if (!canSpeak(story)) return;
        speech.setLanguage("ar".equals(story.language) ? new Locale("ar") : Locale.ENGLISH);
        String words = story.summary.isEmpty() ? story.title : story.title + ". " + story.summary;
        speech.speak(words, TextToSpeech.QUEUE_FLUSH, null, "story");
        showSpeaking(true);
    }

    private void stopSpeech() {
        if (speaking && speech != null) speech.stop();
        showSpeaking(false);
    }

    private void showSpeaking(boolean on) {
        speaking = on;
        if (listen != null) listen.setText(on ? R.string.stop : R.string.listen);
    }

    /** The copy from the last good load, on screen at once while the feeds load, and when they cannot. */
    private void showKept() {
        File file = new File(getFilesDir(), KEPT);
        File savedFile = new File(getFilesDir(), SAVED_FILE);
        new Thread(() -> {
            List<Feed.Story> kept = Feed.restore(read(file));
            long at = file.lastModified();
            Saved restored = Saved.restore(read(savedFile));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                saved = restored;
                savedReady = true;
                if (SAVED_TAB.equals(language)) {
                    showStories(true);
                    return;
                }
                showSave();
                // The feeds won the race, or there is no copy: nothing to show.
                if (kept.isEmpty() || !stories.isEmpty()) return;
                keptAt = at;
                stories.addAll(kept);
                showStories(true);
            });
        }).start();
    }

    private static String read(File file) {
        if (!file.isFile() || file.length() > MAX_KEPT_BYTES) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Written to a temporary file first, so a power cut leaves the old copy whole. */
    private static void write(File file, String text) {
        File temp = new File(file.getPath() + ".tmp");
        try {
            Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(file)) temp.delete();
        } catch (IOException e) {
            temp.delete();
        }
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
    protected void onStop() {
        stopSpeech();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (loader != null) loader.interrupt();
        if (speech != null) speech.shutdown();
        speech = null;
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
        setFeedStatus(getString(R.string.loading), false);
        // Placeholder rows while nothing is on screen yet.
        if (stories.isEmpty()) showStories(true);
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
                    keptAt = System.currentTimeMillis();
                    setFeedStatus(getString(R.string.updated, DateFormat.getTimeFormat(this).format(keptAt)), false);
                    File file = new File(getFilesDir(), KEPT);
                    new Thread(() -> write(file, Feed.save(found))).start();
                } else if (!stories.isEmpty() && keptAt > 0) {
                    // The kept copy stays on screen as it is, with the focus where the viewer left it.
                    setFeedStatus(getString(R.string.offline, DateUtils.formatDateTime(this, keptAt,
                            DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH)), true);
                    return;
                } else {
                    setFeedStatus(getString(R.string.failed), true);
                }
                // The saved tab does not change with the feeds; leave the viewer where they are.
                if (SAVED_TAB.equals(language)) return;
                showStories(false);
            });
        });
        loader.start();
    }

    private void setFeedStatus(CharSequence text, boolean retryShown) {
        feedStatus = text;
        feedRetry = retryShown;
        showStatus();
    }

    /** The line under the title: the feeds' state, or on the saved tab what is kept there. */
    private void showStatus() {
        boolean savedTab = SAVED_TAB.equals(language);
        if (savedTab) {
            int count = saved.list().size();
            status.setText(count == 0 ? getString(R.string.saved_status_empty)
                    : getResources().getQuantityString(R.plurals.saved_status, count, count));
        } else {
            status.setText(feedStatus);
        }
        boolean showRetry = !savedTab && feedRetry;
        // Focus leaves the pill before it hides, so it does not fall somewhere random.
        if (!showRetry && retry.hasFocus()) tabs[tabIndex()].requestFocus();
        retry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
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
            prefs().edit().putString(TAB_KEY, code).apply();
            showTabs();
            showStories(true);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!code.equals("ar")) params.setMarginStart(dp(8));
        tab.setLayoutParams(params);
        return tab;
    }

    /** The chosen tab is the white one; the focused one lifts. */
    private void showTabs() {
        for (TextView tab : tabs) {
            if (tab != null) tab.setSelected(tab == tabs[tabIndex()]);
        }
    }

    private void showStories(boolean keepTabFocus) {
        focusedRow = null;
        shownRow = null;
        list.removeAllViews();
        boolean savedTab = SAVED_TAB.equals(language);
        // The saved tab mixes both languages: the page follows the TV, each row its own story.
        boolean rtl = savedTab ? "ar".equals(Locale.getDefault().getLanguage()) : "ar".equals(language);
        body.setLayoutDirection(direction(rtl));
        hintBar.setLayoutDirection(direction(rtl));
        rtlPage = rtl;
        hintKind = -1;
        showStatus();
        long now = System.currentTimeMillis();
        Feed.Story first = null;
        for (Feed.Story story : savedTab ? saved.list() : stories) {
            if (!savedTab && !story.language.equals(language)) continue;
            if (list.getChildCount() == MAX_SHOWN) break;
            if (first == null) first = story;
            list.addView(row(story, "ar".equals(story.language), now));
        }
        if (list.getChildCount() == 0) {
            if (savedTab) {
                if (savedReady) list.addView(savedEmpty(rtl));
            } else if (loading && loadedAt == 0) {
                for (int i = 0; i < 3; i++) list.addView(skeleton());
            } else if (loadedAt != 0) {
                list.addView(directed(text(getString(R.string.empty), 18, TEXT_2), rtl));
            }
        }
        listScroll.scrollTo(0, 0);
        showDetail(first, first != null && "ar".equals(first.language));
        View firstRow = list.getChildAt(0);
        // At launch, also with a restored tab, the first headline takes focus as soon as there is one.
        if ((!keepTabFocus || firstRowPending) && firstRow != null && firstRow.isFocusable()) {
            firstRowPending = false;
            firstRow.requestFocus();
        }
        // The bar was cleared above; a focus that did not move would leave it empty.
        showHint(getCurrentFocus());
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
        if (story != shownStory) stopSpeech();
        shownStory = story;
        listen = null;
        saveButton = null;
        detail.removeAllViews();
        detail.setFocusable(story != null);
        moreCue.setVisibility(View.GONE);
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
        byline.setPadding(0, 0, 0, dp(12));
        page.addView(byline);
        // Save and Listen: what OK does in the panel. Down from them scrolls the story.
        LinearLayout actions = new LinearLayout(this);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(0, dp(4), 0, dp(16));
        saveButton = pill("");
        saveButton.setOnClickListener(v -> toggleSaved());
        actions.addView(saveButton);
        listen = pill(getString(speaking ? R.string.stop : R.string.listen));
        listen.setOnClickListener(v -> toggleSpeech());
        LinearLayout.LayoutParams listenParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listenParams.setMarginStart(dp(12));
        actions.addView(listen, listenParams);
        page.addView(actions);
        showSave();
        showListen();
        if (!story.summary.isEmpty()) {
            TextView summary = directed(text(story.summary, 18, Color.WHITE), rtl);
            summary.setLineSpacing(0, rtl ? 1.4f : 1.25f);
            page.addView(summary);
        }
        // The scaled Save and Listen pills reach past their row and into this padding; clipping cut their outline.
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(dp(20), dp(16), dp(20), dp(16));
        detail.addView(page);
        detail.scrollTo(0, 0);
        moreCue.setVisibility(View.GONE);
        detail.post(this::updateMoreCue);
    }

    private void toggleSaved() {
        if (!savedReady || shownStory == null) return;
        saved.toggle(shownStory);
        showSave();
        showStatus();
        File file = new File(getFilesDir(), SAVED_FILE);
        String json = saved.save();
        new Thread(() -> write(file, json)).start();
    }

    private void showSave() {
        if (saveButton == null) return;
        saveButton.setText(saved.contains(shownStory) ? R.string.saved : R.string.save);
        saveButton.setVisibility(savedReady ? View.VISIBLE : View.INVISIBLE);
    }

    /** A small focusable pill: a light fill, white with dark text when focused. */
    private TextView pill(String label) {
        TextView pill = text(label, 15, Color.WHITE);
        pill.setTypeface(MEDIUM);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dp(36));
        pill.setPadding(dp(16), 0, dp(16), 0);
        pill.setBackground(states(shape(PILL, 18), shape(PILL, 18), outlined(Color.WHITE, 18),
                outlined(Color.WHITE, 18)));
        pill.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ON_LIGHT, Color.WHITE}));
        pill.setFocusable(true);
        pill.setClickable(true);
        lift(pill, TAB_SCALE, null);
        return pill;
    }

    /** The saved tab with nothing in it: what it is for and how to fill it. */
    private View savedEmpty(boolean rtl) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setBackground(shape(SURFACE, 14));
        TextView star = directed(text("\u2606", 34, TEXT_2), rtl);
        box.addView(star);
        TextView heading = directed(text(getString(R.string.saved_empty_title), 22, Color.WHITE), rtl);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(8), 0, dp(8));
        box.addView(heading);
        box.addView(directed(text(getString(R.string.saved_empty), 16, TEXT_2), rtl));
        return box;
    }

    /** A grey stand-in for a headline row while the first load runs. */
    private View skeleton() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(shape(SURFACE, 14));
        int[] widths = {120, 380, 260};
        int[] heights = {12, 18, 18};
        for (int i = 0; i < widths.length; i++) {
            View bar = new View(this);
            bar.setBackground(shape(TRACK, 6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widths[i]), dp(heights[i]));
            params.topMargin = i == 0 ? 0 : dp(8);
            row.addView(bar, params);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(params);
        return row;
    }

    private void updateMoreCue() {
        moreCue.setVisibility(detail.canScrollVertically(1) ? View.VISIBLE : View.GONE);
    }

    /** The remote hint bar: key chips and what they do where focus is now. */
    private void showHint(View focus) {
        if (focus == null) return;
        int kind = focus == detail ? HINT_DETAIL : focus == retry ? HINT_RETRY
                : detail.hasFocus() ? HINT_ACTION : focus.getParent() == list ? HINT_ROW : HINT_TABS;
        // Moving between tabs keeps the kind but may change the words.
        if (kind == hintKind && kind != HINT_TABS) return;
        hintKind = kind;
        hintBar.removeAllViews();
        if (kind == HINT_DETAIL) {
            addHint(R.string.key_back, R.string.hint_back_headlines);
        } else if (kind == HINT_RETRY) {
            addHint(R.string.key_ok, R.string.hint_retry);
        } else if (kind == HINT_ACTION) {
            addHint(R.string.key_ok, R.string.hint_select);
            addHint(R.string.key_back, R.string.hint_back_headlines);
        } else {
            addHint(R.string.key_ok, kind == HINT_ROW ? R.string.hint_read
                    : focus == tabs[2] ? R.string.hint_saved_tab : R.string.hint_language);
        }
    }

    private void addHint(int key, int label) {
        TextView chip = text(getString(key), 12, Color.WHITE);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        chip.setBackground(shape(PILL, 100));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (hintBar.getChildCount() > 0) params.setMarginStart(dp(24));
        hintBar.addView(chip, params);
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
