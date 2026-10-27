package com.zoolcoder.tv.calendar;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the calendar server while the switch is on, and once a minute, on the minute, pops up the reminders
 * that are due. Each reminder pops up once. Receives events only and exposes nothing else from the TV. Stops
 * itself when the switch goes off. With a relay set and Family away on, it also fetches the events relatives
 * abroad sent, every two minutes and once each time the app opens.
 */
public class CalendarService extends Service {
    private static final String TAG = "FamilyCalendar";
    private static final String CHANNEL = "calendar";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private static final SecureRandom RANDOM = new SecureRandom();
    private Store store;
    private EventStore events;
    private ReminderCard card;
    private CalendarServer server;
    private AwayPoller away;
    private ScheduledFuture<?> nextPoll;
    private long rounds;

    /** The Family away poller, or null when no relay is set: then the app never goes online. */
    static AwayPoller away(Context context, EventStore events) {
        String relay = context.getString(R.string.relay_url);
        if (!RelayClient.usable(relay)) return null;
        AwayBox box = new AwayBox(context.getSharedPreferences(AwayBox.FILE, MODE_PRIVATE));
        return new AwayPoller(box, new RelayClient(relay), System::currentTimeMillis, RANDOM,
                CalendarService::newId, events::add);
    }

    /** Starts the service when the switch is on, stops it when off. */
    static void sync(Context context) {
        Store store = new Store(context);
        Intent intent = new Intent(context, CalendarService.class);
        if (!store.enabled()) {
            context.stopService(intent);
            store.setState("");
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
        store = new Store(this);
        events = EventStore.in(getFilesDir());
        card = new ReminderCard(this);
        away = away(this, events);
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
        if (!store.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server == null) startServer();
        main.removeCallbacks(tick);
        tick();
        pollSoon(0);
        return START_STICKY;
    }

    private void startServer() {
        int port = getResources().getInteger(R.integer.port);
        if (port < 1024 || port > 65535) port = 8766;
        CalendarEndpoint endpoint = new CalendarEndpoint(readPage(), store::pin,
                new PinGuard(System::currentTimeMillis, store.lockoutMemory(), store::newPin), events, LocalDate::now, CalendarService::newId);
        CalendarServer candidate = new CalendarServer(port, endpoint);
        try {
            candidate.start();
            server = candidate;
            store.setState("running");
        } catch (IOException e) {
            Log.w(TAG, "cannot listen on port " + port + ": " + e.getMessage());
            store.setState("port");
        }
    }

    /** Replaces any waiting round with one after {@code delay} ms, on the Family away thread. */
    private synchronized void pollSoon(long delay) {
        if (away == null) return;
        if (nextPoll != null) nextPoll.cancel(false);
        long round = ++rounds;
        nextPoll = AwayPoller.THREAD.schedule(() -> poll(round), delay, TimeUnit.MILLISECONDS);
    }

    /** A round that finishes after pollSoon replaced it, or after the service stopped, schedules nothing. */
    private void poll(long round) {
        long next = away.poll();
        synchronized (this) {
            if (round != rounds || nextPoll == null) return;
            nextPoll = next == AwayPoller.STOP ? null
                    : AwayPoller.THREAD.schedule(() -> poll(round), next, TimeUnit.MILLISECONDS);
        }
    }

    private static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) id.append(String.format("%02x", b & 0xFF));
        return id.toString();
    }

    /** Shows what is due, marks it shown, and comes back one second after the next minute starts. */
    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<Occurrence> due = Agenda.due(events.events(), now, events.fired());
        if (!due.isEmpty()) {
            List<String> keys = new ArrayList<>();
            int seconds = Math.max(3, getResources().getInteger(R.integer.popup_seconds));
            for (Occurrence o : due) {
                keys.add(o.event.key(o.day));
                card.show(o, seconds);
            }
            events.markFired(keys, now.toLocalDate());
        }
        long intoMinute = System.currentTimeMillis() % 60_000L;
        main.postDelayed(tick, 60_000L - intoMinute + 1000L);
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
        if (server != null) server.stop();
        server = null;
        main.removeCallbacksAndMessages(null);
        synchronized (this) {
            if (nextPoll != null) nextPoll.cancel(false);
            nextPoll = null;
        }
        card.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
