package com.anastasia.ai;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

final class InternalBuilderServer {
    private final int port;
    private volatile boolean running;
    private Thread thread;
    private final AtomicReference<byte[]> apk = new AtomicReference<>();

    InternalBuilderServer(int port) { this.port = port; }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "AnastasiaBuilderServer");
        thread.setDaemon(true);
        thread.start();
    }

    byte[] getApk() { return apk.get(); }

    private void loop() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            while (running) {
                try (Socket s = ss.accept()) { handle(s); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) { running = false; }
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r?\n")) {
            int k = line.indexOf(':');
            if (k > 0 && line.substring(0, k).trim().equalsIgnoreCase("Content-Length")) {
                try { return Integer.parseInt(line.substring(k + 1).trim()); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private void handle(Socket socket) throws Exception {
        socket.setSoTimeout(15000);
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) >= 0 && head.size() < 65536) {
            head.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
        }
        String h = head.toString(StandardCharsets.ISO_8859_1.name());
        String first = h.split("\r?\n", 2)[0];
        String[] parts = first.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";

        if ("OPTIONS".equals(method)) { fixed(socket, 204, "text/plain", new byte[0]); return; }
        if ("GET".equals(method) && "/ping".equals(path)) {
            fixed(socket, 200, "application/json", "{\"ok\":true,\"builder\":\"internal-java\",\"version\":\"5.4\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (("GET".equals(method) || "HEAD".equals(method)) && "/generated.apk".equals(path)) {
            byte[] data = apk.get();
            if (data == null) { fixed(socket, 404, "text/plain", "APK not ready".getBytes(StandardCharsets.UTF_8)); return; }
            OutputStream out = socket.getOutputStream();
            String hh = "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Disposition: attachment; filename=\"AnastasiaGenerated.apk\"\r\nCache-Control: no-store\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
            out.write(hh.getBytes(StandardCharsets.ISO_8859_1));
            if ("GET".equals(method)) out.write(data);
            out.flush();
            return;
        }
        if ("POST".equals(method) && "/upload".equals(path)) {
            int len = contentLength(h);
            if (len <= 0 || len > 2_000_000) { fixed(socket, 413, "text/plain", "invalid size".getBytes(StandardCharsets.UTF_8)); return; }
            byte[] data = readExactly(in, len);
            if (data.length != len) { fixed(socket, 400, "text/plain", "incomplete".getBytes(StandardCharsets.UTF_8)); return; }
            apk.set(data);
            fixed(socket, 200, "application/json", ("{\"ok\":true,\"url\":\"http://127.0.0.1:" + port + "/generated.apk\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        fixed(socket, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int p = 0;
        while (p < n) {
            int r = in.read(b, p, n - p);
            if (r < 0) break;
            p += r;
        }
        if (p == n) return b;
        byte[] x = new byte[p];
        System.arraycopy(b, 0, x, 0, p);
        return x;
    }

    private static void fixed(Socket socket, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large" : "Bad Request";
        String h = String.format(Locale.US, "HTTP/1.1 %d %s\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", code, reason, type, body.length);
        OutputStream out = socket.getOutputStream();
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
