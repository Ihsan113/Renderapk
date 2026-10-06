package com.danzku.htmlrender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the render session in the foreground process with a persistent notification. */
public final class RenderService extends Service {
    private static final String CHANNEL_ID = "rendering";
    private static final int NOTIFICATION_ID = 701;
    private static RenderService instance;

    public static void start(Context context, int queueSize) {
        Intent i = new Intent(context, RenderService.class).putExtra("queueSize", queueSize);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void update(int percent, String text) {
        RenderService s = instance;
        if (s != null) s.updateNotification(percent, text);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startAsForeground("Menyiapkan render…", 0);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int q = intent == null ? 1 : intent.getIntExtra("queueSize", 1);
        updateNotification(0, "Render aktif • " + q + " item dalam antrean");
        return START_NOT_STICKY;
    }

    private void startAsForeground(String text, int progress) {
        Intent open = new Intent(this, RenderActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("HTML Render Studio")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, progress), false);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification(int percent, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent open = new Intent(this, RenderActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("HTML Render Studio")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(percent < 100)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, Math.min(100, percent)), false)
                .build();
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "HTML rendering", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public void onDestroy() {
        instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
