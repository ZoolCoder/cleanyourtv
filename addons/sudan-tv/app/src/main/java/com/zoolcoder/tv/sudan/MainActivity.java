package com.zoolcoder.tv.sudan;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sudanese and Arabic channels as tiles. OK looks up the channel's current live video on YouTube and
 * hands that one watch link to the YouTube app the TV has; nothing is played here. The lookup is one
 * GET of the channel's public /live page: the TV YouTube app plays watch links only and sends channel
 * links to its Home.
 */
public class MainActivity extends Activity {
    /** YouTube apps in order of preference; the first one installed gets the link. */
    private static final String[] PLAYERS = {"com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.teamsmart.videomanager.tv"};
    private static final int COLUMNS = 3;
    private static final int BACKGROUND = 0xFF0B1A12, CARD = 0xFF16281D, FOCUS = 0xFF2BB673, MUTED = 0xFF9DB8A8;
    private static final int TIMEOUT_MS = 8000;
    /** The live page's canonical link names the live video; without one the channel is off air. */
    private static final Pattern LIVE_VIDEO = Pattern.compile(
            "rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"");

    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BACKGROUND);
        page.setPadding(dp(48), dp(32), dp(48), dp(24));

        page.addView(text(getString(R.string.title), 28, Color.WHITE));
        TextView hint = text(getString(R.string.hint), 14, MUTED);
        hint.setPadding(0, dp(4), 0, dp(20));
        page.addView(hint);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        for (String line : getResources().getStringArray(R.array.channels)) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            grid.addView(tile(parts[0].trim(), parts[1].trim(), parts[2].trim()));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private View tile(String arabic, String english, String channelId) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable back = new GradientDrawable();
        back.setColor(CARD);
        back.setCornerRadius(dp(12));
        back.setStroke(dp(2), CARD);
        tile.setBackground(back);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.addView(text(arabic, 22, Color.WHITE));
        tile.addView(text(english, 14, MUTED));
        tile.setOnFocusChangeListener((v, focused) -> {
            back.setStroke(dp(2), focused ? FOCUS : CARD);
            float scale = focused ? 1.04f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
        tile.setOnClickListener(v -> open(arabic, channelId));

        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        params.width = 0;
        params.setMargins(dp(8), dp(8), dp(8), dp(8));
        tile.setLayoutParams(params);
        return tile;
    }

    private void open(String name, String channelId) {
        if (busy) return;
        busy = true;
        Toast.makeText(this, getString(R.string.opening, name), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String videoId = liveVideoId(channelId);
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                if (videoId == null) {
                    Toast.makeText(this, getString(R.string.not_live, name), Toast.LENGTH_LONG).show();
                } else {
                    play(videoId);
                }
            });
        }).start();
    }

    /** The id of the channel's live video now, or null when it is off air or the page did not load. */
    static String liveVideoId(String channelId) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://www.youtube.com/channel/" + channelId + "/live");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
            connection.setRequestProperty("Accept-Language", "en");
            connection.setRequestProperty("Cookie", "SOCS=CAI");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return findLiveVideo(connection.getInputStream());
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The page is about 1.3 MB and the canonical link sits past its first half; stop at it or at 3 MB. */
    private static String findLiveVideo(InputStream in) throws IOException {
        StringBuilder page = new StringBuilder();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) > 0 && page.length() < 3 * 1024 * 1024) {
            int from = Math.max(0, page.length() - 128);
            page.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            Matcher m = LIVE_VIDEO.matcher(page).region(from, page.length());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private void play(String videoId) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId));
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
