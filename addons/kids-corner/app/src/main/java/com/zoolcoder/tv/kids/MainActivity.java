package com.zoolcoder.tv.kids;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One row per channel in config.xml with its latest videos. OK opens the video in the TV's YouTube app;
 * nothing is played here. The feeds and thumbnails are read when the app opens, and again when it comes
 * back after a quarter of an hour; nothing runs in the background.
 */
public class MainActivity extends Activity {
    /** YouTube apps in order of preference; the first one installed gets the link. */
    private static final String[] PLAYERS = {"com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.teamsmart.videomanager.tv"};
    private static final int BACKGROUND = 0xFF0B1A12, SURFACE = 0xFF16281D, RAISED = 0xFF20382A, MUTED = 0xFF9DB8A8;
    private static final int ACCENT = 0xFFB65CFF, DARK_TEXT = 0xFF0B1A12;
    /** Flat circle colours for the channel letters, one per row in turn; all bright enough for dark text. */
    private static final int[] BADGES = {0xFFFF8FB1, 0xFF5CC8FF, 0xFFFFC542, 0xFF7CE0A0, 0xFFB65CFF, 0xFFFF9B6B,
            0xFFE8E26B};
    private static final int THUMB_WIDTH_DP = 144, THUMB_HEIGHT_DP = 81, CARD_HEIGHT_DP = 132, ROW_HEIGHT_DP = 140;
    private static final int LABEL_WIDTH_DP = 136;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_IMAGE_BYTES = 512 * 1024;
    private static final long STALE_MS = 15 * DateUtils.MINUTE_IN_MILLIS;

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
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
        }
    };
    private ExecutorService feedPool, thumbnailPool;
    private Drawable.ConstantState cardBackgroundState, focusRingState;
    private LinearLayout rows;
    private ScrollView scroll;
    private TextView status;
    private boolean arabic;
    private long loadedAt;
    private int loadId, rowCount;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        arabic = "ar".equals(Locale.getDefault().getLanguage());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        page.setPadding(dp(48), dp(28), dp(48), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        marker.setBackground(rounded(ACCENT, 3));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(6), dp(28));
        markerParams.setMarginEnd(dp(12));
        header.addView(marker, markerParams);
        header.addView(bold(directed(text(getString(R.string.title), 28, Color.WHITE))));
        page.addView(header);
        status = directed(text(getString(R.string.loading), 16, MUTED));
        status.setPadding(0, dp(4), 0, dp(8));
        page.addView(status);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = directed(text(getString(R.string.hint), 16, MUTED));
        hint.setPadding(0, dp(8), 0, 0);
        page.addView(hint);
        setContentView(page);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (loadedAt == 0 || SystemClock.elapsedRealtime() - loadedAt > STALE_MS) load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        waiting.clear();
        status.setText(R.string.loading);
        rows.removeAllViews();
        rowCount = 0;

        List<Channel> channels = new ArrayList<>();
        for (String line : getResources().getStringArray(R.array.channels)) {
            Channel channel = Channel.parse(line);
            if (channel != null) channels.add(channel);
        }
        int[] pending = {channels.size()};
        int[] loaded = {0};
        for (Channel channel : channels) {
            LinearLayout strip = addRow(channel);
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
                    fill(strip, result, id);
                    if (result != null) loaded[0]++;
                    if (--pending[0] == 0) finish(loaded[0]);
                });
            });
        }
        if (channels.isEmpty()) finish(0);
    }

    private void finish(int loaded) {
        if (loaded > 0) {
            loadedAt = SystemClock.elapsedRealtime();
            status.setText("");
        } else {
            status.setText(R.string.failed);
        }
    }

    /** A channel's label column and an empty strip for its videos; the row keeps its height as videos fill in. */
    private LinearLayout addRow(Channel channel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP));
        rowParams.bottomMargin = dp(4);
        rows.addView(row, rowParams);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(dp(LABEL_WIDTH_DP), dp(CARD_HEIGHT_DP));
        labelParams.gravity = Gravity.CENTER_VERTICAL;
        labelParams.setMarginEnd(dp(12));
        row.addView(label(channel, BADGES[rowCount++ % BADGES.length]), labelParams);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView holder = new HorizontalScrollView(this);
        holder.setHorizontalScrollBarEnabled(false);
        holder.setClipToPadding(false);
        holder.setPadding(dp(4), 0, dp(4), 0);
        holder.addView(strip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        row.addView(holder, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return strip;
    }

    /** A flat circle with the channel's first letter above its name. */
    private View label(Channel channel, int badgeColor) {
        String name = arabic ? channel.arabicName : channel.englishName;
        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(8), dp(8), dp(8), dp(8));
        label.setBackground(rounded(SURFACE, 10));

        TextView letter = text(name.isEmpty() ? "" : name.substring(0, name.offsetByCodePoints(0, 1)), 22,
                DARK_TEXT);
        bold(letter).setGravity(Gravity.CENTER);
        GradientDrawable circle = rounded(badgeColor, 0);
        circle.setShape(GradientDrawable.OVAL);
        letter.setBackground(circle);
        label.addView(letter, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView heading = bold(text(name, 19, Color.WHITE));
        heading.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        heading.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(0, dp(6), 0, 0);
        label.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private void fill(LinearLayout strip, List<Channel.Video> videos, int id) {
        strip.removeAllViews();
        if (videos == null || videos.isEmpty()) {
            TextView note = directed(text(getString(R.string.row_failed), 16, MUTED));
            note.setPadding(dp(4), 0, 0, 0);
            strip.addView(note);
            return;
        }
        boolean first = rows.getFocusedChild() == null && getCurrentFocus() == null;
        for (Channel.Video video : videos) {
            View card = card(video, id);
            strip.addView(card);
            if (first) {
                card.requestFocus();
                first = false;
            }
        }
    }

    private View card(Channel.Video video, int id) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        if (cardBackgroundState == null) {
            cardBackgroundState = cardBackground().getConstantState();
            focusRingState = focusRing().getConstantState();
        }
        card.setBackground(cardBackgroundState.newDrawable());
        card.setForeground(focusRingState.newDrawable());
        card.setFocusable(true);
        card.setClickable(true);

        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setBackgroundColor(RAISED);
        picture.setClipToOutline(true);
        picture.setOutlineProvider(rounding);
        card.addView(picture, new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(THUMB_HEIGHT_DP)));
        TextView title = directed(text(video.title, 16, Color.WHITE));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(6), dp(2), dp(6), 0);
        card.addView(title, new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(CARD_HEIGHT_DP - THUMB_HEIGHT_DP)));

        // A static 1.03 scale, no animation; the row's padding leaves room for it.
        card.setOnFocusChangeListener((v, focused) -> {
            v.setScaleX(focused ? 1.03f : 1f);
            v.setScaleY(focused ? 1.03f : 1f);
        });
        card.setOnClickListener(v -> play(video));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(CARD_HEIGHT_DP));
        params.setMarginEnd(dp(12));
        card.setLayoutParams(params);
        showThumbnail(picture, video, id);
        return card;
    }

    /** Surface, raised while focused. */
    private StateListDrawable cardBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(RAISED, 10));
        states.addState(new int[0], rounded(SURFACE, 10));
        return states;
    }

    /** Drawn over the card: a white outer stroke of 1 dp and the accent stroke of 2 dp inside it, when focused. */
    private StateListDrawable focusRing() {
        GradientDrawable outer = rounded(Color.TRANSPARENT, 10);
        outer.setStroke(dp(1), Color.WHITE);
        GradientDrawable inner = rounded(Color.TRANSPARENT, 9);
        inner.setStroke(dp(2), ACCENT);
        LayerDrawable ring = new LayerDrawable(new Drawable[]{outer, inner});
        ring.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[0], new GradientDrawable());
        return states;
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

    /** Hands the watch link to the TV's YouTube app; these apps play watch?v= links, not channel links. */
    private void play(Channel.Video video) {
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
        }
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
