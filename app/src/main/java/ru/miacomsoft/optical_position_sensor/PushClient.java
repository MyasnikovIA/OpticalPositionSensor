package ru.miacomsoft.optical_position_sensor;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class PushClient {

    private static final String TAG = "PushClient";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    private final SensorBroadcaster broadcaster;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private SensorConfig cfg;
    private String subId;
    private long lastSendMs = 0;

    private Socket sock;
    private OutputStream sockOut;
    private Thread reconnectThread;

    public PushClient(SensorBroadcaster b) { this.broadcaster = b; }

    public boolean isRunning() { return running.get(); }

    public synchronized void start(SensorConfig cfg) {
        stop();
        this.cfg = cfg.copy();
        if (!this.cfg.clientEnabled) return;
        if (this.cfg.clientServerUrl == null || this.cfg.clientServerUrl.trim().isEmpty()) {
            Log.w(TAG, "clientServerUrl empty");
            return;
        }
        running.set(true);

        subId = broadcaster.subscribe(new SensorBroadcaster.Subscriber() {
            @Override public void onReading(JSONObject r) { send(r); }
        });

        String proto = this.cfg.clientProtocol == null
                ? "http" : this.cfg.clientProtocol.toLowerCase();
        if ("ws".equals(proto) || "telnet".equals(proto)) startReconnect();
        Log.i(TAG, "started " + proto + " -> " + this.cfg.clientServerUrl);
    }

    public synchronized void stop() {
        running.set(false);
        if (subId != null) { broadcaster.unsubscribe(subId); subId = null; }
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
        closeSocket();
    }

    private void closeSocket() {
        try { if (sockOut != null) sockOut.close(); } catch (Exception ignored) {}
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sockOut = null;
        sock = null;
    }

    private void startReconnect() {
        reconnectThread = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get()) {
                    try {
                        String proto = cfg.clientProtocol.toLowerCase();
                        if ("ws".equals(proto))          openWs();
                        else if ("telnet".equals(proto)) openTelnet();
                    } catch (Exception e) {
                        Log.w(TAG, "connect: " + e.getMessage());
                    }
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "PushReconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    // ----- HTTP -----
    private void sendHttp(JSONObject r) {
        HttpURLConnection c = null;
        try {
            String base = cfg.clientServerUrl.replaceAll("/+$", "");
            String path = cfg.clientPath == null || cfg.clientPath.isEmpty()
                    ? "/api/sensor/push"
                    : (cfg.clientPath.startsWith("/") ? cfg.clientPath : "/" + cfg.clientPath);
            URL u = new URL(base + path);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (cfg.clientToken != null && !cfg.clientToken.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + cfg.clientToken);
            }
            byte[] body = r.toString().getBytes(UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            try { os.write(body); } finally { os.close(); }
            int code = c.getResponseCode();
            if (code >= 400) Log.w(TAG, "push returned " + code);
        } catch (Exception e) {
            Log.w(TAG, "http push: " + e.getMessage());
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ----- WS -----
    private void openWs() throws Exception {
        String url = cfg.clientServerUrl.trim();
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            if (url.startsWith("https://")) url = "wss://" + url.substring(8);
            else if (url.startsWith("http://")) url = "ws://" + url.substring(7);
            else url = "ws://" + url;
        }
        URI uri = URI.create(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : ("wss".equals(uri.getScheme()) ? 443 : 80);
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        boolean tls = "wss".equals(uri.getScheme());

        Socket s = tls
                ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port)
                : new Socket(host, port);
        s.setTcpNoDelay(true);
        s.setSoTimeout(60000);

        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        String key = Base64.encodeToString(rnd, Base64.NO_WRAP);

        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + (cfg.clientToken != null && !cfg.clientToken.isEmpty()
                ? "Authorization: Bearer " + cfg.clientToken + "\r\n" : "")
                + "\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(ISO_8859_1));
        out.flush();

        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int p = 0, b;
        while ((b = in.read()) >= 0) {
            head.append((char) b);
            if (b == '\r' && (p == 0 || p == 2)) p++;
            else if (b == '\n' && (p == 1 || p == 3)) p++;
            else p = 0;
            if (p == 4) break;
            if (head.length() > 8192) throw new java.io.IOException("Header too large");
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            s.close();
            throw new java.io.IOException("WS handshake failed");
        }

        sock = s;
        sockOut = out;
        Log.i(TAG, "WS connected: " + url);
    }

    private void sendWs(JSONObject r) {
        OutputStream out = sockOut;
        if (out == null) return;
        try {
            byte[] payload = r.toString().getBytes(UTF_8);
            synchronized (out) {
                out.write(0x81);
                int len = payload.length;
                if (len < 126) out.write(len);
                else if (len <= 0xFFFF) {
                    out.write(126);
                    out.write((len >> 8) & 0xFF);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) out.write((int)((long)len >> (8*i)) & 0xFF);
                }
                out.write(payload);
                out.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "ws send: " + e.getMessage());
            closeSocket();
        }
    }

    // ----- Telnet -----
    private void openTelnet() throws Exception {
        URI u = URI.create(cfg.clientServerUrl.contains("://")
                ? cfg.clientServerUrl : "telnet://" + cfg.clientServerUrl);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 23;
        Socket s = new Socket(host, port);
        s.setTcpNoDelay(true);
        sock = s;
        sockOut = s.getOutputStream();
        Log.i(TAG, "Telnet connected: " + host + ":" + port);
    }

    private void sendTelnet(JSONObject r) {
        OutputStream out = sockOut;
        if (out == null) return;
        try {
            synchronized (out) {
                out.write((r.toString() + "\r\n").getBytes(UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "telnet send: " + e.getMessage());
            closeSocket();
        }
    }

    private void send(JSONObject r) {
        if (!running.get() || cfg == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSendMs < Math.max(10, cfg.clientIntervalMs)) return;
        lastSendMs = now;

        String proto = cfg.clientProtocol == null
                ? "http" : cfg.clientProtocol.toLowerCase();
        if ("http".equals(proto))        sendHttp(r);
        else if ("ws".equals(proto))     sendWs(r);
        else if ("telnet".equals(proto)) sendTelnet(r);
        else                             sendHttp(r);
    }
}