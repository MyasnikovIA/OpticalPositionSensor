package ru.miacomsoft.optical_position_sensor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SseHandler implements HttpHandler {

    private final SensorBroadcaster broadcaster;

    public SseHandler(SensorBroadcaster b) { this.broadcaster = b; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        if (HttpUtil.handleOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtil.sendError(ex, 405, "Method not allowed");
            return;
        }

        HttpUtil.handleCors(ex);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        final OutputStream out = ex.getResponseBody();
        final Object lock = new Object();

        SensorBroadcaster.Subscriber sub = new SensorBroadcaster.Subscriber() {
            @Override public void onReading(JSONObject r) throws Exception {
                StringBuilder sb = new StringBuilder();
                sb.append("event: reading\n");
                for (String line : r.toString().split("\n", -1)) {
                    sb.append("data: ").append(line).append('\n');
                }
                sb.append('\n');
                byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
                synchronized (lock) { out.write(data); out.flush(); }
            }
        };

        String id = broadcaster.subscribe(sub);
        try {
            out.write("event: ready\ndata: {\"ok\":true}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                try { Thread.sleep(15000); } catch (InterruptedException ie) { return; }
                synchronized (lock) {
                    out.write(("event: ping\ndata: {\"t\":" + System.currentTimeMillis()
                            + "}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException io) {
            // клиент отключился
        } finally {
            broadcaster.unsubscribe(id);
            try { out.close(); } catch (IOException ignored) {}
        }
    }
}