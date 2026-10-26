package com.zoolcoder.tv.notes;

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

/**
 * Runs the note server while the switch is on, and shows each note on arrival. Receives text only and
 * exposes nothing from the TV. Stops itself when the switch goes off.
 */
public class NoteService extends Service {
    private static final String TAG = "FamilyNotes";
    private static final String CHANNEL = "notes";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private OverlayCard card;
    private NoteServer server;
    private boolean destroyed;

    /** Starts the service when the switch is on, stops it when off. */
    static void sync(Context context) {
        Store store = new Store(context);
        Intent intent = new Intent(context, NoteService.class);
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
        card = new OverlayCard(this);
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
        return START_STICKY;
    }

    private void startServer() {
        int port = getResources().getInteger(R.integer.port);
        if (port < 1024 || port > 65535) port = 8765;
        NoteEndpoint endpoint = new NoteEndpoint(readPage(), store::pin,
                new PinGuard(System::currentTimeMillis, store.lockoutMemory()), System::currentTimeMillis, this::onNote);
        NoteServer candidate = new NoteServer(port, endpoint);
        try {
            candidate.start();
            server = candidate;
            store.setState("running");
        } catch (IOException e) {
            Log.w(TAG, "cannot listen on port " + port + ": " + e.getMessage());
            store.setState("port");
        }
    }

    /** Called on a connection thread. */
    private void onNote(Note note) {
        store.add(note);
        int seconds = Math.max(3, getResources().getInteger(R.integer.popup_seconds));
        main.post(() -> {
            if (!destroyed) card.show(note, seconds);
        });
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
        destroyed = true;
        if (server != null) server.stop();
        server = null;
        main.removeCallbacksAndMessages(null);
        card.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
