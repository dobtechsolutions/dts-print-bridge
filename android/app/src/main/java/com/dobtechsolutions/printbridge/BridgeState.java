package com.dobtechsolutions.printbridge;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** In-process shared state between the service and the settings screen: status line + last 50 log lines. */
public final class BridgeState {
    public interface Listener {
        void onStateChanged();
    }

    private static final int MAX_LOG = 50;
    private static final LinkedList<String> LOG = new LinkedList<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean running;
    private static volatile String status = "";
    private static volatile boolean statusOk = true;

    private BridgeState() {}

    public static boolean isRunning() {
        return running;
    }

    public static void setRunning(boolean r) {
        running = r;
        notifyListeners();
    }

    public static String getStatus() {
        return status;
    }

    public static boolean isStatusOk() {
        return statusOk;
    }

    /** Returns true when the status text changed. */
    public static boolean setStatus(String text, boolean ok) {
        boolean changed = !text.equals(status) || ok != statusOk;
        status = text;
        statusOk = ok;
        if (changed) notifyListeners();
        return changed;
    }

    public static void log(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOG) {
            LOG.addFirst(ts + "  " + line);
            while (LOG.size() > MAX_LOG) LOG.removeLast();
        }
        notifyListeners();
    }

    /** Newest first. */
    public static String logText() {
        StringBuilder b = new StringBuilder();
        synchronized (LOG) {
            for (String l : LOG) b.append(l).append('\n');
        }
        return b.toString();
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

    private static void notifyListeners() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> copy;
                synchronized (LISTENERS) {
                    copy = new ArrayList<>(LISTENERS);
                }
                for (Listener l : copy) l.onStateChanged();
            }
        });
    }
}
