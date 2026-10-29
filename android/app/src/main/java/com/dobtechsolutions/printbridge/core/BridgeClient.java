package com.dobtechsolutions.printbridge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * One poll/print/ack cycle of the DTS Print Bridge protocol. Pure Java (no Android
 * imports) so it can be unit-tested on the JVM. Mirrors poll_once() in
 * src-tauri/src/main.rs exactly:
 *
 *  1. GET  {apiBase}?token=<url-encoded token>
 *       204 = no job, 401 = invalid token, 200 = { jobId, printerAddress, printerPort, payloadBase64 }
 *  2. base64-decode payload (raw ESC/POS) and send it to the printer
 *  3. POST {apiBase} with { "token", "jobId", "code": 200 | 500 }
 */
public final class BridgeClient {

    public static final String DEFAULT_API_BASE =
            "https://crpfwwuiujothrxflmef.supabase.co/functions/v1/lan-bridge-poll";
    public static final long POLL_INTERVAL_MS = 2000;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int HTTP_TIMEOUT_MS = 10000;

    /** Sends raw bytes to a printer. jobAddress/jobPort come from the server (may be empty / 0). */
    public interface Printer {
        void print(byte[] data, String jobAddress, int jobPort) throws IOException;
    }

    public enum Status { CONNECTED, INVALID_TOKEN, NETWORK_ERROR, SERVER_ERROR, PRINTER_UNREACHABLE, PRINTED }

    public interface Events {
        /** detail: short job id for PRINTED, HTTP code for SERVER_ERROR, otherwise may be null. */
        void onStatus(Status status, String detail);
        void onLog(String line);
    }

    /** What pollOnce() did; used by tests and the service. */
    public enum Result { NO_JOB, INVALID_TOKEN, NETWORK_ERROR, HTTP_ERROR, BAD_JOB, PRINTED, PRINT_FAILED }

    private final String apiBase;
    private final String token;
    private final Printer printer;
    private final Events events;

    public BridgeClient(String apiBase, String token, Printer printer, Events events) {
        this.apiBase = apiBase;
        this.token = token == null ? "" : token.trim();
        this.printer = printer;
        this.events = events;
    }

    public Result pollOnce() {
        HttpURLConnection conn = null;
        int code;
        String body;
        try {
            URL url = new URL(apiBase + "?token=" + urlEncode(token));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            code = conn.getResponseCode();
            body = code == 204 ? "" : readBody(conn, code);
        } catch (IOException e) {
            events.onLog("poll error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            events.onStatus(Status.NETWORK_ERROR, null);
            return Result.NETWORK_ERROR;
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (code == 204) {
            events.onStatus(Status.CONNECTED, null);
            return Result.NO_JOB;
        }
        if (code == 401) {
            events.onLog("invalid token");
            events.onStatus(Status.INVALID_TOKEN, null);
            return Result.INVALID_TOKEN;
        }
        if (code < 200 || code > 299) {
            events.onLog("HTTP " + code);
            events.onStatus(Status.SERVER_ERROR, String.valueOf(code));
            return Result.HTTP_ERROR;
        }

        String jobId;
        String address;
        int port;
        String payload;
        try {
            Object parsed = MiniJson.parse(body);
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("not an object");
            Map<?, ?> job = (Map<?, ?>) parsed;
            jobId = str(job.get("jobId"));
            address = str(job.get("printerAddress"));
            port = num(job.get("printerPort"));
            payload = str(job.get("payloadBase64"));
            if (jobId.isEmpty()) throw new IllegalArgumentException("missing jobId");
        } catch (RuntimeException e) {
            events.onLog("bad job body: " + e.getMessage());
            return Result.BAD_JOB;
        }

        String shortId = jobId.substring(0, Math.min(8, jobId.length()));
        events.onLog("job " + shortId + " -> " + address + ":" + port);

        byte[] bytes;
        try {
            bytes = Base64Decoder.decode(payload);
        } catch (IllegalArgumentException e) {
            // Desktop bridge returns without ack here; keep identical behaviour.
            events.onLog("bad payload: " + e.getMessage());
            return Result.BAD_JOB;
        }

        int result;
        try {
            printer.print(bytes, address, port);
            events.onLog("job " + shortId + " printed (" + bytes.length + " bytes)");
            events.onStatus(Status.PRINTED, shortId);
            result = 200;
        } catch (IOException | RuntimeException e) {
            events.onLog("printer write failed: " + e.getMessage());
            events.onStatus(Status.PRINTER_UNREACHABLE, null);
            result = 500;
        }

        ack(jobId, result);
        return result == 200 ? Result.PRINTED : Result.PRINT_FAILED;
    }

    private void ack(String jobId, int result) {
        String json = "{\"token\":" + MiniJson.quote(token)
                + ",\"jobId\":" + MiniJson.quote(jobId)
                + ",\"code\":" + result + "}";
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
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) events.onLog("ack HTTP " + code);
        } catch (IOException e) {
            events.onLog("ack failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn, int code) throws IOException {
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
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

    static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** "abcd…wxyz" — never log or display the full device token. */
    public static String maskToken(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.length() <= 8) return t.isEmpty() ? "" : "****";
        return t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }
}
