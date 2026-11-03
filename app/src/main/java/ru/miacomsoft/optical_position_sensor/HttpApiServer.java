package ru.miacomsoft.optical_position_sensor;

import android.content.Context;
import android.util.Log;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Собирает REST + SSE + статику в одном HttpServer
 * (из com.sun.net.httpserver, уже скопированного в проект).
 */
public class HttpApiServer {

    private static final String TAG = "HttpApiServer";

    private HttpServer server;
    private final SensorService svc;
    private final Context context;

    public HttpApiServer(Context ctx, SensorService svc) {
        this.context = ctx.getApplicationContext();
        this.svc = svc;
    }

    public void start(int port, String host) throws Exception {
        if (server != null) return;

        server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/api/sensor/sse",          new SseHandler(svc.getBroadcaster()));
        server.createContext("/api/sensor/snapshot.jpg", new SnapshotHandler(svc.getSnapshotProvider()));
        server.createContext("/api/sensor/stream.mjpg",  new SnapshotHandler(svc.getSnapshotProvider()));
        server.createContext("/api/sensor/",             new RestHandler(svc));
        server.createContext("/",                        new StaticHandler(context));
        server.createContext("/api/sensor/snapshot.jpg", new SnapshotHandler(svc.getSnapshotProvider()));
        server.createContext("/api/sensor/stream.mjpg",  new SnapshotHandler(svc.getSnapshotProvider()));
        server.createContext("/api/sensor/debug.jpg", new SnapshotHandler(svc.getSnapshotProvider()));
        server.setExecutor(Executors.newFixedThreadPool(8));   // было 6
        server.start();
        Log.i(TAG, "Listening on " + host + ":" + port);
    }

    public void stop() {
        if (server != null) {
            try { server.stop(0); } catch (Exception ignored) {}
            server = null;
        }
    }
}