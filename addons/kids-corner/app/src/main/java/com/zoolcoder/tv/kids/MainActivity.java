package com.zoolcoder.tv.kids;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.view.KeyEvent;
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
    private static final int BACKGROUND = 0xFF0B1A12, SURFACE = 0x9916281D, SURFACE_FOCUSED = 0xFF2C4838;
    private static final int TEXT_2 = 0xC7FFFFFF, TEXT_3 = 0x99FFFFFF;
    private static final int ACCENT = 0xFFB65CFF, GLOW = 0x26B65CFF, DARK_TEXT = 0xFF0B1A12;
    private static final int SELECTED_FILL = 0x40B65CFF, FOCUS_STROKE = 0xFFFFFFFF, CHIP_FILL = 0x29FFFFFF;
    /** Flat circle colours for the channel letters, one per row in turn; all bright enough for dark text. */
    private static final int[] BADGES = {0xFFFF8FB1, 0xFF5CC8FF, 0xFFFFC542, 0xFF7CE0A0, 0xFFB65CFF, 0xFFFF9B6B,
            0xFFE8E26B};
    private static final int THUMB_WIDTH_DP = 160, THUMB_HEIGHT_DP = 90, CARD_HEIGHT_DP = 148;
    private static final int LABEL_WIDTH_DP = 184, SHELF_TITLE_DP = 28, SHELF_PADDING_DP = 16;
    private static final int RADIUS_DP = 14;
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
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RADIUS_DP));
        }
    };
    private ExecutorService feedPool, thumbnailPool;
    private Drawable.ConstantState ringState;
    private Rail railSelected;
    private TextView hintOk, hintBack;
    private LinearLayout rows;
    private ScrollView scroll;
    private TextView status;
    private boolean arabic, moved;
    private long loadedAt;
    private int loadId, rowCount;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        arabic = "ar".equals(Locale.getDefault().getLanguage());

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

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll = new ScrollView(this);
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) moved = true;
        return super.dispatchKeyEvent(event);
    }

    /** Back from a video goes to its channel in the rail; Back from the rail leaves the app. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View focused = getCurrentFocus();
        if (keyCode == KeyEvent.KEYCODE_BACK && focused != null && focused.getTag() instanceof Rail) {
            ((Rail) focused.getTag()).requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        railSelected = null;
        moved = false;

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

    /** A rail entry and a shelf for the channel's videos; the row keeps its height as videos fill in. */
    private LinearLayout addRow(Channel channel) {
        String name = arabic ? channel.arabicName : channel.englishName;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        rows.addView(row, rowParams);

        String other = arabic ? channel.englishName : channel.arabicName;
        Rail label = label(name, other.equals(name) ? "" : other, BADGES[rowCount++ % BADGES.length]);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                dp(LABEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(SHELF_TITLE_DP + SHELF_PADDING_DP + THUMB_HEIGHT_DP / 2 - 30);
        labelParams.setMarginEnd(dp(12));
        row.addView(label, labelParams);

        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        TextView title = directed(text(name, 20, Color.WHITE));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPaddingRelative(dp(SHELF_PADDING_DP), 0, 0, 0);
        shelf.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(SHELF_TITLE_DP)));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setClipChildren(false);
        strip.setTag(label);
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
        shelf.addView(holder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(CARD_HEIGHT_DP + 2 * SHELF_PADDING_DP)));
        row.addView(shelf, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return strip;
    }

    /** A rail entry: its own focus target, and the dot and tint when the channel's shelf is the one in view. */
    private static final class Rail extends LinearLayout {
        View dot, firstVideo;

        Rail(Activity context) {
            super(context);
        }
    }

    /** A rail entry: a round avatar with the channel's first letter, its name and the name in the other script. */
    private Rail label(String name, String otherName, int badgeColor) {
        Rail label = new Rail(this);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPaddingRelative(dp(10), dp(10), dp(12), dp(10));
        label.setBackground(railBackground());
        label.setFocusable(true);
        label.setClickable(true);

        TextView letter = text(name.isEmpty() ? "" : name.substring(0, name.offsetByCodePoints(0, 1)), 18,
                DARK_TEXT);
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
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
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

        label.setOnFocusChangeListener((v, focused) -> {
            lift(v, focused, 1.04f);
            if (focused) {
                markSelected(label);
                setHint(R.string.hint_videos, R.string.hint_home);
            }
        });
        label.setOnClickListener(v -> {
            if (label.firstVideo != null) label.firstVideo.requestFocus();
        });
        return label;
    }

    /** The rail entry of the shelf in view gets the accent tint and the dot. */
    private void markSelected(Rail label) {
        if (label == railSelected) return;
        if (railSelected != null) select(railSelected, false);
        select(label, true);
        railSelected = label;
    }

    private static void select(Rail label, boolean selected) {
        label.setSelected(selected);
        label.dot.setVisibility(selected ? View.VISIBLE : View.GONE);
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

    private void fill(LinearLayout strip, List<Channel.Video> videos, int id) {
        strip.removeAllViews();
        if (videos == null || videos.isEmpty()) {
            TextView note = directed(text(getString(R.string.row_failed), 15, TEXT_2));
            note.setPaddingRelative(dp(4), 0, 0, 0);
            strip.addView(note);
            return;
        }
        Rail label = (Rail) strip.getTag();
        for (Channel.Video video : videos) strip.addView(card(video, id, label));
        label.firstVideo = strip.getChildAt(0);
        // Rows fill in any order: until the first key press, focus stays on the top-most row that has videos.
        View first = strip.getChildAt(0);
        first.post(() -> {
            View row = (View) strip.getParent().getParent().getParent();
            View focusedRow = rows.getFocusedChild();
            if (!moved && (focusedRow == null || rows.indexOfChild(row) < rows.indexOfChild(focusedRow))) {
                first.requestFocus();
            }
        });
    }

    private View card(Channel.Video video, int id, Rail label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setFocusable(true);
        card.setClickable(true);
        if (ringState == null) ringState = ring().getConstantState();
        card.setTag(label);

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
                markSelected(label);
                setHint(R.string.hint_play, R.string.hint_channels);
                keepInView(v);
            }
        });
        card.setOnClickListener(v -> play(video));
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
