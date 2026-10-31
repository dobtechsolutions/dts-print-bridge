package com.dobtechsolutions.printbridge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds receipt printers on the local network: every host in the /24 around this device
 * that accepts a TCP connection on the raw printing port (9100). Printers that speak
 * ESC/POS "GS I" (Epson and most clones) also report their model name, e.g. "TM-m30".
 * Nothing is printed: GS I only asks for information.
 */
public final class LanScanner {
    public static final class Found {
        public final String ip;
        public final int port;
        /** Model reported by the printer, or "" when it doesn't answer GS I. */
        public final String model;

        public Found(String ip, int port, String model) {
            this.ip = ip;
            this.port = port;
            this.model = model == null ? "" : model;
        }

        @Override
        public String toString() {
            return (model.isEmpty() ? "" : model + " ") + ip + ":" + port;
        }
    }

    public interface Listener {
        void onFound(Found f);
    }

    private LanScanner() {
    }

    /** Parses "a.b.c.d" into an int, or -1. */
    static long toLong(String ip) {
        String[] p = ip == null ? new String[0] : ip.trim().split("\\.");
        if (p.length != 4) return -1;
        long v = 0;
        for (String s : p) {
            int n;
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (n < 0 || n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }

    static String toIp(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }

    /**
     * Hosts to probe for a device at {@code ownIp}/{@code prefix}. Networks bigger than /24
     * are narrowed to the /24 around the device (restaurant networks are /24 in practice, and
     * a /16 would take minutes). The device itself, network and broadcast are skipped.
     */
    public static List<String> hostsAround(String ownIp, int prefix) {
        List<String> out = new ArrayList<>();
        long own = toLong(ownIp);
        if (own < 0) return out;
        int p = prefix < 24 || prefix > 30 ? 24 : prefix;
        long mask = (0xFFFFFFFFL << (32 - p)) & 0xFFFFFFFFL;
        long net = own & mask;
        long size = 1L << (32 - p);
        for (long i = 1; i < size - 1; i++) {
            long h = net + i;
            if (h != own) out.add(toIp(h));
        }
        return out;
    }

    /** Blocking scan. Calls {@code listener} from worker threads as printers are found. */
    public static List<Found> scan(List<String> hosts, final int port, final int connectTimeoutMs,
                                   int threads, final Listener listener, final AtomicBoolean cancel)
            throws InterruptedException {
        final List<Found> found = Collections.synchronizedList(new ArrayList<Found>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        for (final String host : hosts) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (cancel != null && cancel.get()) return;
                    Found f = probe(host, port, connectTimeoutMs);
                    if (f != null) {
                        found.add(f);
                        if (listener != null) listener.onFound(f);
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);
        List<Found> sorted = new ArrayList<>(found);
        Collections.sort(sorted, new java.util.Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                return Long.compare(toLong(a.ip), toLong(b.ip));
            }
        });
        return sorted;
    }

    /** Connects to host:port; returns a Found (with the model if the printer tells us) or null. */
    public static Found probe(String host, int port, int connectTimeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            String model = "";
            try {
                s.setSoTimeout(700);
                model = askModel(s);
            } catch (Exception ignored) {
                // Port is open but the printer doesn't answer GS I: still a printer.
            }
            return new Found(host, port, model);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** ESC/POS GS I 67 (model name). Epson answers 0x5F + name + 0x00. */
    static String askModel(Socket s) throws Exception {
        OutputStream out = s.getOutputStream();
        out.write(new byte[]{0x1D, 0x49, 0x43});
        out.flush();
        InputStream in = s.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 700;
        try {
            while (System.currentTimeMillis() < deadline) {
                int b = in.read();
                if (b < 0) break;
                if (b == 0 && buf.size() > 0) break;
                buf.write(b);
                if (buf.size() > 64) break;
            }
        } catch (SocketTimeoutException ignored) {
            // whatever arrived so far
        }
        return parseModel(buf.toByteArray());
    }

    static String parseModel(byte[] raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            int c = b & 0xFF;
            if (c == 0x5F && sb.length() == 0) continue; // Epson header byte
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.toString().trim();
    }
}
