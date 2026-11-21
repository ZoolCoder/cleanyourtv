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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Once a minute, on the minute, shows the reminders that are due: each dose until it is taken (put off 15
 * minutes at a time), each appointment the evening before and 2 hours before. Runs while there is anything to
 * remind about or the phone page is on, and stops itself otherwise. The phone page switches itself off after
 * phone_minutes. With the phone page on it also runs the
 * home-network server phones add records through; it never connects out.
 */
public class HealthService extends Service {
    private static final String TAG = "FamilyHealth";
    private static final String CHANNEL = "health";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** How long a phone's request waits for the main thread before it is told the TV is busy. */
    private static final long MAIN_WAIT_MS = 2000;

    /** True while the app's own screen is in front: it shows the same thing, so no banner over it. */
    static boolean screenOpen;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    /** The phone page's switch-off on time; the minute tick checks again in case the clock moved. */
    private final Runnable switchOff = () -> {
        if (switchOffWhenDue()) stopServer();
        if (!needed()) stopSelf();
    };
    private Store store;
    private Phone phone;
    private PhoneServer server;
    private Banner banner;
    private LocalDate pruned;

    /** Starts the service when there is something to remind about or the phone page is on, else stops it. */
    static void sync(Context context) {
        Intent intent = new Intent(context, HealthService.class);
        Phone phone = new Phone(context);
        if (Store.get(context).records.empty() && !phone.enabled()) {
            context.stopService(intent);
            phone.setState("");
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
        phone = new Phone(this);
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
        switchOffWhenDue();
        if (!needed()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (phone.enabled() && server == null) startServer();
        else if (!phone.enabled()) stopServer();
        main.removeCallbacks(tick);
        tick();
        return START_STICKY;
    }

    private void tick() {
        Records r = store.records;
        if (switchOffWhenDue()) stopServer();
        if (!needed()) {
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

    /** The phone page's time is up: it switches off, as if the owner had. True when it did. */
    private boolean switchOffWhenDue() {
        if (!phone.expired(System.currentTimeMillis())) return false;
        phone.setEnabled(false);
        return true;
    }

    private boolean needed() {
        return !store.records.empty() || phone.enabled();
    }

    /** The port from config.xml, or 8767 when it is out of range; the phone page shows the same. */
    static int port(Context context) {
        int port = context.getResources().getInteger(R.integer.port);
        return port < 1024 || port > 65535 ? 8767 : port;
    }

    private void startServer() {
        int port = port(this);
        PhoneEndpoint endpoint = new PhoneEndpoint(readPage(), phone::pin,
                new PinGuard(System::currentTimeMillis, phone.lockoutMemory(), this::lockedOut), this::onMain,
                LocalDateTime::now, HealthService::newId);
        PhoneServer candidate = new PhoneServer(port, endpoint,
                () -> !phone.expired(System.currentTimeMillis()));
        try {
            candidate.start();
            server = candidate;
            phone.setState("running");
            main.removeCallbacks(switchOff);
            main.postDelayed(switchOff, Math.max(0, phone.onUntil() - System.currentTimeMillis()) + 500L);
        } catch (IOException e) {
            Log.w(TAG, "cannot listen on port " + port + ": " + e.getMessage());
            phone.setState("port");
        }
    }

    /**
     * 50 wrong PINs from all addresses: someone is guessing in earnest. A new PIN, and the page switches itself
     * off until the owner turns it on again. Called on a connection thread.
     */
    private void lockedOut() {
        phone.newPin();
        phone.setEnabled(false);
        main.post(() -> {
            stopServer();
            if (!needed()) stopSelf();
        });
    }

    private void stopServer() {
        main.removeCallbacks(switchOff);
        if (server != null) server.stop();
        server = null;
        phone.setState("");
    }

    /**
     * Runs a phone's read or change on the main thread, where the records live, and saves a change that worked.
     * Called on a connection thread, which waits at most MAIN_WAIT_MS for the main thread to take the task up.
     * A change the TV could not save is undone, so the phone's "try again" cannot add it twice.
     */
    private Response onMain(Function<Records, Response> action, boolean change) {
        AtomicBoolean claimed = new AtomicBoolean();
        FutureTask<Response> task = new FutureTask<>(() -> {
            if (!claimed.compareAndSet(false, true)) return null;
            Records before = change ? Records.fromJson(store.records.toJson()) : null;
            Response answer = action.apply(store.records);
            if (!change || answer.status != 200) return answer;
            // The phone hears "Saved" only once the change is on the TV's flash.
            if (!store.changed()) {
                store.records.restore(before);
                store.changed();
                return Response.text(500, "the TV could not save it, try again");
            }
            banner.forgetGone(store.records);
            return answer;
        });
        main.post(task);
        try {
            try {
                return task.get(MAIN_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Not taken up yet: it never runs, so a retry cannot add it twice. Taken up: wait for its answer.
                if (claimed.compareAndSet(false, true)) {
                    main.removeCallbacks(task);
                    return Response.text(503, "busy");
                }
                return task.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.text(503, "busy");
        } catch (ExecutionException e) {
            Log.w(TAG, "phone change failed: " + e.getCause());
            return Response.text(500, "the TV could not save it, try again");
        }
    }

    private static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) id.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return id.toString();
    }

    private String readPage() {
        StringBuilder html = new StringBuilder();
        try (InputStream in = getResources().openRawResource(R.raw.page);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) html.append(line).append('\n');
        } catch (IOException e) {
            Log.w(TAG, "page not readable: " + e.getMessage());
        }
        return html.toString();
    }

    @Override
    public void onDestroy() {
        stopServer();
        main.removeCallbacks(tick);
        banner.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
