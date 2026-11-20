package com.zoolcoder.tv.health;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Once a minute, on the minute, shows the reminders that are due: each dose until it is taken (put off 15
 * minutes at a time), each appointment the evening before and 2 hours before. Runs while there is anything to
 * remind about and stops itself when the records are empty. Nothing goes online.
 */
public class HealthService extends Service {
    private static final String TAG = "FamilyHealth";
    private static final String CHANNEL = "health";

    /** True while the app's own screen is in front: it shows the same thing, so no banner over it. */
    static boolean screenOpen;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private Store store;
    private Banner banner;
    private LocalDate pruned;

    /** Starts the service when there is something to remind about, stops it when there is nothing. */
    static void sync(Context context) {
        Intent intent = new Intent(context, HealthService.class);
        if (Store.get(context).records.empty()) {
            context.stopService(intent);
            return;
        }
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            // Android 12+ refuses a foreground start from the background; the next open of the app starts it.
            Log.w(TAG, "service not started: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = Store.get(this);
        banner = new Banner(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (store.records.empty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        main.removeCallbacks(tick);
        tick();
        return START_STICKY;
    }

    private void tick() {
        Records r = store.records;
        if (r.empty()) {
            banner.hide();
            stopSelf();
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long millis = System.currentTimeMillis();
        if (!now.toLocalDate().equals(pruned)) {
            pruned = now.toLocalDate();
            r.prune(pruned, millis);
            store.changed();
        }
        if (screenOpen) {
            banner.hide();
        } else {
            for (Plan.Entry e : Plan.visitAlerts(r, now)) banner.offer(e);
            for (Plan.Entry e : Plan.ring(r, now, millis)) banner.offer(e);
        }
        long intoMinute = System.currentTimeMillis() % 60_000L;
        main.postDelayed(tick, 60_000L - intoMinute + 1000L);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        banner.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
