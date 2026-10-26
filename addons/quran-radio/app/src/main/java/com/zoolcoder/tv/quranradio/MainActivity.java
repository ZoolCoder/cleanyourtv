package com.zoolcoder.tv.quranradio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * panel, drawn with shapes and text only, on the other.
 */
public class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFF0B1A12, CARD = 0xFF16281D, RAISED = 0xFF20382A, DIVIDER = 0xFF294235,
            ACCENT = 0xFFFFC542, MUTED = 0xFF9DB8A8, DISABLED = 0xFF53665B;
    private static final float FOCUS_SCALE = 1.03f;
    private static final int RADIO = 0, RECITERS = 1;
    private static final int IDLE = 0, LOADING = 1, LOADED = 2, FAILED = 3;

    private final TextView[] tabs = new TextView[2];
    private final List<Catalog.Station> stations = new ArrayList<>();
    private final List<Catalog.Reciter> reciters = new ArrayList<>();
    private String[] surahNames = new String[115];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView hint, liveLabel, panelName, panelSurah, status, statusDetail, clock;
    private View liveDot;
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
    private String playingKey, playingName, playingSurah;
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
        page.setPadding(dp(48), dp(28), dp(48), dp(28));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        marker.setBackground(shape(ACCENT, 3, 0, 0));
        header.addView(marker, new LinearLayout.LayoutParams(dp(6), dp(28)));
        TextView title = directed(text(getString(R.string.title), 28, Color.WHITE));
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(12), 0, dp(12), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs[RADIO] = tab(getString(R.string.tab_radio), RADIO);
        tabs[RECITERS] = tab(getString(R.string.tab_reciters), RECITERS);
        header.addView(tabs[RADIO]);
        header.addView(tabs[RECITERS]);
        page.addView(header);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll = new ScrollView(this);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        listScroll.addView(list);
        LinearLayout body = new LinearLayout(this);
        body.addView(listScroll, new LinearLayout.LayoutParams(dp(392), LinearLayout.LayoutParams.MATCH_PARENT));
        View gap = new View(this);
        body.addView(gap, new LinearLayout.LayoutParams(dp(24), 1));
        body.addView(buildPanel(), new LinearLayout.LayoutParams(dp(448), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = dp(16);
        page.addView(body, bodyParams);

        hint = directed(text(getString(R.string.hint), 16, MUTED));
        hint.setPadding(0, dp(12), 0, 0);
        page.addView(hint);
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

    private void play(String url, String key, String name, String surah, boolean isLive) {
        release();
        playingKey = key;
        playingName = name;
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
        play(station.url, "s:" + station.url, station.name, null, true);
    }

    private void playSurah(Catalog.Reciter reciter, int index) {
        queueReciter = reciter;
        queueIndex = index;
        int surah = reciter.surahs[index];
        play(Catalog.surahUrl(reciter, surah), surahKey(reciter, surah), reciter.name, surahTitle(surah), false);
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
        TextView tab = directed(text(label, 18, Color.WHITE));
        tab.setTypeface(null, Typeface.BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setMinHeight(dp(48));
        tab.setPadding(dp(24), dp(8), dp(24), dp(8));
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setOnFocusChangeListener(SCALE_ON_FOCUS);
        tab.setOnClickListener(v -> selectTab(index));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(12));
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

    /** The chosen tab is filled with the accent; the focused one also has the white and accent ring. */
    private void showTabs() {
        for (int i = 0; i < tabs.length; i++) {
            boolean chosen = i == tab;
            tabs[i].setBackground(focusable(chosen ? ACCENT : CARD, 24, chosen ? 0 : 1));
            tabs[i].setTextColor(chosen ? BACKGROUND : Color.WHITE);
        }
    }

    private void showList(boolean keepTabFocus) {
        list.removeAllViews();
        View focus = null;
        if (openReciter != null) {
            hint.setText(R.string.hint_surahs);
            list.addView(directed(text(openReciter.name + " · " + openReciter.recitation, 18, MUTED)));
            for (int i = 0; i < openReciter.surahs.length; i++) {
                int index = i;
                int surah = openReciter.surahs[i];
                String key = surahKey(openReciter, surah);
                View row = row(surahTitle(surah), null, key, v -> playSurah(openReciter, index));
                list.addView(row);
                if (focus == null || key.equals(playingKey)) focus = row;
            }
        } else {
            hint.setText(R.string.hint);
            if (tab == RADIO) {
                for (Catalog.Station station : stations) {
                    list.addView(row(station.name, null, "s:" + station.url, v -> playStation(station)));
                }
            } else if (loadState == LOADING) {
                list.addView(directed(text(getString(R.string.loading), 18, MUTED)));
            } else if (loadState == FAILED) {
                list.addView(directed(text(getString(R.string.failed), 18, MUTED)));
            } else {
                for (int i = 0; i < reciters.size(); i++) {
                    int index = i;
                    Catalog.Reciter reciter = reciters.get(i);
                    list.addView(row(reciter.name, reciter.recitation, "c:" + reciter.id + reciter.server,
                            v -> {
                                reciterFocus = index;
                                openReciter = reciter;
                                showList(false);
                            }));
                }
            }
            if (list.getChildCount() == 0) list.addView(directed(text(getString(R.string.empty), 18, MUTED)));
            if (tab == RECITERS && loadState == LOADED && reciterFocus < list.getChildCount()) {
                focus = list.getChildAt(reciterFocus);
            } else {
                focus = firstRow();
            }
        }
        refreshMarks();
        listScroll.scrollTo(0, 0);
        if (!keepTabFocus && focus != null) focus.requestFocus();
    }

    private View firstRow() {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) return list.getChildAt(i);
        }
        return null;
    }

    private View row(String title, String subtitle, String key, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(subtitle == null ? 56 : 64));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setBackground(focusable(CARD, 10, 1));
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(key);
        row.setOnFocusChangeListener(SCALE_ON_FOCUS);

        TextView mark = text("", 16, ACCENT);
        mark.setGravity(Gravity.CENTER);
        row.addView(mark, new LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView label = directed(text(title, 20, Color.WHITE));
        label.setTag(title);
        words.addView(label);
        if (subtitle != null) words.addView(directed(text(subtitle, 16, MUTED)));
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            if (key.equals(playingKey) && player != null && ready) togglePause();
            else onClick.onClick(v);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(params);
        return row;
    }

    /** The row that is playing is gold with a dot, hollow when paused. */
    private void refreshMarks() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (!(row instanceof ViewGroup) || row.getTag() == null) continue;
            ViewGroup group = (ViewGroup) row;
            TextView mark = (TextView) group.getChildAt(0);
            TextView label = (TextView) ((ViewGroup) group.getChildAt(1)).getChildAt(0);
            boolean current = row.getTag().equals(playingKey);
            mark.setText(!current ? "" : paused ? "○" : "●");
            label.setTextColor(current ? ACCENT : Color.WHITE);
        }
    }

    // ---- now-playing panel ----

    private View buildPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        panel.setBackground(shape(CARD, 14, 0, 0));

        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setGravity(Gravity.CENTER_VERTICAL);
        liveDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        liveDot.setBackground(dot);
        liveRow.addView(liveDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        liveLabel = directed(text(getString(R.string.live), 18, ACCENT));
        liveLabel.setTypeface(null, Typeface.BOLD);
        liveLabel.setPadding(dp(8), 0, 0, 0);
        liveRow.addView(liveLabel);
        panel.addView(liveRow);

        panelName = directed(text("", 28, Color.WHITE));
        panelName.setTypeface(null, Typeface.BOLD);
        panelName.setPadding(0, dp(8), 0, 0);
        panel.addView(panelName);
        panelSurah = directed(text("", 22, ACCENT));
        panelSurah.setPadding(0, dp(4), 0, 0);
        panel.addView(panelSurah);

        playButton = new PlayButton(this);
        playButton.setOnClickListener(v -> togglePause());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
        buttonParams.topMargin = dp(24);
        panel.addView(playButton, buttonParams);

        status = text("", 17, Color.WHITE);
        status.setTypeface(null, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        panel.addView(status);
        statusDetail = text(getString(R.string.offline_hint), 16, MUTED);
        statusDetail.setGravity(Gravity.CENTER);
        panel.addView(statusDetail);

        progress = new LinearLayout(this);
        progress.setBackground(shape(DIVIDER, 4, 0, 0));
        progressFill = new View(this);
        progressFill.setBackground(shape(ACCENT, 4, 0, 0));
        progressRest = new View(this);
        progress.addView(progressFill, new LinearLayout.LayoutParams(0, dp(8), 0f));
        progress.addView(progressRest, new LinearLayout.LayoutParams(0, dp(8), 1f));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        barParams.topMargin = dp(24);
        panel.addView(progress, barParams);
        clock = text("", 16, MUTED);
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
        liveLabel.setVisibility(active ? View.VISIBLE : View.GONE);
        liveLabel.setText(live ? R.string.live : R.string.recitation);
        liveDot.setVisibility(active && live && !paused ? View.VISIBLE : View.GONE);
        panelName.setText(playingName == null ? getString(R.string.idle) : playingName);
        panelSurah.setText(playingSurah);
        panelSurah.setVisibility(surah ? View.VISIBLE : View.GONE);
        playButton.setState(ready && !paused, active && ready);
        if (connecting) {
            status.setText(R.string.connecting);
            status.setTextColor(MUTED);
        } else if (offline) {
            status.setText("× " + getString(R.string.state_offline));
            status.setTextColor(Color.WHITE);
        } else if (active) {
            status.setText(paused ? "○ " + getString(R.string.state_paused) : "● " + getString(R.string.state_playing));
            status.setTextColor(paused ? Color.WHITE : ACCENT);
        } else {
            status.setText("");
        }
        statusDetail.setVisibility(offline ? View.VISIBLE : View.GONE);
        int surahBar = surah && ready ? View.VISIBLE : View.GONE;
        progress.setVisibility(surahBar);
        clock.setVisibility(surahBar);
        handler.removeCallbacks(tick);
        if (surahBar == View.VISIBLE) tick.run();
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

    private static final View.OnFocusChangeListener SCALE_ON_FOCUS = (v, focused) -> {
        float scale = focused ? FOCUS_SCALE : 1f;
        v.setScaleX(scale);
        v.setScaleY(scale);
    };

    private GradientDrawable shape(int fill, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (strokeWidth > 0) shape.setStroke(dp(strokeWidth), strokeColor);
        return shape;
    }

    /**
     * Idle, with an optional divider line, or focused: the surface lightens, with a 2 dp accent stroke inside a
     * 1 dp white one. The state list is built with the view, so a focus change allocates nothing.
     */
    private Drawable focusable(int fill, int radius, int dividerWidth) {
        boolean raised = fill == CARD;
        GradientDrawable outer = shape(Color.TRANSPARENT, radius, 1, Color.WHITE);
        GradientDrawable inner = shape(raised ? RAISED : fill, radius - 1, 2, ACCENT);
        LayerDrawable focused = new LayerDrawable(new Drawable[] {outer, inner});
        focused.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[0], shape(fill, radius, dividerWidth, DIVIDER));
        return states;
    }

    /** The 64 dp round control: a play triangle or two pause bars on the accent, grey when nothing plays. */
    private final class PlayButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private boolean playing, enabledLook;

        PlayButton(Activity context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            setOnFocusChangeListener(SCALE_ON_FOCUS);
        }

        void setState(boolean playing, boolean enabledLook) {
            this.playing = playing;
            this.enabledLook = enabledLook;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float c = getWidth() / 2f;
            if (isFocused()) {
                paint.setColor(Color.WHITE);
                canvas.drawCircle(c, c, c, paint);
                paint.setColor(BACKGROUND);
                canvas.drawCircle(c, c, c - dp(1), paint);
            }
            paint.setColor(enabledLook ? ACCENT : DISABLED);
            canvas.drawCircle(c, c, c - dp(4), paint);
            paint.setColor(BACKGROUND);
            float u = getWidth() / 64f;
            if (playing) {
                canvas.drawRect(c - 9 * u, c - 11 * u, c - 3 * u, c + 11 * u, paint);
                canvas.drawRect(c + 3 * u, c - 11 * u, c + 9 * u, c + 11 * u, paint);
            } else {
                triangle.reset();
                triangle.moveTo(c - 7 * u, c - 12 * u);
                triangle.lineTo(c + 11 * u, c);
                triangle.lineTo(c - 7 * u, c + 12 * u);
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
