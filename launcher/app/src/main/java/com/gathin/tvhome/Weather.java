package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The weather pill (config.xml {@code weather}): the sky and temperature now at a fixed place, from Open-Meteo's
 * free forecast. Fetched on a plain thread every 30 minutes while Home is on screen; the last reading is kept in
 * preferences so the pill is filled at once after a restart and stays up while the network is away.
 */
final class Weather {
    private static final String TAG = "Weather";
    private static final String PREFS = "weather";
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long STALE_MS = 3 * 60 * 60_000L;

    private final TextView pill;
    private final String url;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;
    private boolean running;
    private Thread fetch;

    Weather(TextView pill, String latitude, String longitude) {
        this.pill = pill;
        this.url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code,is_day&timezone=auto";
        this.prefs = pill.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Shows the last reading and starts the half-hourly refresh. */
    void start() {
        running = true;
        show();
        long age = System.currentTimeMillis() - prefs.getLong("time", 0);
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, age >= REFRESH_MS ? 0 : REFRESH_MS - age);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
    }

    private void refresh() {
        if (!running || (fetch != null && fetch.isAlive())) return;
        fetch = new Thread(() -> {
            JSONObject current = fetchCurrent();
            handler.post(() -> {
                if (current != null) {
                    prefs.edit().putFloat("temperature", (float) current.optDouble("temperature_2m", Float.NaN))
                            .putInt("code", current.optInt("weather_code", -1))
                            .putBoolean("day", current.optInt("is_day", 1) == 1)
                            .putLong("time", System.currentTimeMillis()).apply();
                    show();
                }
                if (running) handler.postDelayed(refresh, current != null ? REFRESH_MS : 5 * 60_000L);
            });
        }, TAG);
        fetch.start();
    }

    private JSONObject fetchCurrent() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                for (String line; (line = reader.readLine()) != null; ) body.append(line);
                return new JSONObject(body.toString()).getJSONObject("current");
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Fills the pill from the stored reading; hidden until the first reading or when it is hours old. */
    private void show() {
        int code = prefs.getInt("code", -1);
        float temperature = prefs.getFloat("temperature", Float.NaN);
        boolean fresh = System.currentTimeMillis() - prefs.getLong("time", 0) < STALE_MS;
        if (code < 0 || Float.isNaN(temperature) || !fresh) {
            pill.setVisibility(View.GONE);
            return;
        }
        Locale locale = pill.getResources().getConfiguration().getLocales().get(0);
        // The launcher has no RTL support, so the pill reads left to right whatever the language: a leading
        // left-to-right mark fixes the paragraph, the isolate keeps "22°" together next to Arabic text.
        pill.setText(String.format(locale, "\u200E%s  \u2066%d°\u2069  %s", emoji(code, prefs.getBoolean("day", true)),
                Math.round(temperature), pill.getContext().getString(label(code))));
        pill.setVisibility(View.VISIBLE);
    }

    /** WMO weather code to an emoji; the night sky gets the moon. */
    static String emoji(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if (code <= 57) return "🌦️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "🌨️";
        if (code <= 82) return "🌦️";
        if (code <= 86) return "🌨️";
        return "⛈️";
    }

    /** WMO weather code to its short name. */
    static int label(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code <= 2) return R.string.weather_partly_cloudy;
        if (code == 3) return R.string.weather_cloudy;
        if (code <= 48) return R.string.weather_fog;
        if (code <= 57) return R.string.weather_drizzle;
        if (code <= 67) return R.string.weather_rain;
        if (code <= 77) return R.string.weather_snow;
        if (code <= 82) return R.string.weather_rain;
        if (code <= 86) return R.string.weather_snow;
        return R.string.weather_thunderstorm;
    }
}
