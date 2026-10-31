package com.dobtechsolutions.printbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.dobtechsolutions.printbridge.core.AlertClient;
import com.dobtechsolutions.printbridge.core.BridgeClient;

/**
 * Foreground service running the 2-second poll loop. Holds a partial wake lock and a WiFi
 * lock so polling continues with the screen off. START_STICKY so Android restarts it.
 */
public final class BridgeService extends Service {
    public static final String ACTION_START = "com.dobtechsolutions.printbridge.START";
    public static final String ACTION_STOP = "com.dobtechsolutions.printbridge.STOP";

    private static final String CHANNEL = "bridge";
    private static final int NOTIFICATION_ID = 1;

    private Thread worker;
    private volatile boolean stopRequested;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BridgeService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, BridgeService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Config.setAutoStart(this, false);
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Call startForeground right away: startForegroundService() requires it within seconds.
        goForeground(worker == null ? getString(R.string.status_starting) : BridgeState.getStatus());

        Config cfg = Config.load(this); // intent == null means a sticky restart: reload from prefs
        if (!cfg.hasToken()) {
            BridgeState.log("no device token - not starting");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null) startWorker(cfg);
        return START_STICKY;
    }

    private void startWorker(final Config cfg) {
        acquireLocks();
        stopRequested = false;
        BridgeState.setRunning(true);
        BridgeState.log("bridge started (" + cfg.mode + ", token " + BridgeClient.maskToken(cfg.token) + ")");
        final BridgeClient client = new BridgeClient(BridgeClient.DEFAULT_API_BASE, cfg.token,
                Printers.forConfig(this, cfg), new BridgeClient.Events() {
                    @Override
                    public void onStatus(BridgeClient.Status status, String detail) {
                        String text = statusText(BridgeService.this, status, detail);
                        boolean ok = status == BridgeClient.Status.CONNECTED || status == BridgeClient.Status.PRINTED;
                        if (BridgeState.setStatus(text, ok)) updateNotification(text);
                    }

                    @Override
                    public void onLog(String line) {
                        BridgeState.log(line);
                    }
                });
        final AlertClient alerts = cfg.orderAlarm ? new AlertClient(BridgeClient.DEFAULT_API_BASE, cfg.token) : null;
        if (alerts == null) OrderAlarm.clear(this);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int tick = 0;
                while (!stopRequested) {
                    try {
                        client.pollOnce();
                    } catch (RuntimeException e) {
                        BridgeState.log("poll crashed: " + e);
                    }
                    // Order alarm: check for orders nobody accepted yet every ~4 seconds.
                    if (alerts != null && tick++ % 2 == 0) {
                        try {
                            OrderAlarm.update(BridgeService.this, cfg.token, alerts.fetch());
                        } catch (RuntimeException e) {
                            BridgeState.log("order alarm check failed: " + e);
                        }
                    }
                    try {
                        Thread.sleep(BridgeClient.POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "dts-bridge-poll");
        worker.start();
    }

    static String statusText(Context ctx, BridgeClient.Status s, String detail) {
        switch (s) {
            case CONNECTED: return ctx.getString(R.string.status_connected);
            case INVALID_TOKEN: return ctx.getString(R.string.status_invalid_token);
            case NETWORK_ERROR: return ctx.getString(R.string.status_network_error);
            case SERVER_ERROR: return ctx.getString(R.string.status_server_error, detail);
            case PRINTER_UNREACHABLE: return ctx.getString(R.string.status_printer_unreachable);
            case PRINTED: return ctx.getString(R.string.status_printed, detail);
            default: return "";
        }
    }

    private void shutdown() {
        stopRequested = true;
        if (worker != null) {
            worker.interrupt();
            worker = null;
            BridgeState.log("bridge stopped");
        }
        Printers.closeBluetooth();
        OrderAlarm.clear(this);
        releaseLocks();
        BridgeState.setRunning(false);
        BridgeState.setStatus(getString(R.string.status_stopped), true);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DTSPrintBridge:poll");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                @SuppressWarnings("deprecation")
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "DTSPrintBridge:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_print), getString(R.string.stop), stop).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void goForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied: the service keeps running without a visible notification
        }
    }
}
