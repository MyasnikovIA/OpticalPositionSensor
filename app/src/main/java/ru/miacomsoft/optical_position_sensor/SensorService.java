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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    // ---- Накопленный поворот прибора (для отображения «куда смотрит») ----
    private volatile double totalRotationDeg = 0.0;

    // ---- Трейл (след движения по полю) ----
    private final Object trailLock = new Object();
    private final List<double[]> trail = new ArrayList<double[]>(); // {x_abs, y_abs, rotDeg, t_ms}
    private static final int    MAX_TRAIL_POINTS = 1000;
    private static final double TRAIL_MIN_DIST   = 1.0;              // px между точками
    private double lastTrailX = 0.0, lastTrailY = 0.0;

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
    private volatile OpticalFlowTracker.Result lastFlowResult;
    private final Object flowResultLock = new Object();

    private final Object positionLock = new Object();
    private double posX = 0.0, posY = 0.0;
    private double zeroX = 0.0, zeroY = 0.0;
    private long   zeroSetAtMs = 0L;
    private volatile boolean forcePublish = false;

    private final SimpleDateFormat iso = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    // ---- Калибровка покоя ----
    private final Object calibLock = new Object();
    private final List<double[]> calibSamples = new ArrayList<>();
    private volatile boolean calibActive = false;
    private volatile boolean calibPendingZero = false;
    private volatile long    calibStartMs = 0;
    private volatile String  calibStatus = "idle";
    private static final int    CALIB_TARGET_SAMPLES = 60;    // ~2 сек при 30fps
    private static final long   CALIB_TIMEOUT_MS      = 5000;
    private static final double CALIB_REJECT_PX       = 5.0;  // «пользователь двинул»

    // ---- Список разрешений камеры (кэш) ----
    private volatile org.json.JSONArray cachedCameraSizes = null;

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
    /**
     * Текущее положение становится точкой отсчёта (0,0).
     * Публикуем принудительно, чтобы UI обновился даже без движения.
     */
    public synchronized void setZero() {
        synchronized (positionLock) {
            zeroX = posX;
            zeroY = posY;
            zeroSetAtMs = System.currentTimeMillis();
        }
        // Трейл и totalRotationDeg намеренно НЕ сбрасываются:
        // setZero лишь сдвигает начало координат, история и ориентация сохраняются.
        forcePublish = true;
        Log.i(TAG, "Zero set at (" + zeroX + ", " + zeroY + ")");
    }

    /**
     * Обнуляет и накопленную позицию, и точку отсчёта.
     * Полезно при дрейфе (когда pos «уплыла» из-за накопления ошибок).
     */
    public synchronized void resetPosition() {
        synchronized (positionLock) {
            posX = 0.0; posY = 0.0;
            zeroX = 0.0; zeroY = 0.0;
            zeroSetAtMs = System.currentTimeMillis();
        }
        totalRotationDeg = 0.0;
        synchronized (trailLock) {
            trail.clear();
            trail.add(new double[]{0, 0, 0, System.currentTimeMillis()});
            lastTrailX = 0; lastTrailY = 0;
        }
        forcePublish = true;
        Log.i(TAG, "Position and rotation reset");
    }

    private JSONObject positionJson() {
        try {
            double rx, ry, px, py, zx, zy;
            long t;
            synchronized (positionLock) {
                rx = posX - zeroX;
                ry = posY - zeroY;
                px = posX; py = posY;
                zx = zeroX; zy = zeroY;
                t  = zeroSetAtMs;
            }
            JSONObject o = new JSONObject();
            o.put("x_px", round(rx, 3));
            o.put("y_px", round(ry, 3));
            o.put("distance_from_zero_px", round(Math.hypot(rx, ry), 3));
            o.put("direction_from_zero_deg",
                    round(normalizeDeg(Math.toDegrees(Math.atan2(ry, rx))), 2));
            o.put("pos_raw_x_px",  round(px, 3));
            o.put("pos_raw_y_px",  round(py, 3));
            o.put("zero_x_px",     round(zx, 3));
            o.put("zero_y_px",     round(zy, 3));
            o.put("zero_set_at_ms", t);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public JSONObject positionJsonPublic() { return positionJson(); }
// ==============================================================
// Калибровка покоя (static-point calibration)
// ==============================================================

    public synchronized void startCalibStatic(boolean thenZero) {
        synchronized (calibLock) {
            calibSamples.clear();
            calibActive = true;
            calibPendingZero = thenZero;
            calibStartMs = System.currentTimeMillis();
            calibStatus = "sampling";
        }
        Log.i(TAG, "Calib start, thenZero=" + thenZero
                + ", target=" + CALIB_TARGET_SAMPLES);
    }

    public synchronized void applyCalibNow() {
        synchronized (calibLock) {
            if (calibSamples.size() < 5) {
                calibStatus = "too_few_samples";
                calibActive = false;
                return;
            }
            applyCalibFromSamples();
        }
    }

    public synchronized void clearCalibStatic() {
        synchronized (calibLock) {
            calibSamples.clear();
            calibActive = false;
            calibPendingZero = false;
            calibStatus = "idle";
            config.posBiasX      = 0.0;
            config.posBiasY      = 0.0;
            config.posDeadbandPx = 0.15;
            config.posCalibrated = false;
            try { ConfigStore.save(getApplicationContext(), config); }
            catch (Exception e) { Log.w(TAG, "save calib clear", e); }
        }
        Log.i(TAG, "Calibration cleared");
    }

    private void collectCalibSample(double dx, double dy) {
        synchronized (calibLock) {
            if (!calibActive) return;

            // Аборт при явном движении
            if (Math.hypot(dx, dy) > CALIB_REJECT_PX) {
                calibSamples.clear();
                calibActive = false;
                calibPendingZero = false;
                calibStatus = "aborted_motion";
                Log.w(TAG, "Calib aborted: motion detected");
                return;
            }

            calibSamples.add(new double[]{dx, dy});

            boolean targetReached = calibSamples.size() >= CALIB_TARGET_SAMPLES;
            boolean timeout = (System.currentTimeMillis() - calibStartMs) >= CALIB_TIMEOUT_MS;
            if (targetReached || timeout) {
                applyCalibFromSamples();
            }
        }
    }

    private void applyCalibFromSamples() {
        int n = calibSamples.size();
        if (n < 3) {
            calibStatus = "error_too_few";
            calibActive = false;
            return;
        }

        double sumX = 0, sumY = 0;
        for (double[] s : calibSamples) { sumX += s[0]; sumY += s[1]; }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double varX = 0, varY = 0;
        for (double[] s : calibSamples) {
            varX += (s[0] - meanX) * (s[0] - meanX);
            varY += (s[1] - meanY) * (s[1] - meanY);
        }
        double stdX = Math.sqrt(varX / Math.max(1, n - 1));
        double stdY = Math.sqrt(varY / Math.max(1, n - 1));
        double std  = Math.max(stdX, stdY);

        config.posBiasX      = meanX;
        config.posBiasY      = meanY;
        // 3-сигма, но не меньше 0.05 и не больше 2.0
        config.posDeadbandPx = Math.max(0.05, Math.min(2.0, 3.0 * std));
        config.posCalibrated = true;

        try { ConfigStore.save(getApplicationContext(), config); }
        catch (Exception e) { Log.w(TAG, "save calib", e); }

        boolean setZeroAfter = calibPendingZero;
        calibPendingZero = false;
        calibSamples.clear();
        calibActive = false;
        calibStatus = "done";

        if (setZeroAfter) {
            synchronized (positionLock) {
                zeroX = posX;
                zeroY = posY;
                zeroSetAtMs = System.currentTimeMillis();
            }
            forcePublish = true;
        }

        Log.i(TAG, "Calib done: n=" + n
                + " bias=(" + round(meanX,3) + "," + round(meanY,3) + ")"
                + " std=(" + round(stdX,3) + "," + round(stdY,3) + ")"
                + " deadband=" + round(config.posDeadbandPx,3)
                + " thenZero=" + setZeroAfter);
    }

    public JSONObject calibStatusJson() {
        try {
            JSONObject o = new JSONObject();
            synchronized (calibLock) {
                o.put("status", calibStatus);
                o.put("active", calibActive);
                o.put("samples", calibSamples.size());
                o.put("targetSamples", CALIB_TARGET_SAMPLES);
                o.put("pendingZero", calibPendingZero);
            }
            o.put("posBiasX",        config.posBiasX);
            o.put("posBiasY",        config.posBiasY);
            o.put("posDeadbandPx",   config.posDeadbandPx);
            o.put("posCalibrated",   config.posCalibrated);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }
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

        tracker.configure(
                config.targetWidth, config.searchRadius, config.blockSize,
                config.flowMinVariance, config.flowMinGradient, config.maxSadPx,
                config.flowSubpixel, config.flowTemporalSmoothing,
                config.flowTemporalAlpha, config.flowMaxMotionPx,
                config.trackerContrast);                         // <<< контраст

        if (worker == null) {
            worker = new HandlerThread("SensorWorker");
            worker.start();
            workerHandler = new Handler(worker.getLooper());
        }

        if (!openCamera()) {
            lastCameraError = "Не удалось открыть камеру...";
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
        synchronized (positionLock) {
            posX = 0.0; posY = 0.0;
            zeroX = 0.0; zeroY = 0.0;
            zeroSetAtMs = System.currentTimeMillis();
        }
        totalRotationDeg = 0.0;
        synchronized (trailLock) {
            trail.clear();
            trail.add(new double[]{0, 0, 0, System.currentTimeMillis()});
            lastTrailX = 0; lastTrailY = 0;
        }


        synchronized (calibLock) {
            calibSamples.clear();
            calibActive = false;
            calibPendingZero = false;
            calibStatus = config.posCalibrated ? "done" : "idle";
        }
        forcePublish = true;

        Log.i(TAG, "Sensor started ...");
        updateNotification(buildStatusLine());
    }
    public JSONObject mapJson() {
        try {
            JSONObject o = new JSONObject();
            double rx, ry, zeroXc, zeroYc;
            long   t0;
            synchronized (positionLock) {
                rx = posX - zeroX;
                ry = posY - zeroY;
                zeroXc = zeroX;
                zeroYc = zeroY;
                t0 = zeroSetAtMs;
            }

            o.put("x_px",              round(rx, 3));   // + = вправо
            o.put("y_px",              round(ry, 3));   // + = вперёд
            o.put("rotation_deg",      round(normalizeDeg(totalRotationDeg), 2));
            o.put("zero_set_at_ms",    t0);
            o.put("calibrated",        config.posCalibrated);
            o.put("deadband_px",       config.posDeadbandPx);
            o.put("rot_deadband_deg",  config.rotDeadbandDeg);
            o.put("sensor_running",    sensorRunning);

            org.json.JSONArray arr = new org.json.JSONArray();
            synchronized (trailLock) {
                for (double[] pt : trail) {
                    JSONObject p = new JSONObject();
                    p.put("x",   round(pt[0] - zeroXc, 2));   // + = вправо
                    p.put("y",   round(pt[1] - zeroYc, 2));   // + = вперёд
                    p.put("rot", round(normalizeDeg(pt[2]), 2));
                    p.put("t",   (long) pt[3]);
                    arr.put(p);
                }
                o.put("trail_count", trail.size());
            }
            o.put("trail", arr);
            return o;
        } catch (Exception e) {
            Log.e(TAG, "mapJson", e);
            return new JSONObject();
        }
    }

    public JSONObject mapJsonPublic() { return mapJson(); }

    public synchronized void stopSensorOnly() {
        sensorRunning = false;
        synchronized (positionLock) {
            posX = 0.0; posY = 0.0;
            zeroX = 0.0; zeroY = 0.0;
            zeroSetAtMs = System.currentTimeMillis();
        }
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
            if (cachedCameraSizes == null) {
                List<Camera.Size> sizes = p.getSupportedPreviewSizes();
                if (sizes != null) {
                    java.util.ArrayList<Camera.Size> sorted =
                            new java.util.ArrayList<Camera.Size>(sizes);
                    Collections.sort(sorted, new Comparator<Camera.Size>() {
                        @Override public int compare(Camera.Size a, Camera.Size b) {
                            return a.width * a.height - b.width * b.height;
                        }
                    });
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (Camera.Size s : sorted) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("width", s.width);
                            o.put("height", s.height);
                            o.put("area", s.width * s.height);
                            arr.put(o);
                        } catch (Exception ignored) {}
                    }
                    cachedCameraSizes = arr;
                }
            }
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
    public org.json.JSONArray getCameraSizes() {
        return cachedCameraSizes != null
                ? cachedCameraSizes : new org.json.JSONArray();
    }
    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private Camera.Size pickPreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;

        // Явный выбор из конфига
        if (config.cameraPreviewWidth > 0 && config.cameraPreviewHeight > 0) {
            for (Camera.Size s : sizes) {
                if (s.width == config.cameraPreviewWidth
                        && s.height == config.cameraPreviewHeight) {
                    Log.i(TAG, "Preview size (config): " + s.width + "x" + s.height);
                    return s;
                }
            }
            Log.w(TAG, "Requested " + config.cameraPreviewWidth + "x"
                    + config.cameraPreviewHeight + " not supported; using lowest");
        }

        // Auto: самое низкое (>= 128x96)
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (s.width < 128 || s.height < 96) continue;
            if (best == null) { best = s; continue; }
            if (s.width * s.height < best.width * best.height) best = s;
        }
        if (best == null) best = sizes.get(0);
        Log.i(TAG, "Preview size (auto/lowest): " + best.width + "x" + best.height);
        return best;
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
            w = previewWidth; h = previewHeight;
            frameCopy = new byte[fullFrame.length];
            System.arraycopy(fullFrame, 0, frameCopy, 0, fullFrame.length);
        }

        int ySize = w * h;

        // >>> grayscale: хрома в 128
        if (config.grayscaleOutput) {
            for (int i = ySize; i < frameCopy.length; i++) frameCopy[i] = (byte) 128;
        }

        // >>> контраст на Y-плоскость
        if (config.outputContrast != 100) {
            int c = config.outputContrast;
            for (int i = 0; i < ySize; i++) {
                int v = frameCopy[i] & 0xFF;
                int v2 = 128 + ((v - 128) * c) / 100;
                if (v2 < 0) v2 = 0; else if (v2 > 255) v2 = 255;
                frameCopy[i] = (byte) v2;
            }
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
    /**
     * Маленькая (gw × gh) grayscale-картинка с красными крестами на отобранных
     * блоках optical flow. Апскейл — nearest-neighbor, чтобы не тратить CPU.
     * Стоимость: O(gw*gh) + PNG 112×84 — единицы миллисекунд даже на SC8830.
     */
    public byte[] encodeDebugJpeg() {
        OpticalFlowTracker.Result r;
        synchronized (flowResultLock) { r = lastFlowResult; }

        int gw = tracker.getGridWidth();
        int gh = tracker.getGridHeight();
        if (gw <= 0 || gh <= 0) return null;
        byte[] prev = tracker.getPrevBuffer();
        if (prev == null || prev.length != gw * gh) return null;

        int[] pixels = new int[gw * gh];
        for (int i = 0; i < gw * gh; i++) {
            int v = prev[i] & 0xFF;
            pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }

        if (r != null && r.featureXs != null) {
            for (int i = 0; i < r.featureCount; i++) {
                int fx = r.featureXs[i], fy = r.featureYs[i];
                for (int d = -2; d <= 2; d++) {
                    int x = fx + d, y = fy;
                    if (x >= 0 && x < gw && y >= 0 && y < gh)
                        pixels[y * gw + x] = 0xFFFF4040;
                    int x2 = fx, y2 = fy + d;
                    if (x2 >= 0 && x2 < gw && y2 >= 0 && y2 < gh)
                        pixels[y2 * gw + x2] = 0xFFFF4040;
                }
            }
        }

        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    gw, gh, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, gw, 0, 0, gw, gh);
            int scale = 4;
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, gw * scale, gh * scale, false);  // nearest-neighbor
            bmp.recycle();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            scaled.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "encodeDebugJpeg", t);
            return null;
        }
    }
    private void processFrame(long timestamp) {
        try {
            OpticalFlowTracker.Result r = tracker.update(yPlane, previewWidth, previewHeight);
            seq++;

            // ---- 1. Оси по настройкам монтажа ----
            double dx = r.dxPx, dy = r.dyPx;
            int rot = ((config.cameraMountRotationDeg % 360) + 360) % 360;
            if (rot == 90)       { double t = dx; dx = -dy; dy = t; }
            else if (rot == 180) { dx = -dx; dy = -dy; }
            else if (rot == 270) { double t = dx; dx = dy; dy = -t; }
            if (config.invertDx) dx = -dx;
            if (config.invertDy) dy = -dy;

            // ---- 2. Накопление позиции + поворота ----
            if (r.valid) {
                collectCalibSample(dx, dy);

                // deadband и bias — в системе трекера (dx, dy), ДО пересчёта осей
                double adjDx = dx - config.posBiasX;
                double adjDy = dy - config.posBiasY;
                double mag   = Math.hypot(adjDx, adjDy);

                if (mag >= config.posDeadbandPx) {
                    // >>> ПЕРЕСЧЁТ В «ЧЕЛОВЕЧЕСКИЕ» ОСИ СЕРВЕРА <<<
                    //
                    // До правки (дефолт invertDx=false, invertDy=true, rot=0):
                    //   вперёд → adjDx < 0   (adjDx = «назад+»)
                    //   назад  → adjDx > 0
                    //   вправо → adjDy > 0   (adjDy = «вправо+»)
                    //   влево  → adjDy < 0
                    //
                    // Хотим на сервере:
                    //   posX+ = вправо
                    //   posY+ = вперёд
                    //
                    // Значит:
                    //   posX += +adjDy   (вправо → +X)
                    //   posY += −adjDx   (вперёд → +Y)
                    synchronized (positionLock) {
                        posX += adjDy;
                        posY += -adjDx;
                    }
                }

                // Накопление поворота (не зависит от имён posX/posY)
                if (r.confidence >= 0.15
                        && r.blocksUsed >= Math.max(8, r.blocksTotal / 6)
                        && Math.abs(r.rotationDeg) > config.rotDeadbandDeg) {
                    double rotSign = (config.invertDx ^ config.invertDy) ? -1.0 : 1.0;
                    totalRotationDeg += rotSign * r.rotationDeg;
                }

                // Трейл — использует уже переименованные posX/posY
                synchronized (trailLock) {
                    double dxT = posX - lastTrailX;
                    double dyT = posY - lastTrailY;
                    if (trail.isEmpty() || Math.hypot(dxT, dyT) >= TRAIL_MIN_DIST) {
                        trail.add(new double[]{posX, posY, totalRotationDeg, timestamp});
                        lastTrailX = posX;
                        lastTrailY = posY;
                        while (trail.size() > MAX_TRAIL_POINTS) trail.remove(0);
                    }
                }
            }

            // Для shift.* оставляем dx/dy в системе трекера (это «дельты за кадр»)
            double distance     = Math.hypot(dx, dy);
            double directionDeg = normalizeDeg(Math.toDegrees(Math.atan2(dy, dx)));

            // ---- 4. JSON ----
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
            shift.put("valid",               r.valid);
            shift.put("dx_px",               round(dx, 3));
            shift.put("dy_px",               round(dy, 3));
            shift.put("distance_px",         round(distance, 3));
            shift.put("direction_deg",       round(directionDeg, 2));
            shift.put("rotation_deg",        round(r.rotationDeg, 3));
            shift.put("total_rotation_deg",  round(normalizeDeg(totalRotationDeg), 2));
            shift.put("mount_rotation_deg",  rot);
            shift.put("invert_dx",           config.invertDx);
            shift.put("invert_dy",           config.invertDy);
            reading.put("shift", shift);

            reading.put("position", positionJson());

            JSONObject q = new JSONObject();
            q.put("blocks_used",           r.blocksUsed);
            q.put("blocks_total",          r.blocksTotal);
            q.put("blocks_passed_quality", r.blocksPassedQuality);
            q.put("confidence",            round(r.confidence, 3));
            q.put("feature_count",         r.featureCount);
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

            synchronized (flowResultLock) { lastFlowResult = r; }

            // ---- 5. Публикация ----
            boolean shouldPublish;
            String  reason;

            boolean forced = forcePublish;
            if (forced) forcePublish = false;

            if (forced) {
                shouldPublish = true;  reason = "force";
            } else if (!config.publishOnlyOnChange) {
                shouldPublish = true;  reason = "always";
            } else if (lastPublishMs == 0L) {
                shouldPublish = true;  reason = "first";
            } else if (!r.valid) {
                shouldPublish = false; reason = "invalid";
            } else if (r.confidence < 0.10) {
                shouldPublish = false; reason = "low_confidence";
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

            // === Optical flow: последний результат + активный конфиг ===
            OpticalFlowTracker.Result lr;
            synchronized (flowResultLock) { lr = lastFlowResult; }
            if (lr != null) {
                o.put("blocksPassedQuality", lr.blocksPassedQuality);
                o.put("blocksUsed",          lr.blocksUsed);
                o.put("blocksTotal",         lr.blocksTotal);
                o.put("confidenceLast",      lr.confidence);
                o.put("featureCount",        lr.featureCount);
            }
            o.put("flowMinVariance",       config.flowMinVariance);
            o.put("flowMinGradient",       config.flowMinGradient);
            o.put("flowSubpixel",          config.flowSubpixel);
            o.put("flowTemporalSmoothing", config.flowTemporalSmoothing);
            o.put("flowTemporalAlpha",     config.flowTemporalAlpha);
            o.put("flowMaxMotionPx",       config.flowMaxMotionPx);
            o.put("grayscaleOutput",       config.grayscaleOutput);
            o.put("position", positionJson());
            o.put("calib", calibStatusJson());
            o.put("trackerContrast",     config.trackerContrast);
            o.put("outputContrast",      config.outputContrast);
            o.put("cameraPreviewWidth",  config.cameraPreviewWidth);
            o.put("cameraPreviewHeight", config.cameraPreviewHeight);
            o.put("cameraSizes",         getCameraSizes());
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