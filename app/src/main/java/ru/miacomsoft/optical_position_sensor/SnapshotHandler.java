package ru.miacomsoft.optical_position_sensor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class SnapshotHandler implements HttpHandler {

    private static final Charset ISO = Charset.forName("ISO-8859-1");
    private static final String  BOUNDARY = "sensorjpegframe";

    private final SnapshotProvider provider;

    public SnapshotHandler(SnapshotProvider provider) {
        this.provider = provider;
    }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        if (HttpUtil.handleOptions(ex)) return;

        String method = ex.getRequestMethod().toUpperCase();
        if (!"GET".equals(method)) {
            HttpUtil.sendError(ex, 405, "Method not allowed");
            return;
        }

        String path = ex.getRequestURI().getPath();
        if ("/api/sensor/snapshot.jpg".equals(path)) {
            serveSingle(ex);
            return;
        }
        if ("/api/sensor/stream.mjpg".equals(path)) {
            serveStream(ex);
            return;
        }
        HttpUtil.sendError(ex, 404, "Unknown: " + path);
    }

    private void serveSingle(HttpExchange ex) throws IOException {
        byte[] jpeg = provider.encodeOnce();
        if (jpeg == null) {
            HttpUtil.sendError(ex, 503, "No frame available (camera stopped?)");
            return;
        }
        HttpUtil.handleCors(ex);
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.getResponseHeaders().set("Cache-Control",
                "no-cache, no-store, must-revalidate");
        ex.sendResponseHeaders(200, jpeg.length);
        OutputStream out = ex.getResponseBody();
        try { out.write(jpeg); } finally { out.close(); }
    }

    private void serveStream(HttpExchange ex) throws IOException {
        HttpUtil.handleCors(ex);
        ex.getResponseHeaders().set("Content-Type",
                "multipart/x-mixed-replace; boundary=" + BOUNDARY);
        ex.getResponseHeaders().set("Cache-Control",
                "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream out = ex.getResponseBody();
        provider.acquire();
        byte[] lastSent = null;
        try {
            while (true) {
                byte[] jpeg = provider.getLatest();
                if (jpeg != null && jpeg != lastSent) {
                    out.write(("--" + BOUNDARY + "\r\n").getBytes(ISO));
                    out.write("Content-Type: image/jpeg\r\n".getBytes(ISO));
                    out.write(("Content-Length: " + jpeg.length + "\r\n\r\n")
                            .getBytes(ISO));
                    out.write(jpeg);
                    out.write("\r\n".getBytes(ISO));
                    out.flush();
                    lastSent = jpeg;
                }
                try { Thread.sleep(80); }
                catch (InterruptedException ie) { return; }
            }
        } catch (Exception e) {
            // клиент отключился — нормально
        } finally {
            provider.release();
            try { out.close(); } catch (Exception ignored) {}
        }
    }
}