package ru.miacomsoft.optical_position_sensor;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket-сервер (RFC 6455) на чистом ServerSocket.
 * Совместим с Android 4.4 (API 19): использует android.util.Base64.
 */
public class WebSocketServer {

    private static final String TAG = "WsServer";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    private final SensorService svc;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Socket> clients = new ConcurrentHashMap<String, Socket>();
    private final AtomicLong idGen = new AtomicLong();
    private int port;

    public WebSocketServer(SensorService svc) { this.svc = svc; }

    public int getPort() { return port; }
    public int getClientCount() { return clients.size(); }
    public boolean isRunning() { return running.get(); }

    public void start(int port) throws IOException {
        if (running.get()) return;
        this.port = port;
        serverSocket = new ServerSocket(port);
        running.set(true);

        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "WsServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Listening on :" + port);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(0);
                final String id = "w-" + idGen.incrementAndGet();
                clients.put(id, s);
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { serve(id, s); }
                }, "Ws-" + id);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void serve(String id, Socket sock) {
        try {
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            if (!handshake(in, out)) {
                closeClient(id, sock);
                return;
            }

            final Object lock = new Object();

            SensorBroadcaster.Subscriber sub = new SensorBroadcaster.Subscriber() {
                @Override public void onReading(JSONObject r) throws Exception {
                    writeTextFrame(out, lock, r.toString());
                }
            };
            String subId = svc.getBroadcaster().subscribe(sub);

            try {
                while (running.get()) {
                    Frame f = readFrame(in);
                    if (f == null) break;
                    if (f.opcode == 0x8) {              // close
                        writeFrame(out, lock, 0x8, new byte[0]);
                        break;
                    }
                    if (f.opcode == 0x9) {              // ping
                        writeFrame(out, lock, 0xA, f.payload);
                        continue;
                    }
                    if (f.opcode == 0x1) {              // text
                        try {
                            JSONObject req = new JSONObject(new String(f.payload, UTF_8));
                            JSONObject resp = handleCommand(req);
                            if (resp != null) writeTextFrame(out, lock, resp.toString());
                        } catch (Exception ignore) {}
                    }
                }
            } finally {
                svc.getBroadcaster().unsubscribe(subId);
            }
        } catch (Exception e) {
            // disconnect
        } finally {
            closeClient(id, sock);
        }
    }

    private JSONObject handleCommand(JSONObject req) {
        try {
            String cmd = req.optString("cmd", "");
            if ("setFlashlight".equals(cmd)) {
                String mode = req.optString("mode", "");
                int thr = req.optInt("threshold", -1);
                if (!mode.isEmpty()) svc.setFlashlightMode(mode);
                if (thr >= 0)       svc.setFlashlightThreshold(thr);
                return svc.flashlightJson();
            }
            if ("getLast".equals(cmd)) {
                JSONObject r = svc.getBroadcaster().getLast();
                return r == null ? new JSONObject() : r;
            }
            if ("status".equals(cmd)) {
                return svc.statusJson();
            }
        } catch (Exception ignore) {}
        return null;
    }

    private boolean handshake(InputStream in, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = 0, cur;
        while ((cur = in.read()) >= 0) {
            sb.append((char) cur);
            if (prev == '\r' && cur == '\n') {
                if (sb.length() >= 4
                        && sb.charAt(sb.length()-4) == '\r'
                        && sb.charAt(sb.length()-3) == '\n'
                        && sb.charAt(sb.length()-2) == '\r'
                        && sb.charAt(sb.length()-1) == '\n') {
                    break;
                }
            }
            prev = cur;
            if (sb.length() > 8192) return false;
        }
        String headers = sb.toString();
        if (!headers.startsWith("GET ")) return false;

        String key = null;
        String upgrade = null;
        for (String line : headers.split("\r\n")) {
            int i = line.indexOf(':');
            if (i < 0) continue;
            String k = line.substring(0, i).trim().toLowerCase();
            String v = line.substring(i + 1).trim();
            if ("sec-websocket-key".equals(k)) key = v;
            if ("upgrade".equals(k)) upgrade = v;
        }
        if (key == null || upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                    .getBytes(ISO_8859_1));
            out.flush();
            return false;
        }

        String accept = computeAccept(key);
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        out.write(resp.getBytes(ISO_8859_1));
        out.flush();
        return true;
    }

    private static String computeAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((key + GUID).getBytes(ISO_8859_1));
            return Base64.encodeToString(h, Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private static class Frame {
        int opcode;
        byte[] payload;
    }

    private Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            int a = in.read(), b = in.read();
            if (a < 0 || b < 0) return null;
            len = ((a & 0xFF) << 8) | (b & 0xFF);
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read(); if (b < 0) return null;
                l = (l << 8) | (b & 0xFF);
            }
            len = l;
        }
        if (len > 10 * 1024 * 1024) return null;

        byte[] mask = new byte[4];
        if (masked) {
            for (int i = 0; i < 4; i++) {
                int m = in.read(); if (m < 0) return null;
                mask[i] = (byte) m;
            }
        }
        byte[] payload = new byte[(int) len];
        int read = 0;
        while (read < len) {
            int n = in.read(payload, read, (int) len - read);
            if (n < 0) return null;
            read += n;
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        Frame f = new Frame();
        f.opcode = opcode;
        f.payload = payload;
        return f;
    }

    private static void writeTextFrame(OutputStream out, Object lock, String text)
            throws IOException {
        writeFrame(out, lock, 0x1, text.getBytes(UTF_8));
    }

    private static void writeFrame(OutputStream out, Object lock, int opcode, byte[] payload)
            throws IOException {
        synchronized (lock) {
            out.write(0x80 | opcode);
            int len = payload.length;
            if (len < 126) {
                out.write(len);
            } else if (len <= 0xFFFF) {
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
    }

    private void closeClient(String id, Socket s) {
        clients.remove(id);
        try { s.close(); } catch (Exception ignored) {}
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        for (Socket s : clients.values()) { try { s.close(); } catch (Exception ignored) {} }
        clients.clear();
        serverSocket = null;
        Log.i(TAG, "stopped");
    }
}