package com.zoolcoder.tv.kids;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One row per channel in config.xml with its latest videos, after a Continue watching row. OK opens the video in
 * the TV's YouTube app; nothing is played here. The feeds and thumbnails are read when the app opens, and again
 * when it comes back after a quarter of an hour; nothing runs in the background. The last good feed of each
 * channel is kept in a file, so the rows show at once and still show when the internet is down.
 */
public class MainActivity extends Activity {
    /** YouTube apps in order of preference; the first one installed gets the link. */
    private static final String[] PLAYERS = {"com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.teamsmart.videomanager.tv"};
    private static final int BACKGROUND = 0xFF0B1A12, SURFACE = 0x9916281D, SURFACE_FOCUSED = 0xFF2C4838;
    private static final int TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF;
    private static final int ACCENT = 0xFFB65CFF, GLOW = 0x26B65CFF, DARK_TEXT = 0xFF0B1A12;
    private static final int SELECTED_FILL = 0x40B65CFF, FOCUS_STROKE = 0xFFFFFFFF, CHIP_FILL = 0x29FFFFFF;
    private static final int PLACEHOLDER = 0x33FFFFFF;
    /** Flat circle colours for the channel letters, one per row in turn; all bright enough for dark text. */
    private static final int[] BADGES = {0xFFFF8FB1, 0xFF5CC8FF, 0xFFFFC542, 0xFF7CE0A0, 0xFFB65CFF, 0xFFFF9B6B,
            0xFFE8E26B};
    private static final int THUMB_WIDTH_DP = 160, THUMB_HEIGHT_DP = 90, CARD_HEIGHT_DP = 148;
    private static final int LABEL_WIDTH_DP = 184, SHELF_TITLE_DP = 28, SHELF_PADDING_DP = 16;
    private static final int RADIUS_DP = 14, PLACEHOLDERS = 4;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_IMAGE_BYTES = 512 * 1024;
    private static final long STALE_MS = 15 * DateUtils.MINUTE_IN_MILLIS;
    private static final String KEPT_FILE = "feeds.txt", PREFS = "kids";
    private static final String PREF_FAVOURITES = "favourites", PREF_RECENT = "recent", PREF_HINTED = "hinted";

    /** An eighth of the app's heap, between 4 and 12 MB: about 100 thumbnails at RGB_565. */
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(
            (int) Math.max(4L << 20, Math.min(12L << 20, Runtime.getRuntime().maxMemory() / 8))) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    /** Pictures waiting for a url that is already being fetched; touched on the main thread only. */
    private final Map<String, List<ImageView>> waiting = new HashMap<>();
    private final ViewOutlineProvider rounding = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RADIUS_DP));
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The last good feed of each channel, by channel id; touched on the main thread only. */
    private final Map<String, List<Channel.Video>> kept = new LinkedHashMap<>();
    private final List<Shelf> shelves = new ArrayList<>();
    private ExecutorService feedPool, thumbnailPool;
    private Drawable.ConstantState ringState;
    private SharedPreferences prefs;
    private Set<String> favourites;
    private Recent recent;
    private Shelf selected, recentShelf;
    private TextView hintOk, hintBack;
    private LinearLayout rows;
    private View banner;
    private TextView status;
    private boolean arabic, moved, freshAny, keptChanged;
    private long loadedAt;
    private int loadId, rowCount, pendingFeeds;
    /** The card that opened YouTube, found again when the app comes back: its channel (null: Continue watching). */
    private String restoreChannel, restoreVideo;

    /** One row: the rail entry, the shelf title with its Favourite pill, and the strip of cards. */
    private static final class Shelf {
        Channel channel;
        LinearLayout row, strip;
        Rail label;
        TextView pill;
        boolean hasVideos;
        Runnable timeout;
    }

    /** A rail entry: its own focus target, and the dot and tint when the channel's shelf is the one in view. */
    private static final class Rail extends LinearLayout {
        View dot;
        TextView heading;

        Rail(Activity context) {
            super(context);
        }
    }

    private static final class Card extends LinearLayout {
        String videoId;

        Card(Activity context) {
            super(context);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        arabic = "ar".equals(Locale.getDefault().getLanguage());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        favourites = new HashSet<>(prefs.getStringSet(PREF_FAVOURITES, Collections.emptySet()));
        recent = Recent.read(prefs.getString(PREF_RECENT, null));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        page.setPaddingRelative(dp(48), dp(28), dp(48), dp(24));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setBackground(background());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        View marker = new View(this);
        marker.setBackground(rounded(ACCENT, 2));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(4), dp(26));
        markerParams.setMarginEnd(dp(12));
        header.addView(marker, markerParams);
        header.addView(bold(directed(text(getString(R.string.title), 30, Color.WHITE))));
        page.addView(header);
        status = directed(text(getString(R.string.loading), 15, TEXT_2));
        status.setPadding(0, dp(4), 0, 0);
        page.addView(status);
        if (!prefs.getBoolean(PREF_HINTED, false)) {
            banner = firstRunBanner();
            page.addView(banner);
        }

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        ScrollView scroll = new ScrollView(this);
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        scroll.setClipToOutline(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPaddingRelative(dp(12), dp(8), dp(12), dp(12));
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        page.addView(hintBar());
        setContentView(page);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (loadedAt == 0 || SystemClock.elapsedRealtime() - loadedAt > STALE_MS) load();
    }

    /** Back from YouTube: the Continue watching row has the video at its front, and focus goes back to the card. */
    @Override
    protected void onResume() {
        super.onResume();
        if (restoreVideo == null || recentShelf == null) return;
        fillRecent();
        restoreFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (banner != null && (key == KeyEvent.KEYCODE_BACK || isOk(key))) {
            if (event.getAction() == KeyEvent.ACTION_UP) dismissBanner();
            return true;
        }
        // Menu brings the banner back, for a grandparent who put it away too soon.
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP && banner == null) showBanner();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            moved = true;
            if (move(key)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isOk(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * The moves Android's nearest-view search gets wrong here: Up and Down from a card go to the nearest card of
     * the next row, not to the Favourite pill above; the pill is reached with Right (Left in Arabic) from the rail.
     */
    private boolean move(int key) {
        View focused = getCurrentFocus();
        if (focused == null || !(focused.getTag() instanceof Shelf)) return false;
        Shelf shelf = (Shelf) focused.getTag();
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP, down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        int endKey = arabic ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        int startKey = arabic ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        if (focused.getParent() == shelf.strip && (up || down)) {
            Shelf next = neighbour(shelf, up ? -1 : 1);
            if (next != null) nearest(next.strip, centreX(focused)).requestFocus();
            return true;
        }
        if (focused == shelf.label && key == endKey && shelf.pill != null
                && shelf.pill.getVisibility() == View.VISIBLE) {
            shelf.pill.requestFocus();
            return true;
        }
        if (focused == shelf.pill) {
            View first = firstFocusable(shelf.strip);
            if ((down || key == endKey) && first != null) first.requestFocus();
            else if (key == startKey) shelf.label.requestFocus();
            else if (up && neighbour(shelf, -1) != null) neighbour(shelf, -1).label.requestFocus();
            return up || down || key == endKey || key == startKey;
        }
        return false;
    }

    /** The nearest shown row above (-1) or below (1) with something focusable in its strip, or null. */
    private Shelf neighbour(Shelf shelf, int step) {
        for (int i = rows.indexOfChild(shelf.row) + step; i >= 0 && i < rows.getChildCount(); i += step) {
            View row = rows.getChildAt(i);
            Shelf other = (Shelf) row.getTag();
            if (row.getVisibility() == View.VISIBLE && firstFocusable(other.strip) != null) return other;
        }
        return null;
    }

    private static View firstFocusable(ViewGroup strip) {
        for (int i = 0; i < strip.getChildCount(); i++) {
            if (strip.getChildAt(i).isFocusable()) return strip.getChildAt(i);
        }
        return null;
    }

    private static View nearest(ViewGroup strip, int x) {
        View best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View child = strip.getChildAt(i);
            if (!child.isFocusable()) continue;
            int distance = Math.abs(centreX(child) - x);
            if (distance < bestDistance) {
                best = child;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int centreX(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[0] + view.getWidth() / 2;
    }

    /** Back from anything in a row goes to its channel in the rail; Back from the rail leaves the app. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View focused = getCurrentFocus();
        if (keyCode == KeyEvent.KEYCODE_BACK && focused != null && focused.getTag() instanceof Shelf
                && focused != ((Shelf) focused.getTag()).label) {
            ((Shelf) focused.getTag()).label.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (feedPool != null) feedPool.shutdownNow();
        if (thumbnailPool != null) thumbnailPool.shutdownNow();
        thumbnails.evictAll();
        waiting.clear();
    }

    private void load() {
        int id = ++loadId;
        if (feedPool != null) feedPool.shutdownNow();
        if (thumbnailPool != null) thumbnailPool.shutdownNow();
        feedPool = Executors.newFixedThreadPool(3);
        thumbnailPool = Executors.newFixedThreadPool(3);
        handler.removeCallbacksAndMessages(null);
        waiting.clear();
        status.setText(R.string.loading);
        rows.removeAllViews();
        shelves.clear();
        rowCount = 0;
        pendingFeeds = 0;
        selected = null;
        moved = restoreVideo != null;
        freshAny = false;

        recentShelf = addShelf(null, getString(R.string.continue_watching_en),
                getString(R.string.continue_watching_ar), ACCENT, "▶");
        fillRecent();
        List<Channel> channels = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.channels)) {
            Channel channel = Channel.parse(line);
            if (channel != null) channels.add(channel);
        }
        for (Channel channel : Channel.favouritesFirst(channels, favourites)) {
            Shelf shelf = addShelf(channel, channel.englishName, channel.arabicName,
                    BADGES[rowCount++ % BADGES.length], null);
            shelves.add(shelf);
            placeholders(shelf);
        }
        if (shelves.isEmpty()) {
            status.setText(R.string.failed);
            return;
        }
        // The saved feeds first, read off the main thread; then every channel is asked for its latest.
        feedPool.execute(() -> {
            Map<String, List<Channel.Video>> saved = readKept();
            runOnUiThread(() -> {
                if (id != loadId || isDestroyed()) return;
                kept.clear();
                kept.putAll(saved);
                for (Shelf shelf : shelves) {
                    List<Channel.Video> videos = kept.get(shelf.channel.id);
                    if (videos != null && !videos.isEmpty()) fill(shelf, videos, id);
                }
                for (Shelf shelf : shelves) fetch(shelf, id);
            });
        });
    }

    /** Asks the channel for its feed; after TIMEOUT_MS with nothing on the shelf, it says so and offers Try again. */
    private void fetch(Shelf shelf, int id) {
        pendingFeeds++;
        if (!shelf.hasVideos) placeholders(shelf);
        handler.removeCallbacks(shelf.timeout);
        shelf.timeout = () -> {
            if (id == loadId && !shelf.hasVideos) unavailable(shelf);
        };
        handler.postDelayed(shelf.timeout, TIMEOUT_MS);
        Channel channel = shelf.channel;
        feedPool.execute(() -> {
            List<Channel.Video> videos = null;
            try {
                videos = channel.fetch();
            } catch (Exception ignored) {
                // One channel down leaves the others on screen.
            }
            List<Channel.Video> result = videos;
            runOnUiThread(() -> {
                if (id != loadId || isDestroyed()) return;
                handler.removeCallbacks(shelf.timeout);
                if (result != null && !result.isEmpty()) {
                    kept.put(channel.id, result);
                    keptChanged = true;
                    freshAny = true;
                    fill(shelf, result, id);
                } else if (!shelf.hasVideos) {
                    unavailable(shelf);
                }
                if (--pendingFeeds == 0) done();
            });
        });
    }

    private void done() {
        if (keptChanged) {
            keptChanged = false;
            String text = Kept.write(new LinkedHashMap<>(kept));
            feedPool.execute(() -> writeAtomically(KEPT_FILE, text));
        }
        boolean anyShown = false;
        for (Shelf shelf : shelves) anyShown |= shelf.hasVideos;
        if (freshAny) {
            loadedAt = SystemClock.elapsedRealtime();
            status.setText("");
        } else {
            status.setText(anyShown ? R.string.offline : R.string.failed);
        }
    }

    /** The saved feeds, or none when the file is missing, too large or unreadable. */
    private Map<String, List<Channel.Video>> readKept() {
        File file = new File(getFilesDir(), KEPT_FILE);
        if (!file.isFile() || file.length() > Kept.MAX_BYTES) return Collections.emptyMap();
        try (InputStream in = new FileInputStream(file)) {
            return Kept.read(new String(Http.readCapped(in, Kept.MAX_BYTES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    /** Writes a temporary file and renames it over the old one, so a power cut leaves the old file or the new. */
    private void writeAtomically(String name, String text) {
        File file = new File(getFilesDir(), name);
        File temporary = new File(getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(file)) temporary.delete();
    }

    /** A rail entry and a shelf for the videos; the row keeps its height as videos fill in. */
    private Shelf addShelf(Channel channel, String englishName, String arabicName, int badgeColor, String letter) {
        Shelf shelf = new Shelf();
        shelf.channel = channel;
        String name = arabic ? arabicName : englishName;
        String other = arabic ? englishName : arabicName;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setTag(shelf);
        shelf.row = row;
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        rows.addView(row, rowParams);

        if (letter == null) letter = name.isEmpty() ? "" : name.substring(0, name.offsetByCodePoints(0, 1));
        Rail label = label(shelf, name, other.equals(name) ? "" : other, badgeColor, letter);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                dp(LABEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(SHELF_TITLE_DP + SHELF_PADDING_DP + THUMB_HEIGHT_DP / 2 - 30);
        labelParams.setMarginEnd(dp(12));
        row.addView(label, labelParams);

        LinearLayout shelfView = new LinearLayout(this);
        shelfView.setOrientation(LinearLayout.VERTICAL);
        shelfView.setClipChildren(false);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setClipChildren(false);
        heading.setPaddingRelative(dp(SHELF_PADDING_DP), 0, 0, 0);
        TextView title = directed(text(name, 20, Color.WHITE));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (channel != null) {
            shelf.pill = favouritePill(shelf);
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pillParams.setMarginStart(dp(12));
            heading.addView(shelf.pill, pillParams);
        }
        shelfView.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(SHELF_TITLE_DP)));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setClipChildren(false);
        shelf.strip = strip;
        HorizontalScrollView holder = new HorizontalScrollView(this);
        holder.setHorizontalScrollBarEnabled(false);
        // Children may draw into the padding, but nothing past the scroll view's own edges.
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        holder.setClipToOutline(true);
        holder.setPaddingRelative(dp(SHELF_PADDING_DP), dp(SHELF_PADDING_DP), dp(SHELF_PADDING_DP),
                dp(SHELF_PADDING_DP));
        holder.addView(strip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(CARD_HEIGHT_DP)));
        shelfView.addView(holder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(CARD_HEIGHT_DP + 2 * SHELF_PADDING_DP)));
        row.addView(shelfView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return shelf;
    }

    /** A rail entry: a round avatar with the channel's first letter, its name and the name in the other script. */
    private Rail label(Shelf shelf, String name, String otherName, int badgeColor, String letterText) {
        Rail label = new Rail(this);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPaddingRelative(dp(10), dp(10), dp(12), dp(10));
        label.setBackground(railBackground());
        label.setFocusable(true);
        label.setClickable(true);
        label.setTag(shelf);
        shelf.label = label;

        TextView letter = text(letterText, 18, DARK_TEXT);
        bold(letter).setGravity(Gravity.CENTER);
        GradientDrawable circle = rounded(badgeColor, 0);
        circle.setShape(GradientDrawable.OVAL);
        letter.setBackground(circle);
        label.addView(letter, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView heading = directed(text(name, 19, Color.WHITE));
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        // Two lines: the rail is 184 dp, too narrow for "Toyor Al Janah" on one.
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setTag(name);
        label.heading = heading;
        names.addView(heading);
        if (!otherName.isEmpty()) {
            TextView second = directed(text(otherName, 15, TEXT_2));
            second.setMaxLines(1);
            second.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(second);
        }
        LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        namesParams.setMarginStart(dp(10));
        label.addView(names, namesParams);

        View dot = new View(this);
        GradientDrawable dotShape = rounded(ACCENT, 0);
        dotShape.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotShape);
        dot.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginStart(dp(6));
        label.addView(dot, dotParams);
        label.dot = dot;
        showStar(shelf, label);

        label.setOnFocusChangeListener((v, focused) -> {
            lift(v, focused, 1.04f);
            if (focused) {
                markSelected(shelf);
                setHint(R.string.hint_videos, R.string.hint_home);
            }
        });
        label.setOnClickListener(v -> {
            View first = firstFocusable(shelf.strip);
            if (first != null) first.requestFocus();
        });
        return label;
    }

    /** A favourite channel's rail name starts with a star. */
    private void showStar(Shelf shelf, Rail label) {
        String name = (String) label.heading.getTag();
        boolean favourite = shelf.channel != null && favourites.contains(shelf.channel.id);
        label.heading.setText(favourite ? "★ " + name : name);
    }

    /** The shelf title's Favourite pill, shown on the shelf in view only; OK adds or removes the channel. */
    private TextView favouritePill(Shelf shelf) {
        TextView pill = bold(text("", 13, Color.WHITE));
        pill.setPadding(dp(10), dp(2), dp(10), dp(2));
        GradientDrawable focused = rounded(SURFACE_FOCUSED, 20);
        focused.setStroke(dp(2), FOCUS_STROKE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], rounded(CHIP_FILL, 20));
        pill.setBackground(states);
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setTag(shelf);
        pill.setVisibility(View.INVISIBLE);
        showFavourite(shelf, pill);
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            lift(v, hasFocus, 1.04f);
            if (hasFocus) showFavouriteHint(shelf);
        });
        pill.setOnClickListener(v -> toggleFavourite(shelf));
        return pill;
    }

    private void showFavourite(Shelf shelf, TextView pill) {
        boolean favourite = favourites.contains(shelf.channel.id);
        pill.setText(favourite ? R.string.favourite_on : R.string.favourite_off);
        pill.setTextColor(favourite ? 0xFFFFD45C : Color.WHITE);
    }

    private void showFavouriteHint(Shelf shelf) {
        setHint(favourites.contains(shelf.channel.id) ? R.string.hint_unfavourite : R.string.hint_favourite,
                R.string.hint_channels);
    }

    /** Saves the change and moves the favourite rows to the top of the rail, keeping every view and its videos. */
    private void toggleFavourite(Shelf shelf) {
        String id = shelf.channel.id;
        if (!favourites.remove(id)) favourites.add(id);
        prefs.edit().putStringSet(PREF_FAVOURITES, new HashSet<>(favourites)).apply();
        showFavourite(shelf, shelf.pill);
        showStar(shelf, shelf.label);
        showFavouriteHint(shelf);

        List<Channel> channels = new ArrayList<>();
        Map<String, Shelf> byId = new HashMap<>();
        for (Shelf each : shelves) {
            channels.add(each.channel);
            byId.put(each.channel.id, each);
        }
        // The rail's own order is config.xml order within each group, so sort the config order, not the screen's.
        List<Channel> config = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.channels)) {
            Channel channel = Channel.parse(line);
            if (channel != null && byId.containsKey(channel.id)) config.add(channel);
        }
        shelves.clear();
        for (Channel channel : Channel.favouritesFirst(config, favourites)) {
            Shelf each = byId.get(channel.id);
            shelves.add(each);
            rows.removeView(each.row);
            rows.addView(each.row);
        }
        shelf.pill.requestFocus();
    }

    /** The rail entry of the shelf in view gets the accent tint and the dot; its title shows the Favourite pill. */
    private void markSelected(Shelf shelf) {
        if (shelf == selected) return;
        if (selected != null) select(selected, false);
        select(shelf, true);
        selected = shelf;
    }

    private static void select(Shelf shelf, boolean on) {
        shelf.label.setSelected(on);
        shelf.label.dot.setVisibility(on ? View.VISIBLE : View.GONE);
        if (shelf.pill != null) shelf.pill.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    /** The once-only banner: what the remote's keys do. OK or Back puts it away for good. */
    private View firstRunBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(16), dp(10), dp(20), dp(10));
        GradientDrawable fill = rounded(SELECTED_FILL, 18);
        fill.setStroke(dp(1), 0x66B65CFF);
        bar.setBackground(fill);
        bar.addView(text("👋", 18, Color.WHITE));
        addKey(bar, getString(R.string.key_ok), R.string.banner_play, true);
        addKey(bar, "▲▼", R.string.banner_channel, false);
        addKey(bar, getString(R.string.key_back), R.string.banner_return, false);
        TextView dismiss = directed(text(getString(R.string.banner_dismiss), 14, TEXT_3));
        dismiss.setPaddingRelative(dp(24), 0, 0, 0);
        bar.addView(dismiss);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        params.setMarginStart(dp(12));
        bar.setLayoutParams(params);
        return bar;
    }

    private void addKey(LinearLayout bar, String key, int label, boolean first) {
        if (!first) {
            TextView dot = text("·", 14, TEXT_3);
            dot.setPadding(dp(12), 0, 0, 0);
            bar.addView(dot);
        }
        bar.addView(chip(key));
        TextView text = directed(text(getString(label), 15, Color.WHITE));
        text.setPadding(dp(6), 0, 0, 0);
        bar.addView(text);
    }

    private void showBanner() {
        ViewGroup page = (ViewGroup) status.getParent();
        banner = firstRunBanner();
        page.addView(banner, page.indexOfChild(status) + 1);
    }

    private void dismissBanner() {
        ((ViewGroup) banner.getParent()).removeView(banner);
        banner = null;
        prefs.edit().putBoolean(PREF_HINTED, true).apply();
    }

    /** Focus: scale and elevation only while focused; the outline comes from the view's own background. */
    private void lift(View view, boolean focused, float scale) {
        float to = focused ? scale : 1f;
        view.animate().scaleX(to).scaleY(to).setDuration(150).start();
        view.setElevation(focused ? dp(8) : 0);
    }

    /** Rail background, built once per entry: plain, tinted when selected, outlined when focused. */
    private Drawable railBackground() {
        GradientDrawable focused = rounded(SURFACE_FOCUSED, RADIUS_DP);
        focused.setStroke(dp(2), FOCUS_STROKE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, rounded(SELECTED_FILL, RADIUS_DP));
        states.addState(new int[0], rounded(SURFACE, RADIUS_DP));
        return states;
    }

    private View hintBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, 0);
        bar.addView(chip(getString(R.string.key_ok)));
        hintOk = hintLabel();
        bar.addView(hintOk);
        TextView dot = text("·", 14, TEXT_3);
        dot.setPadding(dp(12), 0, 0, 0);
        bar.addView(dot);
        bar.addView(chip(getString(R.string.key_back)));
        hintBack = hintLabel();
        bar.addView(hintBack);
        TextView helpDot = text("·", 14, TEXT_3);
        helpDot.setPadding(dp(12), 0, 0, 0);
        bar.addView(helpDot);
        bar.addView(chip("≡"));
        TextView help = hintLabel();
        help.setText(R.string.hint_help);
        bar.addView(help);
        setHint(R.string.hint_play, R.string.hint_channels);
        return bar;
    }

    private TextView chip(String key) {
        TextView chip = bold(text(key, 12, Color.WHITE));
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        chip.setBackground(rounded(CHIP_FILL, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(12));
        chip.setLayoutParams(params);
        return chip;
    }

    private TextView hintLabel() {
        TextView label = directed(text("", 14, TEXT_2));
        label.setPadding(dp(6), 0, 0, 0);
        return label;
    }

    private void setHint(int ok, int back) {
        hintOk.setText(ok);
        hintBack.setText(back);
    }

    /** Grey cards the size of real ones while the feed loads, so nothing jumps when the videos arrive. */
    private void placeholders(Shelf shelf) {
        boolean hadFocus = hasFocusIn(shelf.strip);
        shelf.strip.removeAllViews();
        for (int i = 0; i < PLACEHOLDERS; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            View picture = new View(this);
            picture.setBackground(rounded(PLACEHOLDER, RADIUS_DP));
            card.addView(picture, new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(THUMB_HEIGHT_DP)));
            View line = new View(this);
            line.setBackground(rounded(PLACEHOLDER, 4));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP * 3 / 4), dp(12));
            lineParams.topMargin = dp(10);
            card.addView(line, lineParams);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(CARD_HEIGHT_DP));
            params.setMarginEnd(dp(16));
            shelf.strip.addView(card, params);
        }
        if (hadFocus) shelf.label.requestFocus();
    }

    /** No videos after the wait and none saved: one line and a Try again button, which asks this channel again. */
    private void unavailable(Shelf shelf) {
        boolean hadFocus = hasFocusIn(shelf.strip);
        shelf.strip.removeAllViews();
        TextView note = directed(text(getString(R.string.unavailable), 16, TEXT_2));
        note.setPaddingRelative(dp(4), 0, dp(16), 0);
        shelf.strip.addView(note);
        TextView retry = bold(text(getString(R.string.try_again), 15, Color.WHITE));
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable focused = rounded(SURFACE_FOCUSED, 20);
        focused.setStroke(dp(2), FOCUS_STROKE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], rounded(CHIP_FILL, 20));
        retry.setBackground(states);
        retry.setFocusable(true);
        retry.setClickable(true);
        retry.setTag(shelf);
        retry.setOnFocusChangeListener((v, hasFocus) -> {
            lift(v, hasFocus, 1.04f);
            if (hasFocus) {
                markSelected(shelf);
                setHint(R.string.hint_retry, R.string.hint_channels);
            }
        });
        retry.setOnClickListener(v -> {
            shelf.label.requestFocus();
            status.setText(R.string.loading);
            fetch(shelf, loadId);
        });
        shelf.strip.addView(retry);
        if (hadFocus) retry.requestFocus();
    }

    private boolean hasFocusIn(ViewGroup strip) {
        View focused = getCurrentFocus();
        return focused != null && focused.getParent() == strip;
    }

    private void fill(Shelf shelf, List<Channel.Video> videos, int id) {
        View focused = getCurrentFocus();
        String focusedVideo = focused instanceof Card && focused.getParent() == shelf.strip
                ? ((Card) focused).videoId : null;
        boolean hadFocus = hasFocusIn(shelf.strip);
        shelf.strip.removeAllViews();
        shelf.hasVideos = true;
        String channelId = shelf.channel.id;
        for (Channel.Video video : videos) shelf.strip.addView(card(shelf, video, channelId, id));
        if (hadFocus) {
            View same = cardFor(shelf, focusedVideo);
            (same != null ? same : shelf.strip.getChildAt(0)).requestFocus();
            return;
        }
        if (restoreVideo != null && channelId.equals(restoreChannel) && restoreFocus()) return;
        // Rows fill in any order: until the first key press, focus stays on the top-most row that has videos.
        View first = shelf.strip.getChildAt(0);
        first.post(() -> {
            View focusedRow = rows.getFocusedChild();
            if (!moved && (focusedRow == null || rows.indexOfChild(shelf.row) < rows.indexOfChild(focusedRow))) {
                first.requestFocus();
            }
        });
    }

    /** Continue watching: the last videos opened, newest first; the row is hidden while there are none. */
    private void fillRecent() {
        List<Recent.Entry> entries = recent.entries();
        recentShelf.row.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        recentShelf.strip.removeAllViews();
        recentShelf.hasVideos = !entries.isEmpty();
        for (Recent.Entry entry : entries) {
            recentShelf.strip.addView(card(recentShelf, entry.video, entry.channelId, loadId));
        }
        if (!entries.isEmpty() && !moved) {
            View first = recentShelf.strip.getChildAt(0);
            first.post(() -> {
                if (!moved) first.requestFocus();
            });
        }
    }

    /** Focus back on the card that opened YouTube, once its row is on screen; true when it was found. */
    private boolean restoreFocus() {
        Shelf target = recentShelf;
        if (restoreChannel != null) {
            target = null;
            for (Shelf shelf : shelves) if (shelf.channel.id.equals(restoreChannel)) target = shelf;
        }
        View card = target == null ? null : cardFor(target, restoreVideo);
        if (card == null) return false;
        restoreVideo = null;
        restoreChannel = null;
        moved = true;
        card.requestFocus();
        return true;
    }

    private static View cardFor(Shelf shelf, String videoId) {
        if (videoId == null) return null;
        for (int i = 0; i < shelf.strip.getChildCount(); i++) {
            View child = shelf.strip.getChildAt(i);
            if (child instanceof Card && videoId.equals(((Card) child).videoId)) return child;
        }
        return null;
    }

    private View card(Shelf shelf, Channel.Video video, String channelId, int id) {
        Card card = new Card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setFocusable(true);
        card.setClickable(true);
        card.videoId = video.id;
        if (ringState == null) ringState = ring().getConstantState();
        card.setTag(shelf);

        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setBackgroundColor(SURFACE_FOCUSED);
        picture.setClipToOutline(true);
        picture.setOutlineProvider(rounding);
        picture.setDuplicateParentStateEnabled(true);
        picture.setForeground(ringState.newDrawable());
        card.addView(picture, new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(THUMB_HEIGHT_DP)));
        TextView title = directed(text(video.title, 16, Color.WHITE));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(2), dp(6), dp(2), 0);
        if (arabic) title.setLineSpacing(0, 1.25f);
        card.addView(title, new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(CARD_HEIGHT_DP - THUMB_HEIGHT_DP)));

        // The focused card lifts: scale 1.04, an 8 dp shadow under the thumbnail, the white outline from the ring.
        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.04f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
            picture.setElevation(focused ? dp(8) : 0);
            if (focused) {
                markSelected(shelf);
                setHint(R.string.hint_play, R.string.hint_channels);
                keepInView(v);
            }
        });
        card.setOnClickListener(v -> play(video, channelId, shelf == recentShelf ? null : channelId));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(CARD_HEIGHT_DP));
        params.setMarginEnd(dp(16));
        card.setLayoutParams(params);
        showThumbnail(picture, video, id);
        return card;
    }

    /** The white 2 dp outline on the focused thumbnail; nothing otherwise. Built once, shared by every card. */
    private StateListDrawable ring() {
        GradientDrawable outline = rounded(Color.TRANSPARENT, RADIUS_DP);
        outline.setStroke(dp(2), FOCUS_STROKE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, outline);
        states.addState(new int[0], new GradientDrawable());
        return states;
    }

    /** Scrolls the shelf so the focused card, with room for its scale and outline, is fully inside it. */
    private void keepInView(View card) {
        View strip = (View) card.getParent();
        HorizontalScrollView holder = (HorizontalScrollView) strip.getParent();
        int margin = dp(SHELF_PADDING_DP);
        int left = strip.getLeft() + card.getLeft() - margin;
        int right = strip.getLeft() + card.getRight() + margin;
        int visibleLeft = holder.getScrollX();
        int visibleRight = visibleLeft + holder.getWidth();
        if (left < visibleLeft) holder.smoothScrollTo(left, 0);
        else if (right > visibleRight) holder.smoothScrollTo(right - holder.getWidth(), 0);
    }

    /** The window base with a soft accent glow from the top-start corner. */
    private Drawable background() {
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(arabic ? 1f : 0f, 0f);
        glow.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.7f);
        glow.setColors(new int[]{GLOW, GLOW & 0x00FFFFFF});
        return new LayerDrawable(new Drawable[]{new ColorDrawable(BACKGROUND), glow});
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private static TextView bold(TextView view) {
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void showThumbnail(ImageView picture, Channel.Video video, int id) {
        String url = video.thumbnailUrl();
        picture.setTag(url);
        Bitmap cached = thumbnails.get(url);
        if (cached != null) {
            picture.setImageBitmap(cached);
            return;
        }
        List<ImageView> pictures = waiting.get(url);
        if (pictures != null) {
            pictures.add(picture);
            return;
        }
        pictures = new ArrayList<>();
        pictures.add(picture);
        waiting.put(url, pictures);
        thumbnailPool.execute(() -> {
            Bitmap bitmap = download(url, dp(THUMB_WIDTH_DP), dp(THUMB_HEIGHT_DP));
            if (bitmap != null) thumbnails.put(url, bitmap);
            runOnUiThread(() -> {
                if (id != loadId || isDestroyed()) return;
                List<ImageView> done = waiting.remove(url);
                if (bitmap == null || done == null) return;
                for (ImageView view : done) {
                    if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
                }
            });
        });
    }

    /** The picture at url, shrunk by a power of two while it stays at least the size needed. */
    private static Bitmap download(String url, int width, int height) {
        try {
            byte[] bytes = Http.get(url, MAX_IMAGE_BYTES, TIMEOUT_MS);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            while (bounds.outWidth / (options.inSampleSize * 2) >= width
                    && bounds.outHeight / (options.inSampleSize * 2) >= height) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Hands the watch link to the TV's YouTube app; these apps play watch?v= links, not channel links. The video
     * goes to the front of Continue watching, and the card is remembered so Back from YouTube lands on it again.
     */
    private void play(Channel.Video video, String channelId, String shelfChannel) {
        if (!Channel.isVideoId(video.id)) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(video.watchUrl()));
        PackageManager pm = getPackageManager();
        for (String player : PLAYERS) {
            if (pm.getLaunchIntentForPackage(player) != null) {
                intent.setPackage(player);
                break;
            }
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_LONG).show();
            return;
        }
        recent.add(video, channelId);
        prefs.edit().putString(PREF_RECENT, recent.write()).apply();
        restoreChannel = shelfChannel;
        restoreVideo = video.id;
    }

    private static TextView directed(TextView view) {
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
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
