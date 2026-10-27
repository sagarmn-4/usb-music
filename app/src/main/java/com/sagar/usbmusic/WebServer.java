package com.sagar.usbmusic;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Minimal HTTP server: the phone web page plus a small command API. */
final class WebServer {
    private static final int FIRST_PORT = 8080;

    private final Context ctx;
    private final MusicService svc;
    private final ExecutorService pool = Executors.newFixedThreadPool(10);
    /** Cover decoding is slow, so only a few run at once and the control requests stay quick. */
    private final Semaphore artSlots = new Semaphore(3);
    private ServerSocket socket;
    private volatile boolean running;
    private int port = -1;

    WebServer(Context ctx, MusicService svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    int port() {
        return port;
    }

    boolean start() {
        for (int p = FIRST_PORT; p < FIRST_PORT + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                socket = s;
                port = p;
                break;
            } catch (IOException ignored) {
                // port taken, try the next one
            }
        }
        if (socket == null) return false;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "usbmusic-accept");
        t.setDaemon(true);
        t.start();
        return true;
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = socket.accept();
                pool.execute(new Runnable() {
                    @Override public void run() {
                        handle(c);
                    }
                });
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                return; // pool shut down
            }
        }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null) return;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                // headers are not needed
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String target = parts[1];
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            }
            respond(c.getOutputStream(), path, q);
        } catch (Exception ignored) {
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void respond(OutputStream out, String path, Map<String, String> q) throws IOException {
        int status = 200;
        String cache = "no-store";
        String type = "application/json; charset=utf-8";
        byte[] body;
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                type = "text/html; charset=utf-8";
                body = readAsset("index.html");
            } else if (path.equals("/art")) {
                Track t = svc.trackAt(intArg(q.get("id")));
                byte[] art = null;
                if (t != null) {
                    artSlots.acquireUninterruptibly();
                    try {
                        art = AlbumArt.get(ctx, t);
                    } finally {
                        artSlots.release();
                    }
                }
                if (art == null) {
                    status = 404;
                    type = "text/plain; charset=utf-8";
                    body = "No art".getBytes(StandardCharsets.UTF_8);
                } else {
                    type = "image/jpeg";
                    cache = "max-age=86400";
                    body = art;
                }
            } else if (path.startsWith("/api/")) {
                JSONObject json = svc.api(path.substring(5), q);
                if (json == null) {
                    status = 404;
                    json = new JSONObject().put("error", "unknown command");
                }
                body = json.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                type = "text/plain; charset=utf-8";
                body = "Not found".getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            status = 500;
            type = "text/plain; charset=utf-8";
            body = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
        }
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static int intArg(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    /** The TV's address on the local network, or "?" if it has none. */
    static String localIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return "?";
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "?";
    }
}
