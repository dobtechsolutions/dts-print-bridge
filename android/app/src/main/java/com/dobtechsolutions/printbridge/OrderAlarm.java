package com.dobtechsolutions.printbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.dobtechsolutions.printbridge.core.AlertClient;
import com.dobtechsolutions.printbridge.core.BridgeClient;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Order alarm: rings (alarm volume), vibrates and wakes the screen for every new order,
 * and keeps going until somebody accepts it, here or on any other screen.
 * Fed by BridgeService every few seconds; shown by AlarmActivity.
 */
public final class OrderAlarm {
    public interface Listener {
        void onOrdersChanged(List<AlertClient.Order> orders);
    }

    private static final String CHANNEL = "orders";
    private static final int NOTIFICATION_ID = 2;
    private static final long MUTE_MS = 60_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static List<AlertClient.Order> pending = Collections.emptyList();
    private static final Set<String> announced = new HashSet<>();
    private static long mutedUntil;
    private static String token = "";

    private static MediaPlayer player;
    private static ToneGenerator tone;
    private static Thread toneThread;
    private static int savedVolume = -1;

    private OrderAlarm() {
    }

    public static synchronized List<AlertClient.Order> pending() {
        return new ArrayList<>(pending);
    }

    public static void addListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.remove(l);
        }
    }

    /** Called from the service with the server's list (null = server not reachable: keep state). */
    public static void update(final Context ctx, String deviceToken, List<AlertClient.Order> orders) {
        if (orders == null) return;
        token = deviceToken == null ? "" : deviceToken;
        final Context app = ctx.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                apply(app, orders);
            }
        });
    }

    private static void apply(Context app, List<AlertClient.Order> orders) {
        boolean hasNew = false;
        Set<String> ids = new HashSet<>();
        for (AlertClient.Order o : orders) {
            ids.add(o.id);
            if (!announced.contains(o.id)) hasNew = true;
        }
        announced.retainAll(ids);
        announced.addAll(ids);
        synchronized (OrderAlarm.class) {
            pending = new ArrayList<>(orders);
        }

        if (orders.isEmpty()) {
            stopRinging(app);
            cancelNotification(app);
        } else {
            if (hasNew) {
                mutedUntil = 0; // a new order always rings, even while muted
                BridgeState.log("new order alarm: " + orders.size() + " waiting");
            }
            if (System.currentTimeMillis() >= mutedUntil) startRinging(app);
            showNotification(app, orders, hasNew);
            if (hasNew) openScreen(app);
        }
        notifyListeners();
    }

    /** Stops all alarms (bridge stopped or alarm switched off). */
    public static void clear(Context ctx) {
        final Context app = ctx.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                announced.clear();
                apply(app, Collections.<AlertClient.Order>emptyList());
            }
        });
    }

    public static void mute(Context ctx) {
        mutedUntil = System.currentTimeMillis() + MUTE_MS;
        stopRinging(ctx.getApplicationContext());
    }

    public interface AcceptCallback {
        void onDone(boolean ok);
    }

    /** Accepts on the server (status → preparing), then removes it here straight away. */
    public static void accept(final Context ctx, final String orderId, final AcceptCallback cb) {
        final String t = token;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = new AlertClient(BridgeClient.DEFAULT_API_BASE, t).accept(orderId);
                BridgeState.log("accept " + orderId.substring(0, Math.min(8, orderId.length())) + (ok ? " ok" : " failed"));
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            List<AlertClient.Order> rest = new ArrayList<>();
                            for (AlertClient.Order o : pending()) if (!o.id.equals(orderId)) rest.add(o);
                            apply(ctx.getApplicationContext(), rest);
                        }
                        if (cb != null) cb.onDone(ok);
                    }
                });
            }
        }, "dts-accept").start();
    }

    /** Short test from the settings screen. */
    public static void test(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        startRinging(app);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pending().isEmpty()) stopRinging(app);
            }
        }, 4000);
    }

    // ---- sound + vibration ------------------------------------------------------------------

    private static synchronized void startRinging(Context app) {
        if (player != null || toneThread != null) return;
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            // A dedicated order device must be heard in a busy kitchen.
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (SecurityException ignored) {
            }
        }
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            if (uri == null) throw new IllegalStateException("no alarm sound");
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(app, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            // POS devices often have no alarm sound installed: use a loud beep pattern instead.
            startTone();
        }
        vibrate(app, true);
    }

    private static void startTone() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        } catch (RuntimeException e) {
            return;
        }
        toneThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        ToneGenerator t = tone;
                        if (t == null) break;
                        t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                        Thread.sleep(900);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "dts-alarm-tone");
        toneThread.start();
    }

    private static synchronized void stopRinging(Context app) {
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
            }
            player.release();
            player = null;
        }
        if (toneThread != null) {
            toneThread.interrupt();
            toneThread = null;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
        vibrate(app, false);
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && savedVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (SecurityException ignored) {
            }
            savedVolume = -1;
        }
    }

    @SuppressWarnings("deprecation")
    private static void vibrate(Context app, boolean on) {
        Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (!on) {
            v.cancel();
            return;
        }
        long[] pattern = {0, 700, 400, 700, 1200};
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0));
        else v.vibrate(pattern, 0);
    }

    // ---- screen + notification --------------------------------------------------------------

    static String money(AlertClient.Order o) {
        try {
            NumberFormat f = NumberFormat.getCurrencyInstance(Locale.getDefault());
            f.setCurrency(Currency.getInstance(o.currency));
            return f.format(o.total);
        } catch (RuntimeException e) {
            return o.currency + " " + String.format(Locale.US, "%.2f", o.total);
        }
    }

    static String typeLabel(Context ctx, AlertClient.Order o) {
        switch (o.type) {
            case "dine_in":
                return o.table.isEmpty() ? ctx.getString(R.string.alarm_type_dine_in)
                        : ctx.getString(R.string.alarm_type_table, o.table);
            case "takeaway":
                return ctx.getString(R.string.alarm_type_takeaway);
            case "delivery":
                return ctx.getString(R.string.alarm_type_delivery);
            case "kiosk":
                return ctx.getString(R.string.alarm_type_kiosk);
            default:
                return "";
        }
    }

    private static void openScreen(Context app) {
        try {
            app.startActivity(new Intent(app, AlarmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException ignored) {
            // Background activity starts may be blocked: the full-screen notification covers it.
        }
    }

    private static void showNotification(Context app, List<AlertClient.Order> orders, boolean alertAgain) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    app.getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // the app plays the alarm itself, at alarm volume
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(app, 10,
                new Intent(app, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags);
        AlertClient.Order first = orders.get(0);
        String title = orders.size() == 1
                ? app.getString(R.string.alarm_one, first.number)
                : app.getString(R.string.alarm_many, orders.size());
        String text = typeLabel(app, first) + " · " + money(first)
                + (first.items.isEmpty() ? "" : " · " + first.items);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
        b.setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(!alertAgain)
                .setContentIntent(open)
                .setFullScreenIntent(open, true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX);
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
        }
    }

    private static void cancelNotification(Context app) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    private static void notifyListeners() {
        List<Listener> copy;
        synchronized (LISTENERS) {
            copy = new ArrayList<>(LISTENERS);
        }
        List<AlertClient.Order> now = pending();
        for (Listener l : copy) l.onOrdersChanged(now);
    }
}
