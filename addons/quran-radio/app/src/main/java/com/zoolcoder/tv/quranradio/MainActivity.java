package com.zoolcoder.tv.quranradio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Quran on the TV: live radio stations and the surahs of a few reciters, both listed in config.xml. Audio
 * plays with MediaPlayer only while the app is open; leaving it stops the sound, and nothing runs in the
 * background. The screen stays on while a recitation plays. The list is on one side and a flat now-playing
 * panel, drawn with shapes and text only, on the other, in the Apple TV look: glow background, lifted focus.
 */
public class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFF0B1A12, GLOW = 0x26FFC342, SURFACE = 0x9916281D,
            SURFACE_FOCUSED = 0xFF2C4838, TEXT = 0xFFFFFFFF, TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF,
            PILL = 0x29FFFFFF, TRACK = 0x1FFFFFFF, TRACK_LINE = 0x33FFFFFF, ON_LIGHT = 0xFF0B1A12,
            ACCENT = 0xFFFFC342, ACCENT_TINT = 0x26FFC342, FOCUS_STROKE = 0xFFFFFFFF;
    private static final float ROW_SCALE = 1.02f, TILE_SCALE = 1.04f;
    private static final int RADIO = 0, RECITERS = 1;
    private static final int IDLE = 0, LOADING = 1, LOADED = 2, FAILED = 3;

    private final TextView[] tabs = new TextView[2];
    private final List<Catalog.Station> stations = new ArrayList<>();
    private final List<Catalog.Reciter> reciters = new ArrayList<>();
    private String[] surahNames = new String[115];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView okChip, okLabel, backLabel, tabsChip, tabsLabel, pill, panelName, panelSecondary, panelSurah,
            statusDetail, clock;
    private LinearLayout hintBar;
    private View focusedView;
    private Drawable pillPlaying, pillNeutral;
    private PlayButton playButton;
    private LinearLayout progress;
    private View progressFill, progressRest;
    private LinearLayout list;
    private ScrollView listScroll;
    private boolean arabic;
    private int tab = RADIO, loadState = IDLE, loadId, reciterFocus;
    private Catalog.Reciter openReciter;

    // The catalog is fetched once per process and kept, so a recreated activity or a return to the tab does not refetch.
    private static List<Catalog.Reciter> cachedReciters;
    private static String[] cachedSurahNames;
    private static String cachedLanguage;
    private Thread loader;

    private MediaPlayer player;
    private boolean ready, paused;
    private String playingKey, playingName, playingSecondary, playingSurah;
    private boolean live, offline;
    private Catalog.Reciter queueReciter;
    private int queueIndex;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean pausedByFocus, noisyRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        arabic = "ar".equals(Locale.getDefault().getLanguage());
        audio = getSystemService(AudioManager.class);
        for (String line : getResources().getStringArray(R.array.stations)) {
            Catalog.Station station = Catalog.parseStation(line);
            if (station != null) stations.add(station);
        }

        int direction = arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(direction);
        page.setPadding(dp(48), dp(28), dp(48), dp(24));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setBackground(glow());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        View marker = new View(this);
        marker.setBackground(shape(ACCENT, 2));
        header.addView(marker, new LinearLayout.LayoutParams(dp(4), dp(26)));
        TextView title = directed(text(getString(R.string.title), 30, TEXT));
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(12), 0, dp(12), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs[RADIO] = tab(getString(R.string.tab_radio), RADIO);
        tabs[RECITERS] = tab(getString(R.string.tab_reciters), RECITERS);
        LinearLayout track = new LinearLayout(this);
        track.setClipChildren(false);
        track.setGravity(Gravity.CENTER_VERTICAL);
        track.setPadding(dp(4), dp(4), dp(4), dp(4));
        track.setBackground(shape(TRACK, 26));
        track.addView(tabs[RADIO]);
        track.addView(tabs[RECITERS]);
        header.addView(track);
        page.addView(header);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        listScroll = new ScrollView(this);
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        listScroll.setClipChildren(false);
        listScroll.setClipToPadding(false);
        listScroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        listScroll.setClipToOutline(true);
        listScroll.setPadding(dp(12), dp(12), dp(12), dp(12));
        listScroll.addView(list);
        LinearLayout body = new LinearLayout(this);
        body.setClipChildren(false);
        // The list's 12 dp padding holds the scaled row; negative margins line its rows up with the title.
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(dp(392),
                LinearLayout.LayoutParams.MATCH_PARENT);
        listParams.setMarginStart(-dp(12));
        listParams.setMarginEnd(-dp(12));
        body.addView(listScroll, listParams);
        View gap = new View(this);
        body.addView(gap, new LinearLayout.LayoutParams(dp(24), 1));
        body.addView(buildPanel(), new LinearLayout.LayoutParams(dp(448), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = dp(12);
        page.addView(body, bodyParams);

        page.addView(buildHintBar());
        setContentView(page);
        showTabs();
        showList(false);
    }

    /** Audio only while the app is on screen. */
    @Override
    protected void onStop() {
        stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        loadId++;
        if (loader != null) loader.interrupt();
        stop();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Back leaves a reciter's surahs, then stops the sound, then closes the app. */
    @Override
    public void onBackPressed() {
        if (openReciter != null) {
            openReciter = null;
            showList(false);
        } else if (player != null || playingKey != null) {
            stop();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                stop();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---- playback ----

    private void play(String url, String key, String name, String secondary, String surah, boolean isLive) {
        release();
        playingKey = key;
        playingName = name;
        playingSecondary = secondary;
        playingSurah = surah;
        live = isLive;
        offline = false;
        ready = false;
        paused = false;
        renderPanel();
        if (!requestFocus()) {
            fail();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        next.setOnPreparedListener(p -> {
            if (p != player) return;
            ready = true;
            setNoisyListening(true);
            try {
                p.start();
            } catch (IllegalStateException e) {
                fail();
                return;
            }
            showPlaybackState();
        });
        next.setOnCompletionListener(p -> {
            if (p != player) return;
            if (live) fail();
            else playNextSurah();
        });
        next.setOnErrorListener((p, what, extra) -> {
            if (p == player) fail();
            return true;
        });
        try {
            next.setDataSource(url);
            next.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
            fail();
            return;
        }
        refreshMarks();
    }

    private void playStation(Catalog.Station station) {
        queueReciter = null;
        String[] names = names(station);
        play(station.url, "s:" + station.url, names[0], names[1], null, true);
    }

    private void playSurah(Catalog.Reciter reciter, int index) {
        queueReciter = reciter;
        queueIndex = index;
        int surah = reciter.surahs[index];
        play(Catalog.surahUrl(reciter, surah), surahKey(reciter, surah), reciter.name, reciter.recitation,
                surahTitle(surah), false);
    }

    /** The next surah of the same recitation follows; after the last one the sound stops. */
    private void playNextSurah() {
        if (queueReciter != null && queueIndex + 1 < queueReciter.surahs.length) {
            playSurah(queueReciter, queueIndex + 1);
        } else {
            stop();
        }
    }

    private void togglePause() {
        if (player == null || !ready) return;
        pausedByFocus = false;
        if (paused) resume();
        else pause();
    }

    private void pause() {
        try {
            player.pause();
        } catch (IllegalStateException e) {
            fail();
            return;
        }
        paused = true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showPlaybackState();
    }

    private void resume() {
        if (!requestFocus()) return;
        try {
            player.start();
        } catch (IllegalStateException e) {
            fail();
            return;
        }
        paused = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showPlaybackState();
    }

    // ---- audio focus and headphones ----

    private boolean requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(this::onFocusChange, handler)
                    .build();
        }
        return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Another app took the sound: a short loss pauses and comes back by itself, a long one stops. */
    private void onFocusChange(int change) {
        if (player == null) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                stop();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (ready && !paused) {
                    pause();
                    pausedByFocus = paused;
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (pausedByFocus && paused) {
                    pausedByFocus = false;
                    resume();
                }
                break;
            default:
                break;
        }
    }

    /** Headphones or an HDMI sink going away pause the sound instead of moving it to the speakers. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (player != null && ready && !paused) {
                pausedByFocus = false;
                pause();
            }
        }
    };

    private void setNoisyListening(boolean on) {
        if (on == noisyRegistered) return;
        noisyRegistered = on;
        if (on) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        else unregisterReceiver(noisy);
    }

    private void showPlaybackState() {
        renderPanel();
        refreshMarks();
    }

    private void stop() {
        release();
        playingKey = null;
        playingName = null;
        playingSecondary = null;
        playingSurah = null;
        offline = false;
        queueReciter = null;
        renderPanel();
        refreshMarks();
    }

    private void fail() {
        release();
        playingKey = null;
        queueReciter = null;
        offline = true;
        renderPanel();
        refreshMarks();
    }

    private void release() {
        handler.removeCallbacks(tick);
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            old.setOnPreparedListener(null);
            old.setOnCompletionListener(null);
            old.setOnErrorListener(null);
            old.release();
        }
        if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        setNoisyListening(false);
        ready = false;
        paused = false;
        pausedByFocus = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---- reciters from mp3quran.net ----

    private void loadReciters() {
        if (loadState == LOADING || loadState == LOADED) return;
        String language = arabic ? "ar" : "eng";
        if (cachedReciters != null && language.equals(cachedLanguage)) {
            reciters.clear();
            reciters.addAll(cachedReciters);
            surahNames = cachedSurahNames;
            loadState = LOADED;
            return;
        }
        loadState = LOADING;
        int id = ++loadId;
        List<Catalog.Ref> refs = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.reciters)) {
            Catalog.Ref ref = Catalog.parseRef(line);
            if (ref != null) refs.add(ref);
        }
        loader = new Thread(() -> {
            List<Catalog.Reciter> found = new ArrayList<>();
            String[] names = new String[115];
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(4, refs.size())));
            try {
                List<Callable<Catalog.Reciter>> jobs = new ArrayList<>();
                for (Catalog.Ref ref : refs) {
                    jobs.add(() -> Catalog.parseReciter(Api.get(Catalog.reciterUrl(ref, language)), ref));
                }
                Future<String> suwar = pool.submit(() -> Api.get(Catalog.suwarUrl(language)));
                for (Future<Catalog.Reciter> result : pool.invokeAll(jobs)) {
                    try {
                        Catalog.Reciter reciter = result.get();
                        if (reciter != null) found.add(reciter);
                    } catch (Exception ignored) {
                        // One reciter down leaves the others on screen.
                    }
                }
                try {
                    names = Catalog.parseSuwar(suwar.get());
                } catch (Exception ignored) {
                    names = Catalog.parseSuwar("");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
            }
            String[] surahs = names;
            if (found.size() == refs.size() && !Thread.currentThread().isInterrupted()) {
                synchronized (MainActivity.class) {
                    cachedReciters = found;
                    cachedSurahNames = surahs;
                    cachedLanguage = language;
                }
            }
            runOnUiThread(() -> {
                if (id != loadId || isDestroyed()) return;
                reciters.clear();
                reciters.addAll(found);
                surahNames = surahs;
                loadState = found.isEmpty() ? FAILED : LOADED;
                if (tab == RECITERS && openReciter == null) showList(tabs[RECITERS].isFocused());
            });
        });
        loader.start();
    }

    private String surahTitle(int surah) {
        String name = surahNames[surah] == null ? "" : surahNames[surah];
        return getString(R.string.surah_format, surah, name).trim();
    }

    private static String surahKey(Catalog.Reciter reciter, int surah) {
        return "r:" + reciter.server + surah;
    }

    // ---- screens ----

    private TextView tab(String label, int index) {
        TextView tab = directed(text(label, 18, TEXT_2));
        tab.setTypeface(MEDIUM);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(20), 0, dp(20), 0);
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setBackground(tabBackground());
        tab.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        tab.setOnFocusChangeListener((v, focused) -> {
            lift(v, focused, TILE_SCALE);
            showTabs();
            focusMoved(v, focused);
        });
        tab.setOnClickListener(v -> selectTab(index));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        if (index > 0) params.setMarginStart(dp(8));
        tab.setLayoutParams(params);
        return tab;
    }

    private void selectTab(int index) {
        if (index == this.tab) return;
        this.tab = index;
        openReciter = null;
        showTabs();
        if (index == RECITERS) loadReciters();
        showList(true);
    }

    /** The chosen tab is a white pill with dark text; a focused one adds the white outline and lifts. */
    private void showTabs() {
        for (int i = 0; i < tabs.length; i++) {
            boolean chosen = i == tab;
            tabs[i].setSelected(chosen);
            tabs[i].setTextColor(chosen ? ON_LIGHT : tabs[i].isFocused() ? TEXT : TEXT_2);
        }
    }

    private void showList(boolean keepTabFocus) {
        list.removeAllViews();
        View focus = null;
        if (openReciter != null) {
            TextView section = directed(text(openReciter.name + " · " + openReciter.recitation, 20, TEXT_2));
            section.setTypeface(MEDIUM);
            section.setPadding(dp(4), 0, dp(4), dp(8));
            list.addView(section);
            for (int i = 0; i < openReciter.surahs.length; i++) {
                int index = i;
                int surah = openReciter.surahs[i];
                String key = surahKey(openReciter, surah);
                View row = row(surahTitle(surah), null, null, key, v -> playSurah(openReciter, index), false);
                list.addView(row);
                if (focus == null || key.equals(playingKey)) focus = row;
            }
        } else {
            if (tab == RADIO) {
                for (Catalog.Station station : stations) {
                    String[] names = names(station);
                    list.addView(row(names[0], names[1], getString(R.string.live), "s:" + station.url,
                            v -> playStation(station), true));
                }
            } else if (loadState == LOADING) {
                list.addView(directed(text(getString(R.string.loading), 18, TEXT_2)));
            } else if (loadState == FAILED) {
                list.addView(directed(text(getString(R.string.failed), 18, TEXT_2)));
            } else {
                for (int i = 0; i < reciters.size(); i++) {
                    int index = i;
                    Catalog.Reciter reciter = reciters.get(i);
                    list.addView(row(reciter.name, reciter.recitation, null, reciterKey(reciter),
                            v -> {
                                reciterFocus = index;
                                openReciter = reciter;
                                showList(false);
                            }, false));
                }
            }
            if (list.getChildCount() == 0) list.addView(directed(text(getString(R.string.empty), 18, TEXT_2)));
            if (tab == RECITERS && loadState == LOADED && reciterFocus < list.getChildCount()) {
                focus = list.getChildAt(reciterFocus);
            } else {
                focus = firstRow();
            }
        }
        refreshMarks();
        listScroll.scrollTo(0, 0);
        if (!keepTabFocus && focus != null) focus.requestFocus();
        updateHint();
    }

    private View firstRow() {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) return list.getChildAt(i);
        }
        return null;
    }

    private View row(String title, String secondary, String idleStatus, String key, View.OnClickListener onClick,
            boolean station) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setMinimumHeight(dp(secondary == null ? 56 : 64));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setBackground(rowBackground());
        row.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(key);
        row.setOnFocusChangeListener((v, focused) -> {
            lift(v, focused, ROW_SCALE);
            focusMoved(v, focused);
        });

        View disc;
        if (station) {
            disc = new RadioGlyph(this);
        } else {
            TextView note = text("\u266A", 18, ACCENT);
            note.setGravity(Gravity.CENTER);
            disc = note;
        }
        disc.setBackground(oval(PILL));
        row.addView(disc, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, dp(14), 0);
        TextView label = directed(text(title, 19, TEXT));
        label.setTypeface(MEDIUM);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(label);
        if (secondary != null) {
            TextView second = directed(text(secondary, 15, TEXT_2));
            second.setSingleLine();
            second.setEllipsize(TextUtils.TruncateAt.END);
            if (isArabic(secondary)) second.setLineSpacing(0, ARABIC_TITLE_SPACING);
            words.addView(second);
        }
        TextView state = directed(text(idleStatus == null ? "" : idleStatus, 14, TEXT_3));
        state.setTag(idleStatus);
        state.setVisibility(idleStatus == null ? View.GONE : View.VISIBLE);
        words.addView(state);
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            if (isPlayingKey(key) && player != null && ready) togglePause();
            else onClick.onClick(v);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(params);
        return row;
    }

    /** A station key is the one playing; a reciter key is too while one of that reciter's surahs plays. */
    private boolean isPlayingKey(String key) {
        if (key.equals(playingKey)) return true;
        return queueReciter != null && playingKey != null && key.equals(reciterKey(queueReciter));
    }

    /** The playing row gets the accent tint and "Live - Playing" in accent; the idle status returns when it stops. */
    private void refreshMarks() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (!(row instanceof ViewGroup) || row.getTag() == null) continue;
            ViewGroup words = (ViewGroup) ((ViewGroup) row).getChildAt(1);
            TextView state = (TextView) words.getChildAt(words.getChildCount() - 1);
            boolean current = isPlayingKey((String) row.getTag());
            row.setActivated(current);
            if (current) {
                boolean station = state.getTag() != null;
                state.setText(paused ? "\u25CB " + getString(R.string.state_paused)
                        : "\u25CF " + getString(station ? R.string.live_playing : R.string.playing));
                state.setTextColor(paused ? TEXT_2 : ACCENT);
                state.setVisibility(View.VISIBLE);
            } else {
                String idle = (String) state.getTag();
                state.setText(idle == null ? "" : idle);
                state.setTextColor(TEXT_3);
                state.setVisibility(idle == null ? View.GONE : View.VISIBLE);
            }
        }
        updateHint();
    }

    /** "Arabic - English" station names: the language of the screen comes first, the other under it. */
    private String[] names(Catalog.Station station) {
        int dot = station.name.indexOf(" \u00B7 ");
        if (dot < 0) return new String[] {station.name, null};
        String first = station.name.substring(0, dot).trim();
        String second = station.name.substring(dot + 3).trim();
        boolean firstArabic = isArabic(first);
        return arabic == firstArabic ? new String[] {first, second} : new String[] {second, first};
    }

    private static boolean isArabic(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.UnicodeBlock.of(value.charAt(i)) == Character.UnicodeBlock.ARABIC) return true;
        }
        return false;
    }

    private static String reciterKey(Catalog.Reciter reciter) {
        return "c:" + reciter.id + reciter.server;
    }

    // ---- remote hint bar ----

    /** Key chips and what the keys do right now: one row, mirrored in RTL with the page. */
    private View buildHintBar() {
        hintBar = new LinearLayout(this);
        hintBar.setGravity(Gravity.CENTER_VERTICAL);
        hintBar.setPadding(0, dp(12), 0, 0);
        okChip = chip(getString(R.string.key_ok));
        hintBar.addView(okChip);
        okLabel = hintLabel();
        hintBar.addView(okLabel);
        hintBar.addView(chip(getString(R.string.key_back)));
        backLabel = hintLabel();
        hintBar.addView(backLabel);
        tabsChip = chip("\u2191");
        hintBar.addView(tabsChip);
        tabsLabel = hintLabel();
        tabsLabel.setText(R.string.hint_tabs);
        hintBar.addView(tabsLabel);
        return hintBar;
    }

    private TextView chip(String key) {
        TextView chip = text(key, 12, TEXT);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        chip.setBackground(shape(PILL, 100));
        return chip;
    }

    private TextView hintLabel() {
        TextView label = directed(text("", 14, TEXT_2));
        label.setPadding(dp(8), 0, dp(24), 0);
        return label;
    }

    private void focusMoved(View view, boolean focused) {
        if (focused) focusedView = view;
        else if (focusedView == view) focusedView = null;
        updateHint();
    }

    /** What OK and Back really do: OK follows the focused view, Back follows onBackPressed. */
    private void updateHint() {
        if (okLabel == null) return;
        boolean running = player != null && ready;
        int ok;
        if (focusedView == playButton) {
            ok = !running ? 0 : paused ? R.string.hint_resume : R.string.hint_pause;
        } else if (focusedView instanceof TextView) {
            ok = R.string.hint_open_tab;
        } else if (focusedView != null && focusedView.getTag() instanceof String) {
            String key = (String) focusedView.getTag();
            if (running && isPlayingKey(key)) ok = paused ? R.string.hint_resume : R.string.hint_pause;
            else ok = key.startsWith("c:") ? R.string.hint_open_surahs : R.string.hint_play;
        } else {
            ok = 0;
        }
        okLabel.setText(ok == 0 ? "" : getString(ok));
        okLabel.setVisibility(ok == 0 ? View.GONE : View.VISIBLE);
        okChip.setVisibility(ok == 0 ? View.GONE : View.VISIBLE);
        backLabel.setText(openReciter != null ? R.string.hint_to_reciters
                : player != null || playingKey != null ? R.string.hint_stop : R.string.hint_close);
        boolean topLevel = openReciter == null;
        tabsChip.setVisibility(topLevel ? View.VISIBLE : View.GONE);
        tabsLabel.setVisibility(topLevel ? View.VISIBLE : View.GONE);
    }

    // ---- now-playing panel ----

    private View buildPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setClipChildren(false);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        panel.setBackground(shape(SURFACE, 18));

        pillPlaying = shape(ACCENT_TINT, 100);
        pillNeutral = shape(PILL, 100);
        pill = directed(text("", 15, ACCENT));
        pill.setTypeface(MEDIUM);
        pill.setPadding(dp(12), dp(4), dp(12), dp(4));
        panel.addView(pill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        panelName = directed(text("", 30, TEXT));
        panelName.setTypeface(null, Typeface.BOLD);
        panelName.setMaxLines(2);
        panelName.setEllipsize(TextUtils.TruncateAt.END);
        panelName.setPadding(0, dp(12), 0, 0);
        panel.addView(panelName);
        panelSecondary = directed(text("", 22, TEXT_2));
        panelSecondary.setMaxLines(2);
        panelSecondary.setEllipsize(TextUtils.TruncateAt.END);
        panelSecondary.setPadding(0, dp(4), 0, 0);
        panel.addView(panelSecondary);
        panelSurah = directed(text("", 19, ACCENT));
        panelSurah.setTypeface(MEDIUM);
        panelSurah.setPadding(0, dp(8), 0, 0);
        panel.addView(panelSurah);

        playButton = new PlayButton(this);
        playButton.setOnClickListener(v -> togglePause());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
        buttonParams.topMargin = dp(24);
        panel.addView(playButton, buttonParams);

        statusDetail = text(getString(R.string.offline_hint), 15, TEXT_2);
        statusDetail.setGravity(Gravity.CENTER);
        statusDetail.setPadding(0, dp(16), 0, 0);
        panel.addView(statusDetail);

        progress = new LinearLayout(this);
        progress.setBackground(shape(TRACK_LINE, 2));
        progressFill = new View(this);
        progressFill.setBackground(shape(ACCENT, 2));
        progressRest = new View(this);
        progress.addView(progressFill, new LinearLayout.LayoutParams(0, dp(4), 0f));
        progress.addView(progressRest, new LinearLayout.LayoutParams(0, dp(4), 1f));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        barParams.topMargin = dp(24);
        panel.addView(progress, barParams);
        clock = text("", 14, TEXT_3);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, dp(8), 0, 0);
        panel.addView(clock);
        renderPanel();
        return panel;
    }

    /** One place decides what the panel says; the state text is never colour alone. */
    private void renderPanel() {
        boolean active = playingKey != null;
        boolean connecting = active && !ready;
        boolean surah = playingSurah != null;
        pill.setVisibility(active || offline ? View.VISIBLE : View.GONE);
        panelName.setText(playingName == null ? getString(R.string.idle) : playingName);
        boolean second = playingSecondary != null && !playingSecondary.isEmpty();
        panelSecondary.setText(playingSecondary);
        panelSecondary.setVisibility(second ? View.VISIBLE : View.GONE);
        panelSecondary.setLineSpacing(0, second && isArabic(playingSecondary) ? ARABIC_TITLE_SPACING : 1f);
        panelSurah.setText(playingSurah);
        panelSurah.setVisibility(surah ? View.VISIBLE : View.GONE);
        playButton.setState(ready && !paused, active && ready);
        if (connecting) {
            showPill(getString(R.string.connecting), false, false);
        } else if (offline) {
            showPill("\u00D7 " + getString(R.string.state_offline), false, true);
        } else if (active) {
            showPill(paused ? "\u25CB " + getString(R.string.state_paused)
                    : "\u25CF " + getString(R.string.state_playing), !paused, paused);
        }
        statusDetail.setVisibility(offline ? View.VISIBLE : View.GONE);
        int surahBar = surah && ready ? View.VISIBLE : View.GONE;
        progress.setVisibility(surahBar);
        clock.setVisibility(surahBar);
        handler.removeCallbacks(tick);
        if (surahBar == View.VISIBLE) tick.run();
    }

    /** "Playing now" is accent text on an accent tint; every other state is white on the neutral pill. */
    private void showPill(String label, boolean accent, boolean strong) {
        pill.setText(label);
        pill.setBackground(accent ? pillPlaying : pillNeutral);
        pill.setTextColor(accent ? ACCENT : strong ? TEXT : TEXT_2);
    }

    /** Position of the surah, once a second while it is on screen and playing. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player == null || !ready) return;
            int position, duration;
            try {
                position = player.getCurrentPosition();
                duration = player.getDuration();
            } catch (IllegalStateException e) {
                return;
            }
            float done = duration > 0 ? Math.min(1f, (float) position / duration) : 0f;
            ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = done;
            ((LinearLayout.LayoutParams) progressRest.getLayoutParams()).weight = 1f - done;
            progress.requestLayout();
            clock.setText(clock(position) + " / " + clock(Math.max(duration, 0)));
            if (!paused) handler.postDelayed(this, 1000);
        }
    };

    private static String clock(int millis) {
        int seconds = millis / 1000;
        return seconds >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---- shapes ----

    private static final float ARABIC_TITLE_SPACING = 1.25f;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Focus lifts the view: it scales, and a soft shadow shows under it. */
    private void lift(View view, boolean focused, float scale) {
        float to = focused ? scale : 1f;
        view.animate().scaleX(to).scaleY(to).setDuration(150).start();
        view.setElevation(focused ? dp(8) : 0);
    }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable oval(int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        return shape;
    }

    private GradientDrawable outlined(int fill, int radius) {
        GradientDrawable shape = shape(fill, radius);
        shape.setStroke(dp(2), FOCUS_STROKE);
        return shape;
    }

    /** List row: focused = lighter fill + white outline; playing (activated) = accent tint; both when both. */
    private Drawable rowBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused, android.R.attr.state_activated},
                outlined(blend(SURFACE_FOCUSED, ACCENT_TINT), 14));
        states.addState(new int[] {android.R.attr.state_focused}, outlined(SURFACE_FOCUSED, 14));
        states.addState(new int[] {android.R.attr.state_activated}, shape(blend(SURFACE, ACCENT_TINT), 14));
        states.addState(new int[0], shape(SURFACE, 14));
        return states;
    }

    /** Tab: chosen = white fill; focused adds the outline and a faint fill when not chosen. */
    private Drawable tabBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused, android.R.attr.state_selected},
                outlined(Color.WHITE, 22));
        states.addState(new int[] {android.R.attr.state_focused}, outlined(PILL, 22));
        states.addState(new int[] {android.R.attr.state_selected}, shape(Color.WHITE, 22));
        states.addState(new int[0], shape(Color.TRANSPARENT, 22));
        return states;
    }

    /** Opaque result of a translucent colour laid over a base, so a row never shows the glow through. */
    private static int blend(int base, int over) {
        float a = Color.alpha(over) / 255f;
        int baseAlpha = Color.alpha(base);
        return Color.argb(baseAlpha,
                Math.round(Color.red(over) * a + Color.red(base) * (1 - a)),
                Math.round(Color.green(over) * a + Color.green(base) * (1 - a)),
                Math.round(Color.blue(over) * a + Color.blue(base) * (1 - a)));
    }

    /** The window base, lit by an accent glow from the top-start corner. */
    private Drawable glow() {
        GradientDrawable light = new GradientDrawable();
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        light.setGradientCenter(arabic ? 1f : 0f, 0f);
        light.setGradientRadius(dp(672));
        light.setColors(new int[] {GLOW, GLOW & 0x00FFFFFF});
        return new LayerDrawable(new Drawable[] {new ColorDrawable(BACKGROUND), light});
    }

    /** A radio mast with a wave on each side, in the accent. */
    private final class RadioGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();

        RadioGlyph(Context context) {
            super(context);
            paint.setColor(ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float c = getWidth() / 2f, u = getWidth() / 40f;
            paint.setStrokeWidth(2 * u);
            for (int ring = 1; ring <= 2; ring++) {
                float r = (4 + 5 * ring) * u;
                arc.set(c - r, c - r, c + r, c + r);
                canvas.drawArc(arc, -45, 90, false, paint);
                canvas.drawArc(arc, 135, 90, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(c, c, 3 * u, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
    }

    /** The 72 dp round control: a play triangle or two pause bars on the accent; white when focused. */
    private final class PlayButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private boolean playing, enabledLook;

        PlayButton(Activity context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            setOnFocusChangeListener((v, focused) -> {
                lift(v, focused, TILE_SCALE);
                invalidate();
                focusMoved(v, focused);
            });
        }

        void setState(boolean playing, boolean enabledLook) {
            this.playing = playing;
            this.enabledLook = enabledLook;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float c = getWidth() / 2f;
            paint.setColor(enabledLook ? ACCENT : PILL);
            canvas.drawCircle(c, c, c, paint);
            if (isFocused()) {
                float ring = 3 * getWidth() / 72f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ring);
                paint.setColor(FOCUS_STROKE);
                canvas.drawCircle(c, c, c - ring / 2, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(enabledLook ? ON_LIGHT : TEXT_3);
            float u = getWidth() / 72f;
            if (playing) {
                canvas.drawRect(c - 10 * u, c - 12 * u, c - 3 * u, c + 12 * u, paint);
                canvas.drawRect(c + 3 * u, c - 12 * u, c + 10 * u, c + 12 * u, paint);
            } else {
                triangle.reset();
                triangle.moveTo(c - 8 * u, c - 13 * u);
                triangle.lineTo(c + 12 * u, c);
                triangle.lineTo(c - 8 * u, c + 13 * u);
                triangle.close();
                canvas.drawPath(triangle, paint);
            }
        }
    }

    private TextView directed(TextView view) {
        view.setTextDirection(arabic ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
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
