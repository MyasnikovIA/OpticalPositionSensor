package ru.miacomsoft.optical_position_sensor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class SensorService extends Service {

    private static final String TAG = "SensorService";
    private static final String CHANNEL_ID = "sensor_channel";
    private static final int    NOTIF_ID   = 2001;

    public static final String ACTION_START = "ru.miacomsoft.optical_position_sensor.START";
    public static final String ACTION_STOP  = "ru.miacomsoft.optical_position_sensor.STOP";

    // ---------- HTTP port change notification ----------
    public interface HttpPortListener {
        void onHttpPortChanged(int requestedPort, int actualPort);
    }
    private static volatile HttpPortListener sPortListener;
    private static volatile int sCurrentHttpPort = 8080;

    public static int getCurrentHttpPort() { return sCurrentHttpPort; }
    public static void setHttpPortListener(HttpPortListener l) { sPortListener = l; }

    private static void notifyPortChanged(int requestedPort, int actualPort) {
        HttpPortListener l = sPortListener;
        if (l != null) {
            try { l.onHttpPortChanged(requestedPort, actualPort); }
            catch (Exception ignored) {}
        }
    }

    // ---------- state ----------
    private SensorConfig config = new SensorConfig();
    private final SensorBroadcaster broadcaster = new SensorBroadcaster();

    private Camera camera;
    private SurfaceTexture dummyTexture;
    private int previewWidth = 0, previewHeight = 0;
    private byte[] yPlane;
    private final AtomicBoolean frameBusy = new AtomicBoolean(false);

    private volatile byte[] fullFrame;
    private final Object    snapshotLock = new Object();
    private SnapshotProvider snapshotProvider;

    private HandlerThread worker;
    private Handler workerHandler;

    private Handler uiHandler;
    private Runnable uiTick;
    private static final long UI_TICK_MS = 15000L;

    private OpticalFlowTracker tracker = new OpticalFlowTracker();
    private FlashlightController flashlight;

    private HttpApiServer   httpServer;
    private WebSocketServer wsServer;
    private TelnetServer    telnetServer;
    private PushClient      pushClient;

    private volatile boolean sensorRunning = false;
    private volatile String  lastCameraError = "";
    private long seq = 0;
    private long lastTimestamp = 0;

    // ---- publish filter ----
    private long   lastPublishMs   = 0L;
    private String lastFlashState  = "";
    private long   skippedReadings = 0L;

    private final SimpleDateFormat iso = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    // ==============================================================
    // Lifecycle
    // ==============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        uiHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("Запуск…"));

        try {
            config = ConfigStore.load(getApplicationContext());
            Log.i(TAG, "Config loaded: enabled=" + config.enabled
                    + ", autoStart=" + config.autoStart
                    + ", serverEnabled=" + config.serverEnabled
                    + ", httpPort=" + config.httpPort
                    + ", flashMode=" + config.flashlightMode);
        } catch (Exception e) {
            Log.e(TAG, "config load", e);
        }

        snapshotProvider = new SnapshotProvider(this,
                config.cameraPreviewFps, config.cameraPreviewQuality);

        if (config.enabled && config.serverEnabled) startServers();
        if (config.enabled && config.autoStart)     startSensorOnly();
        if (config.enabled && config.clientEnabled) startClient();

        updateNotification(buildStatusLine());

        if (uiTick == null) {
            uiTick = new Runnable() {
                @Override public void run() {
                    updateNotification(buildStatusLine());
                    if (uiHandler != null) uiHandler.postDelayed(this, UI_TICK_MS);
                }
            };
            uiHandler.postDelayed(uiTick, UI_TICK_MS);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ==============================================================
    // Config / status
    // ==============================================================

    public SensorConfig getConfig() { return config; }
    public SensorBroadcaster getBroadcaster() { return broadcaster; }
    public SnapshotProvider getSnapshotProvider() { return snapshotProvider; }

    public synchronized void patchConfig(JSONObject patch) {
        config.applyPatch(patch);
        try { ConfigStore.save(getApplicationContext(), config); }
        catch (Exception e) { Log.w(TAG, "config save", e); }

        if (flashlight != null && patch.has("flashlightMode")) {
            flashlight.setMode(config.flashlightMode);
        }
        if (flashlight != null && patch.has("lightThreshold")) {
            flashlight.setThreshold(config.lightThreshold);
        }
        if (patch.has("clientEnabled") || patch.has("clientProtocol")
                || patch.has("clientServerUrl") || patch.has("clientPath")
                || patch.has("clientToken") || patch.has("clientIntervalMs")) {
            restartClient();
        }
    }

    // ==============================================================
    // HTTP port change
    // ==============================================================

    public synchronized boolean scheduleHttpRestart(final int newPort) {
        if (newPort <= 0 || newPort > 65535) return false;
        final int oldPort = config.httpPort;
        if (newPort == oldPort && httpServer != null) return true;

        config.httpPort = newPort;
        try { ConfigStore.save(getApplicationContext(), config); }
        catch (Exception e) { Log.w(TAG, "config save (httpPort)", e); }

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                doHttpRestart(oldPort, newPort);
            }
        }, "HttpRestart");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private synchronized void doHttpRestart(int oldPort, int newPort) {
        Log.i(TAG, "doHttpRestart: " + oldPort + " -> " + newPort);

        if (httpServer != null) {
            try { httpServer.stop(); } catch (Exception ignored) {}
            httpServer = null;
        }

        int actualPort = oldPort;

        if (config.restEnabled || config.sseEnabled) {
            HttpApiServer srv = new HttpApiServer(getApplicationContext(), this);
            try {
                srv.start(newPort, config.httpHost);
                httpServer = srv;
                sCurrentHttpPort = newPort;
                actualPort = newPort;
                Log.i(TAG, "HTTP restarted on " + newPort);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start HTTP on " + newPort, e);

                if (newPort != oldPort) {
                    config.httpPort = oldPort;
                    try { ConfigStore.save(getApplicationContext(), config); }
                    catch (Exception ignored) {}

                    HttpApiServer srvOld = new HttpApiServer(getApplicationContext(), this);
                    try {
                        srvOld.start(oldPort, config.httpHost);
                        httpServer = srvOld;
                        sCurrentHttpPort = oldPort;
                        actualPort = oldPort;
                        Log.i(TAG, "Rolled back to " + oldPort);
                    } catch (Exception e2) {
                        Log.e(TAG, "Rollback failed", e2);
                    }
                }
            }
        } else {
            sCurrentHttpPort = newPort;
            actualPort = newPort;
        }

        notifyPortChanged(newPort, actualPort);
        updateNotification(buildStatusLine());
    }

    // ==============================================================
    // Servers
    // ==============================================================

    private void startServers() {
        if (config.restEnabled || config.sseEnabled) {
            httpServer = new HttpApiServer(getApplicationContext(), this);
            try {
                httpServer.start(config.httpPort, config.httpHost);
                sCurrentHttpPort = config.httpPort;
            } catch (Exception e) {
                Log.e(TAG, "HTTP start", e);
            }
        }
        if (config.wsEnabled) {
            wsServer = new WebSocketServer(this);
            try { wsServer.start(config.wsPort); }
            catch (Exception e) { Log.e(TAG, "WS start", e); }
        }
        if (config.telnetEnabled) {
            telnetServer = new TelnetServer(this);
            try { telnetServer.start(config.telnetPort); }
            catch (Exception e) { Log.e(TAG, "Telnet start", e); }
        }
    }

    private void stopServers() {
        if (httpServer != null)   { httpServer.stop();   httpServer = null; }
        if (wsServer != null)     { wsServer.stop();     wsServer = null; }
        if (telnetServer != null) { telnetServer.stop(); telnetServer = null; }
    }

    private void startClient() {
        pushClient = new PushClient(broadcaster);
        pushClient.start(config);
    }

    private void restartClient() {
        if (pushClient != null) { pushClient.stop(); pushClient = null; }
        if (config.clientEnabled) startClient();
    }

    // ==============================================================
    // Camera
    // ==============================================================

    public synchronized void startSensorOnly() {
        if (sensorRunning) return;

        tracker.configure(config.targetWidth, config.searchRadius,
                config.blockSize, config.minContrast, config.maxSadPx);

        if (worker == null) {
            worker = new HandlerThread("SensorWorker");
            worker.start();
            workerHandler = new Handler(worker.getLooper());
        }

        if (!openCamera()) {
            lastCameraError = "Не удалось открыть камеру. Закройте другие приложения с камерой и нажмите «Запустить».";
            Log.e(TAG, "startSensorOnly: camera open failed");
            return;
        }

        try {
            flashlight = new FlashlightController(getApplicationContext());
            flashlight.init();
            flashlight.setThreshold(config.lightThreshold);
            flashlight.setMode(config.flashlightMode);
            flashlight.setCamera(camera);
        } catch (Exception e) {
            Log.w(TAG, "Flashlight init failed: " + e.getMessage());
            flashlight = null;
        }

        sensorRunning = true;
        lastCameraError = "";
        lastPublishMs   = 0L;
        lastFlashState  = "";
        skippedReadings = 0L;

        Log.i(TAG, "Sensor started " + previewWidth + "x" + previewHeight
                + ", flashlight=" + (flashlight != null && flashlight.isAvailable()));
        updateNotification(buildStatusLine());
    }

    public synchronized void stopSensorOnly() {
        sensorRunning = false;

        if (flashlight != null) {
            try { flashlight.release(); } catch (Exception ignored) {}
            flashlight = null;
        }

        synchronized (snapshotLock) { fullFrame = null; }

        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
        if (dummyTexture != null) {
            try { dummyTexture.release(); } catch (Exception ignored) {}
            dummyTexture = null;
        }
        if (worker != null) { worker.quit(); worker = null; workerHandler = null; }

        Log.i(TAG, "stopSensorOnly done");
        updateNotification(buildStatusLine());
    }

    private boolean openCamera() {
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
        if (dummyTexture != null) {
            try { dummyTexture.release(); } catch (Exception ignored) {}
            dummyTexture = null;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            Log.i(TAG, "Camera.open() attempt " + attempt + "/3");
            try {
                camera = Camera.open();
                if (camera == null) { sleepMs(400); continue; }
                if (!configureCamera()) {
                    try { camera.release(); } catch (Exception ignored) {}
                    camera = null;
                    sleepMs(400);
                    continue;
                }
                try {
                    dummyTexture = new SurfaceTexture(0);
                    camera.setPreviewTexture(dummyTexture);
                    Log.i(TAG, "dummy SurfaceTexture attached");
                } catch (Exception e) {
                    Log.w(TAG, "setPreviewTexture failed: " + e.getMessage());
                }
                try {
                    camera.startPreview();
                } catch (Exception e) {
                    Log.e(TAG, "startPreview failed", e);
                    try { camera.release(); } catch (Exception ignored) {}
                    camera = null;
                    if (dummyTexture != null) {
                        try { dummyTexture.release(); } catch (Exception ignored) {}
                        dummyTexture = null;
                    }
                    sleepMs(400);
                    continue;
                }
                Log.i(TAG, "Camera opened on attempt " + attempt
                        + ": " + previewWidth + "x" + previewHeight);
                return true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Camera.open() attempt " + attempt + " failed", e);
                if (camera != null) { try { camera.release(); } catch (Exception ignored) {} camera = null; }
                if (dummyTexture != null) { try { dummyTexture.release(); } catch (Exception ignored) {} dummyTexture = null; }
                sleepMs(400);
            }
        }
        Log.e(TAG, "openCamera: all attempts failed");
        return false;
    }

    private boolean configureCamera() {
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.Size size = pickPreviewSize(p.getSupportedPreviewSizes());
            previewWidth = size.width;
            previewHeight = size.height;
            p.setPreviewSize(previewWidth, previewHeight);
            p.setPreviewFormat(ImageFormat.NV21);

            try {
                List<int[]> ranges = p.getSupportedPreviewFpsRange();
                if (ranges != null && !ranges.isEmpty()) {
                    int[] best = ranges.get(0);
                    for (int[] r : ranges) {
                        if (r[1] > best[1] || (r[1] == best[1] && r[0] < best[0])) best = r;
                    }
                    p.setPreviewFpsRange(best[0], best[1]);
                }
            } catch (Exception ignore) {}

            try {
                List<String> focus = p.getSupportedFocusModes();
                if (focus != null) {
                    if (focus.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
                        p.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                    else if (focus.contains(Camera.Parameters.FOCUS_MODE_FIXED))
                        p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
                }
            } catch (Exception ignore) {}

            try {
                if (p.isVideoStabilizationSupported()) {
                    p.setVideoStabilization(false);
                }
            } catch (Exception ignore) {}

            camera.setParameters(p);

            int bufSize = previewWidth * previewHeight
                    * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            yPlane = new byte[previewWidth * previewHeight];
            tracker.reset(previewWidth, previewHeight);

            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override public void onPreviewFrame(byte[] data, Camera cam) {
                    onFrame(data, cam);
                }
            });
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "configureCamera exception", e);
            return false;
        }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private Camera.Size pickPreviewSize(List<Camera.Size> sizes) {
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (s.width < 128 || s.height < 96) continue;
            if (best == null) { best = s; continue; }
            if (s.width * s.height < best.width * best.height) best = s;
        }
        return best != null ? best : sizes.get(0);
    }

    // ==============================================================
    // Frame processing
    // ==============================================================

    private void onFrame(byte[] data, Camera cam) {
        if (cam == null) return;
        if (data == null || yPlane == null) {
            if (data != null) cam.addCallbackBuffer(data);
            return;
        }
        if (!frameBusy.compareAndSet(false, true)) {
            cam.addCallbackBuffer(data);
            return;
        }

        System.arraycopy(data, 0, yPlane, 0, yPlane.length);

        synchronized (snapshotLock) {
            if (fullFrame == null || fullFrame.length != data.length) {
                fullFrame = new byte[data.length];
            }
            System.arraycopy(data, 0, fullFrame, 0, data.length);
        }

        cam.addCallbackBuffer(data);

        final long ts = System.currentTimeMillis();
        Handler h = workerHandler;
        if (h != null) {
            h.post(new Runnable() {
                @Override public void run() { processFrame(ts); }
            });
        } else {
            frameBusy.set(false);
        }
    }

    public byte[] encodeSnapshotJpeg(int quality) {
        byte[] frameCopy;
        int w, h;
        synchronized (snapshotLock) {
            if (fullFrame == null || previewWidth <= 0 || previewHeight <= 0) return null;
            w = previewWidth;
            h = previewHeight;
            frameCopy = new byte[fullFrame.length];
            System.arraycopy(fullFrame, 0, frameCopy, 0, fullFrame.length);
        }

        int rotation = config.cameraPreviewRotation;
        rotation = ((rotation % 360) + 360) % 360;
        if (rotation != 90 && rotation != 180 && rotation != 270) rotation = 0;

        try {
            android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                    frameCopy, ImageFormat.NV21, w, h, null);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), quality, baos);
            byte[] jpeg0 = baos.toByteArray();

            if (rotation == 0) return jpeg0;

            android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(
                    jpeg0, 0, jpeg0.length, null);
            if (src == null) return jpeg0;

            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rotation);
            android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                    src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (rotated != src) { try { src.recycle(); } catch (Exception ignored) {} }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out);
            try { rotated.recycle(); } catch (Exception ignored) {}
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "encodeSnapshotJpeg", t);
            return null;
        }
    }

    private void processFrame(long timestamp) {
        try {
            OpticalFlowTracker.Result r = tracker.update(yPlane, previewWidth, previewHeight);
            seq++;

            // 1. Оси по настройкам монтажа
            double dx = r.dxPx;
            double dy = r.dyPx;

            int rot = ((config.cameraMountRotationDeg % 360) + 360) % 360;
            if (rot == 90)       { double t = dx; dx = -dy; dy = t; }
            else if (rot == 180) { dx = -dx; dy = -dy; }
            else if (rot == 270) { double t = dx; dx = dy; dy = -t; }
            if (config.invertDx) dx = -dx;
            if (config.invertDy) dy = -dy;

            double distance     = Math.hypot(dx, dy);
            double directionDeg = normalizeDeg(Math.toDegrees(Math.atan2(dy, dx)));

            // 2. Формируем JSON
            JSONObject reading = new JSONObject();
            reading.put("seq", seq);
            reading.put("timestamp_ms", timestamp);
            reading.put("datetime", iso.format(new Date(timestamp)));
            long dt = (lastTimestamp > 0 && timestamp >= lastTimestamp)
                    ? (timestamp - lastTimestamp) : 0;
            reading.put("dt_ms", dt);
            lastTimestamp = timestamp;

            JSONObject frame = new JSONObject();
            frame.put("width", previewWidth);
            frame.put("height", previewHeight);
            frame.put("downscale", tracker.getDownscale());
            reading.put("frame", frame);

            JSONObject shift = new JSONObject();
            shift.put("valid",         r.valid);
            shift.put("dx_px",         round(dx, 3));
            shift.put("dy_px",         round(dy, 3));
            shift.put("distance_px",   round(distance, 3));
            shift.put("direction_deg", round(directionDeg, 2));
            shift.put("rotation_deg",  round(r.rotationDeg, 3));
            shift.put("mount_rotation_deg", rot);
            shift.put("invert_dx",     config.invertDx);
            shift.put("invert_dy",     config.invertDy);
            reading.put("shift", shift);

            JSONObject q = new JSONObject();
            q.put("blocks_used",  r.blocksUsed);
            q.put("blocks_total", r.blocksTotal);
            q.put("confidence",   round(r.confidence, 3));
            reading.put("quality", q);

            if (flashlight != null) {
                JSONObject f = new JSONObject();
                f.put("mode",      flashlight.getMode());
                f.put("on",        flashlight.isOn());
                f.put("available", flashlight.isAvailable());
                f.put("lux",       flashlight.getLux());
                reading.put("flashlight", f);
            }

            reading.put("proc_ms", round(r.processNs / 1e6, 2));

            // 3. Решаем, публиковать или нет
            boolean shouldPublish;
            String  reason;

            if (!config.publishOnlyOnChange) {
                shouldPublish = true; reason = "always";
            } else if (lastPublishMs == 0L) {
                shouldPublish = true; reason = "first";
            } else if (!r.valid) {
                shouldPublish = false; reason = "invalid";
            } else {
                boolean moved = Math.abs(dx) >= config.publishMotionThresholdPx
                        || Math.abs(dy) >= config.publishMotionThresholdPx
                        || Math.abs(r.rotationDeg) >= config.publishRotationThresholdDeg;
                String newFlashState = buildFlashState();
                boolean flashChanged = !newFlashState.equals(lastFlashState);
                boolean heartbeat = config.publishHeartbeatMs > 0
                        && (timestamp - lastPublishMs) >= config.publishHeartbeatMs;

                if (moved)              { shouldPublish = true;  reason = "motion"; }
                else if (flashChanged)  { shouldPublish = true;  reason = "flashlight"; }
                else if (heartbeat)     { shouldPublish = true;  reason = "heartbeat"; }
                else                    { shouldPublish = false; reason = "unchanged"; }
            }

            reading.put("publish_reason", reason);

            if (shouldPublish) {
                broadcaster.publish(reading);
                lastPublishMs  = timestamp;
                lastFlashState = buildFlashState();
            } else {
                broadcaster.setLatest(reading);
                skippedReadings++;
            }
        } catch (Throwable t) {
            Log.e(TAG, "processFrame", t);
        } finally {
            frameBusy.set(false);
        }
    }

    private static double round(double v, int d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double p = Math.pow(10, d);
        return Math.round(v * p) / p;
    }

    private static double normalizeDeg(double a) {
        a = a % 360.0;
        if (a < 0) a += 360.0;
        return a;
    }

    private String buildFlashState() {
        if (flashlight == null) return "none";
        return flashlight.getMode()
                + "|" + (flashlight.isOn() ? "1" : "0")
                + "|" + (flashlight.isAvailable() ? "1" : "0");
    }

    // ==============================================================
    // Flashlight helpers
    // ==============================================================

    public synchronized void setFlashlightMode(String mode) {
        config.flashlightMode = mode;
        if (flashlight != null) {
            flashlight.setMode(mode);
            Log.i(TAG, "setFlashlightMode: mode=" + mode
                    + " on=" + flashlight.isOn()
                    + " applied=" + flashlight.getAppliedMode()
                    + " err=" + flashlight.getLastError());
        }
        try { ConfigStore.save(getApplicationContext(), config); }
        catch (Exception e) { Log.w(TAG, "save", e); }
    }

    public synchronized void setFlashlightThreshold(int lux) {
        config.lightThreshold = lux;
        if (flashlight != null) flashlight.setThreshold(lux);
        try { ConfigStore.save(getApplicationContext(), config); }
        catch (Exception e) { Log.w(TAG, "save", e); }
    }

    public JSONObject flashlightJson() {
        try {
            JSONObject o = new JSONObject();
            if (flashlight == null) {
                o.put("available", false);
                o.put("running", false);
                o.put("lastError", "flashlight controller not initialized");
                return o;
            }
            o.put("available", flashlight.isAvailable());
            o.put("running", sensorRunning);
            o.put("mode", flashlight.getMode());
            o.put("on", flashlight.isOn());
            o.put("lux", flashlight.getLux());
            o.put("threshold", flashlight.getThreshold());
            o.put("lastError", flashlight.getLastError());
            o.put("appliedMode", flashlight.getAppliedMode());
            List<String> modes = flashlight.getSupportedModes();
            if (modes != null) o.put("supportedModes", new org.json.JSONArray(modes));
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    public JSONObject statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("sensorRunning",   sensorRunning);
            o.put("lastCameraError", lastCameraError);
            o.put("previewWidth",    previewWidth);
            o.put("previewHeight",   previewHeight);

            o.put("httpPort",       config.httpPort);
            o.put("httpPortActive", sCurrentHttpPort);
            o.put("wsPort",         config.wsEnabled     ? config.wsPort     : -1);
            o.put("telnetPort",     config.telnetEnabled ? config.telnetPort : -1);

            o.put("httpActive",     httpServer   != null);
            o.put("wsActive",       wsServer     != null);
            o.put("telnetActive",   telnetServer != null);
            o.put("wsClients",      wsServer     == null ? 0 : wsServer.getClientCount());
            o.put("telnetClients",  telnetServer == null ? 0 : telnetServer.getClientCount());

            o.put("subscribers",    broadcaster.count());
            o.put("lastPublishMs",  broadcaster.getLastPublishMs());
            o.put("clientEnabled",  pushClient != null && pushClient.isRunning());

            // === СЕТЬ (WifiManager + NetworkInterface) ===
            Context ctx = getApplicationContext();
            o.put("localIp",  HttpUtil.getLocalIp(ctx));
            o.put("localIps", HttpUtil.getAllLocalIps(ctx));

            o.put("flashlight", flashlightJson());

            o.put("autoStart",        config.autoStart);
            o.put("sensorEnabledCfg", config.enabled);

            o.put("cameraPreviewEnabled",  config.cameraPreviewEnabled);
            o.put("cameraPreviewFps",      config.cameraPreviewFps);
            o.put("cameraPreviewQuality",  config.cameraPreviewQuality);
            o.put("cameraPreviewRotation", config.cameraPreviewRotation);

            o.put("cameraMountRotationDeg", config.cameraMountRotationDeg);
            o.put("invertDx",               config.invertDx);
            o.put("invertDy",               config.invertDy);

            o.put("publishOnlyOnChange",         config.publishOnlyOnChange);
            o.put("publishMotionThresholdPx",    config.publishMotionThresholdPx);
            o.put("publishRotationThresholdDeg", config.publishRotationThresholdDeg);
            o.put("publishHeartbeatMs",          config.publishHeartbeatMs);
            o.put("publishSeq",                  broadcaster.getPublishSeq());
            o.put("skippedReadings",             skippedReadings);

            boolean hasFrame;
            synchronized (snapshotLock) { hasFrame = (fullFrame != null); }
            o.put("hasFrame", hasFrame);

            return o;
        } catch (Exception e) {
            Log.e(TAG, "statusJson", e);
            return new JSONObject();
        }
    }

    private String buildStatusLine() {
        String ip = HttpUtil.getLocalIp(getApplicationContext());
        String base = "http://" + ip + ":" + sCurrentHttpPort + "/";
        if (sensorRunning) {
            return base + "  ·  " + previewWidth + "x" + previewHeight;
        }
        if (!lastCameraError.isEmpty()) {
            return base + "  ·  камера: ошибка";
        }
        return base + "  ·  камера остановлена";
    }

    // ==============================================================
    // Shutdown
    // ==============================================================

    private synchronized void shutdown() {
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
            uiHandler = null;
        }
        uiTick = null;
        stopSensorOnly();
        if (pushClient != null) { pushClient.stop(); pushClient = null; }
        stopServers();
    }

    // ==============================================================
    // Notification
    // ==============================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Optical Sensor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, piFlags);

        Intent stop = new Intent(this, SensorService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        String ip  = HttpUtil.getLocalIp(getApplicationContext());
        String url = "http://" + ip + ":" + sCurrentHttpPort + "/";

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("OpticalSensor  " + url)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Адрес: " + url + "\n" + text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopPi);
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}