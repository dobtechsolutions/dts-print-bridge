package com.dobtechsolutions.printbridge.core;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM test harness for the bridge protocol: a fake lan-bridge-poll endpoint (JDK HttpServer)
 * plus a local TCP "printer" on port 9100 that captures bytes. Run via ./build.sh test
 */
public final class ProtocolTest {
    private static int failures = 0;
    private static int passes = 0;

    // fake server state
    private static final AtomicInteger nextStatus = new AtomicInteger(204);
    private static final AtomicReference<String> nextBody = new AtomicReference<>("");
    private static final List<String> getTokens = Collections.synchronizedList(new ArrayList<String>());
    private static final List<String> posts = Collections.synchronizedList(new ArrayList<String>());

    // fake printer state
    private static final List<byte[]> printed = Collections.synchronizedList(new ArrayList<byte[]>());
    private static final AtomicInteger printerConnections = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/functions/v1/lan-bridge-poll", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                String q = ex.getRequestURI().getRawQuery();
                getTokens.add(URLDecoder.decode(q.substring("token=".length()), "UTF-8"));
                int code = nextStatus.get();
                if (code == 204) {
                    ex.sendResponseHeaders(204, -1);
                } else {
                    byte[] b = nextBody.get().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(code, b.length);
                    ex.getResponseBody().write(b);
                }
            } else {
                posts.add(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8));
                byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        http.start();
        String api = "http://127.0.0.1:" + http.getAddress().getPort() + "/functions/v1/lan-bridge-poll";

        ServerSocket printer;
        try {
            printer = new ServerSocket(9100, 50, java.net.InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            printer = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        }
        final ServerSocket printerSocket = printer;
        final int printerPort = printer.getLocalPort();
        Thread acceptor = new Thread(() -> {
            while (!printerSocket.isClosed()) {
                try (Socket s = printerSocket.accept()) {
                    printerConnections.incrementAndGet();
                    printed.add(readAll(s.getInputStream()));
                } catch (IOException ignored) {
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        System.out.println("fake API: " + api + "   fake printer: 127.0.0.1:" + printerPort);

        String token = "dev_tok+en/with=special&chars";
        Recorder events = new Recorder();

        // 1. 204 -> no print, no ack, status CONNECTED
        reset();
        nextStatus.set(204);
        BridgeClient lan = new BridgeClient(api, token, new TcpPrinter("", 0), events);
        BridgeClient.Result r = lan.pollOnce();
        check("204 -> NO_JOB", r == BridgeClient.Result.NO_JOB);
        check("204 -> status CONNECTED", events.last == BridgeClient.Status.CONNECTED);
        Thread.sleep(200);
        check("204 -> printer not contacted", printerConnections.get() == 0);
        check("204 -> no ack POST", posts.isEmpty());
        check("GET carries URL-encoded token (decodes back exactly)", token.equals(getTokens.get(0)));

        // 2. 200 job -> exact bytes on printer + ack code 200
        reset();
        byte[] payload = new byte[4096];
        new Random(42).nextBytes(payload);
        for (int i = 0; i < 256; i++) payload[i] = (byte) i; // every byte value
        String jobId = "3f2a9c1e-1111-2222-3333-444455556666";
        nextStatus.set(200);
        nextBody.set("{\"jobId\":\"" + jobId + "\",\"printerAddress\":\"127.0.0.1\",\"printerPort\":"
                + printerPort + ",\"payloadBase64\":\"" + java.util.Base64.getEncoder().encodeToString(payload) + "\"}");
        r = lan.pollOnce(); // app IP empty -> falls back to job printerAddress/printerPort
        Thread.sleep(300);
        check("200 -> PRINTED", r == BridgeClient.Result.PRINTED);
        check("200 -> exactly one printer connection", printerConnections.get() == 1);
        check("200 -> bytes delivered exactly (" + payload.length + " bytes)",
                printed.size() == 1 && Arrays.equals(printed.get(0), payload));
        check("200 -> one ack POST", posts.size() == 1);
        Map<?, ?> ack = posts.isEmpty() ? null : (Map<?, ?>) MiniJson.parse(posts.get(0));
        check("ack code 200", ack != null && Long.valueOf(200).equals(ack.get("code")));
        check("ack jobId", ack != null && jobId.equals(ack.get("jobId")));
        check("ack token", ack != null && token.equals(ack.get("token")));
        check("status PRINTED with short id", events.last == BridgeClient.Status.PRINTED && "3f2a9c1e".equals(events.lastDetail));

        // 3. app IP/port override the job's address
        reset();
        nextBody.set("{\"jobId\":\"job-override\",\"printerAddress\":\"10.255.255.1\",\"printerPort\":1,"
                + "\"payloadBase64\":\"" + java.util.Base64.getEncoder().encodeToString("hello".getBytes()) + "\"}");
        BridgeClient lanFixed = new BridgeClient(api, token, new TcpPrinter("127.0.0.1", printerPort), events);
        r = lanFixed.pollOnce();
        Thread.sleep(300);
        check("app IP overrides job address -> PRINTED", r == BridgeClient.Result.PRINTED
                && printed.size() == 1 && "hello".equals(new String(printed.get(0))));

        // 4. printer down -> ack code 500, status PRINTER_UNREACHABLE
        reset();
        int deadPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            deadPort = tmp.getLocalPort();
        }
        nextBody.set("{\"jobId\":\"job-down\",\"printerAddress\":\"127.0.0.1\",\"printerPort\":" + deadPort
                + ",\"payloadBase64\":\"G0A=\"}");
        r = lan.pollOnce();
        check("printer down -> PRINT_FAILED", r == BridgeClient.Result.PRINT_FAILED);
        check("printer down -> status PRINTER_UNREACHABLE", events.last == BridgeClient.Status.PRINTER_UNREACHABLE);
        ack = posts.isEmpty() ? null : (Map<?, ?>) MiniJson.parse(posts.get(0));
        check("printer down -> ack code 500", posts.size() == 1 && ack != null
                && Long.valueOf(500).equals(ack.get("code")) && "job-down".equals(ack.get("jobId")));

        // 5. 401 -> invalid token
        reset();
        nextStatus.set(401);
        nextBody.set("{\"error\":\"invalid token\"}");
        r = lan.pollOnce();
        check("401 -> INVALID_TOKEN", r == BridgeClient.Result.INVALID_TOKEN
                && events.last == BridgeClient.Status.INVALID_TOKEN && posts.isEmpty());

        // 6. 500 from server -> SERVER_ERROR, nothing printed
        reset();
        nextStatus.set(500);
        nextBody.set("oops");
        r = lan.pollOnce();
        check("HTTP 500 -> HTTP_ERROR", r == BridgeClient.Result.HTTP_ERROR && printed.isEmpty() && posts.isEmpty());

        // 7. network down -> NETWORK_ERROR
        http.stop(0);
        r = lan.pollOnce();
        check("server unreachable -> NETWORK_ERROR", r == BridgeClient.Result.NETWORK_ERROR
                && events.last == BridgeClient.Status.NETWORK_ERROR);

        // 8. Base64 decoder matches java.util.Base64
        Random rnd = new Random(7);
        boolean b64ok = true;
        for (int n = 0; n < 300; n++) {
            byte[] d = new byte[n];
            rnd.nextBytes(d);
            String enc = java.util.Base64.getEncoder().encodeToString(d);
            if (!Arrays.equals(d, Base64Decoder.decode(enc))) b64ok = false;
            if (!Arrays.equals(d, Base64Decoder.decode(enc.replace("=", "")))) b64ok = false;
            if (!Arrays.equals(d, Base64Decoder.decode(java.util.Base64.getMimeEncoder().encodeToString(d)))) b64ok = false;
        }
        check("Base64Decoder == java.util.Base64 (300 sizes, padded/unpadded/MIME)", b64ok);

        // 9. test ticket bytes
        byte[] t = EscPos.testTicket("2026-10-29 12:00");
        String ts = new String(t, StandardCharsets.US_ASCII);
        check("test ticket starts 1B40 1B6101 1B4501", t[0] == 0x1B && t[1] == 0x40 && t[2] == 0x1B && t[3] == 0x61
                && t[4] == 0x01 && t[5] == 0x1B && t[6] == 0x45 && t[7] == 0x01);
        check("test ticket ends 1D564200", t[t.length - 4] == 0x1D && t[t.length - 3] == 0x56
                && t[t.length - 2] == 0x42 && t[t.length - 1] == 0x00);
        check("test ticket text", ts.contains("TEST PRINT") && ts.contains("DTS Print Bridge works")
                && ts.contains("2026-10-29 12:00"));

        // 10. token masking
        check("maskToken hides the middle", "dev_…hars".equals(BridgeClient.maskToken(token)));

        printerSocket.close();

        // 11. LAN scanner: subnet maths
        List<String> hosts = LanScanner.hostsAround("192.168.1.87", 24);
        check("hostsAround /24 has 253 hosts", hosts.size() == 253);
        check("hostsAround skips own ip, net and broadcast", !hosts.contains("192.168.1.87")
                && !hosts.contains("192.168.1.0") && !hosts.contains("192.168.1.255")
                && hosts.contains("192.168.1.1") && hosts.contains("192.168.1.254"));
        check("hostsAround narrows /16 to /24", LanScanner.hostsAround("10.0.5.20", 16).size() == 253
                && LanScanner.hostsAround("10.0.5.20", 16).contains("10.0.5.1"));
        check("hostsAround rejects bad ip", LanScanner.hostsAround("abc", 24).isEmpty());
        check("parseModel strips Epson header", "TM-m30".equals(
                LanScanner.parseModel(new byte[]{0x5F, 'T', 'M', '-', 'm', '3', '0'})));

        // 12. LAN scanner: finds an Epson-like printer (answers GS I) and a silent one
        final ServerSocket epson = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        final ServerSocket silent = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread et = new Thread(() -> {
            try (Socket c = epson.accept()) {
                byte[] q = new byte[3];
                int n = c.getInputStream().read(q);
                if (n == 3 && q[0] == 0x1D && q[1] == 0x49 && q[2] == 0x43) {
                    c.getOutputStream().write(new byte[]{0x5F, 'T', 'M', '-', 'm', '3', '0', 0x00});
                    c.getOutputStream().flush();
                }
                Thread.sleep(200);
            } catch (Exception ignored) {
            }
        });
        et.start();
        Thread st = new Thread(() -> {
            try (Socket c = silent.accept()) {
                Thread.sleep(1200);
            } catch (Exception ignored) {
            }
        });
        st.start();
        LanScanner.Found fe = LanScanner.probe("127.0.0.1", epson.getLocalPort(), 500);
        check("probe finds Epson with model", fe != null && "TM-m30".equals(fe.model));
        LanScanner.Found fs = LanScanner.probe("127.0.0.1", silent.getLocalPort(), 500);
        check("probe finds silent printer without model", fs != null && fs.model.isEmpty());
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        check("probe returns null for closed port", LanScanner.probe("127.0.0.1", closedPort, 300) == null);
        epson.close();
        silent.close();

        // 13. order alarm protocol
        final List<String> alertCalls = Collections.synchronizedList(new ArrayList<String>());
        final AtomicReference<String> alertBody = new AtomicReference<>(
                "{\"currency\":\"EUR\",\"orders\":[{\"id\":\"o1\",\"number\":\"ORD-7\",\"type\":\"takeaway\","
                        + "\"table\":\"\",\"total\":26.45,\"customer\":\"Sam\",\"notes\":\"\",\"items\":\"2x Pad Thai\","
                        + "\"createdAt\":\"2026-10-31T17:00:00Z\",\"scheduledFor\":\"\"},{\"number\":\"no id\"}]}");
        HttpServer alertServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        alertServer.createContext("/alerts", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            byte[] resp;
            int status;
            if ("GET".equals(ex.getRequestMethod())) {
                alertCalls.add("GET " + q);
                resp = alertBody.get().getBytes(StandardCharsets.UTF_8);
                status = q != null && q.contains("token=tok%20en") && q.endsWith("&alerts=1") ? 200 : 401;
            } else {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                InputStream in = ex.getRequestBody();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                alertCalls.add("POST " + new String(b.toByteArray(), StandardCharsets.UTF_8));
                resp = "{\"ok\":true,\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
                status = 200;
            }
            ex.sendResponseHeaders(status, resp.length);
            OutputStream os = ex.getResponseBody();
            os.write(resp);
            os.close();
        });
        alertServer.start();
        String alertBase = "http://127.0.0.1:" + alertServer.getAddress().getPort() + "/alerts";
        AlertClient ac = new AlertClient(alertBase, " tok en ");
        List<AlertClient.Order> pending = ac.fetch();
        check("alerts: fetch parses orders and skips ones without id", pending != null && pending.size() == 1
                && "o1".equals(pending.get(0).id) && "ORD-7".equals(pending.get(0).number)
                && "takeaway".equals(pending.get(0).type) && Math.abs(pending.get(0).total - 26.45) < 0.001
                && "2x Pad Thai".equals(pending.get(0).items) && "EUR".equals(pending.get(0).currency));
        check("alerts: accept posts token, action and order id", ac.accept("o1")
                && alertCalls.get(alertCalls.size() - 1).equals(
                        "POST {\"token\":\"tok en\",\"action\":\"accept\",\"orderId\":\"o1\"}"));
        alertBody.set("not json");
        check("alerts: bad body returns null (keeps ringing state)", ac.fetch() == null);
        check("alerts: wrong token returns null", new AlertClient(alertBase, "other").fetch() == null);
        alertServer.stop(0);
        check("alerts: unreachable server returns null and accept false",
                ac.fetch() == null && !ac.accept("o1"));

        System.out.println();
        System.out.println(passes + " passed, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void reset() {
        printed.clear();
        posts.clear();
        getTokens.clear();
        printerConnections.set(0);
    }

    private static void check(String name, boolean ok) {
        if (ok) passes++;
        else failures++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static final class Recorder implements BridgeClient.Events {
        volatile BridgeClient.Status last;
        volatile String lastDetail;

        @Override
        public void onStatus(BridgeClient.Status status, String detail) {
            last = status;
            lastDetail = detail;
        }

        @Override
        public void onLog(String line) {
            System.out.println("      log: " + line);
        }
    }
}
