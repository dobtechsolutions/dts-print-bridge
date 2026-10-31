package com.dobtechsolutions.printbridge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Order alarm protocol (pure Java, unit-tested on the JVM):
 *   GET  {apiBase}?token=..&alerts=1  -> { currency, orders: [ {id, number, type, table, total, customer, notes, items, createdAt, scheduledFor} ] }
 *   POST {apiBase} { token, action: "accept", orderId } -> { ok, accepted }
 */
public final class AlertClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int HTTP_TIMEOUT_MS = 10000;

    public static final class Order {
        public String id = "";
        public String number = "";
        /** dine_in, takeaway, delivery or kiosk */
        public String type = "";
        public String table = "";
        public double total;
        public String currency = "EUR";
        public String customer = "";
        public String notes = "";
        public String items = "";
        public String createdAt = "";
        public String scheduledFor = "";
    }

    private final String apiBase;
    private final String token;

    public AlertClient(String apiBase, String token) {
        this.apiBase = apiBase;
        this.token = token == null ? "" : token.trim();
    }

    /** Orders waiting to be accepted, or null when the server could not be reached. */
    public List<Order> fetch() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiBase + "?token=" + BridgeClient.urlEncode(token) + "&alerts=1");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            return parse(read(conn.getInputStream()));
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static List<Order> parse(String body) {
        Object parsed = MiniJson.parse(body);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("not an object");
        Map<?, ?> root = (Map<?, ?>) parsed;
        String currency = str(root.get("currency"));
        List<Order> out = new ArrayList<>();
        Object list = root.get("orders");
        if (!(list instanceof List)) return out;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Order r = new Order();
            r.id = str(m.get("id"));
            if (r.id.isEmpty()) continue;
            r.number = str(m.get("number"));
            r.type = str(m.get("type"));
            r.table = str(m.get("table"));
            r.total = m.get("total") instanceof Number ? ((Number) m.get("total")).doubleValue() : 0;
            r.currency = currency.isEmpty() ? "EUR" : currency;
            r.customer = str(m.get("customer"));
            r.notes = str(m.get("notes"));
            r.items = str(m.get("items"));
            r.createdAt = str(m.get("createdAt"));
            r.scheduledFor = str(m.get("scheduledFor"));
            out.add(r);
        }
        return out;
    }

    /** True when the server took the request (also when another screen accepted it first). */
    public boolean accept(String orderId) {
        String json = "{\"token\":" + MiniJson.quote(token)
                + ",\"action\":\"accept\",\"orderId\":" + MiniJson.quote(orderId) + "}";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiBase).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] out = json.getBytes(UTF8);
            conn.setFixedLengthStreamingMode(out.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(out);
            } finally {
                os.close();
            }
            return conn.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
