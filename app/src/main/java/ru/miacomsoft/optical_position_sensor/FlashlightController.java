package ru.miacomsoft.optical_position_sensor;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.util.List;

/**
 * Управление фонариком: OFF / ON / AUTO.
 *
 * Камерой владеет SensorService — она передаётся через setCamera().
 *
 * Реализация setTorch() сделана максимально «терпимой» к HAL-багам
 * (Spreadtrum SC8830 и старые MTK):
 *   1. каждая установка использует СВЕЖИЕ getParameters();
 *   2. перед сменой флеша — cancelAutoFocus();
 *   3. результат подтверждается чтением getParameters().getFlashMode();
 *   4. если прямая установка не удалась — OFF → пауза 100 мс → TORCH;
 *   5. если снова неудача — повтор с задержкой (cooldown 2 с), чтобы не спамить HAL.
 */
public class FlashlightController implements SensorEventListener {

    private static final String TAG = "Flashlight";
    private static final long   RETRY_COOLDOWN_MS = 2000L;
    private static final long   TOGGLE_DELAY_MS   = 100L;

    private final Context context;

    // Камера, которой управляем. Может быть null, пока SensorService не открыл её.
    private Camera camera;

    // Диагностика
    private boolean available = false;             // HAL сообщает «torch» в supportedFlashModes
    private volatile boolean torchOn = false;      // подтверждённое состояние
    private volatile String lastError = "";
    private volatile String appliedMode = "";      // что вернул HAL после setParameters
    private volatile List<String> supportedModes = null;
    private volatile boolean lastWanted = false;
    private volatile long    lastAttemptTime = 0L;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private volatile float lux = -1f;

    private volatile String mode = "off";          // off|on|auto
    private volatile int thresholdLux = 50;

    public FlashlightController(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    /** Регистрирует датчик освещённости. Камера НЕ открывается — её передают через setCamera(). */
    public boolean init() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                if (lightSensor != null) {
                    sensorManager.registerListener(this, lightSensor,
                            SensorManager.SENSOR_DELAY_UI);
                    Log.i(TAG, "Light sensor registered");
                } else {
                    Log.i(TAG, "No light sensor");
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            lastError = "init: " + e.getMessage();
            return false;
        }
    }

    /**
     * Передаёт открытую превью-камеру. После этого setMode()/setThreshold()
     * будут применяться к ней через setParameters().
     */
    public void setCamera(Camera cam) {
        this.camera = cam;
        this.torchOn = false;
        this.available = false;
        this.lastError = "";
        this.appliedMode = "";
        this.supportedModes = null;

        if (cam == null) {
            Log.i(TAG, "setCamera: null, flashlight unavailable");
            return;
        }
        try {
            Camera.Parameters p = cam.getParameters();
            this.supportedModes = p.getSupportedFlashModes();
            Log.i(TAG, "setCamera: supportedModes=" + supportedModes);
            if (supportedModes != null
                    && supportedModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                available = true;
            }
            // Применяем текущий режим (обычно "off" — просто чтобы выровнять состояние)
            apply();
        } catch (Exception e) {
            Log.w(TAG, "setCamera failed: " + e.getMessage());
            lastError = "setCamera: " + e.getMessage();
        }
    }

    public synchronized void setMode(String m) {
        if (m == null) m = "off";
        m = m.toLowerCase();
        if (!"on".equals(m) && !"auto".equals(m)) m = "off";
        mode = m;
        apply();
    }

    public synchronized void setThreshold(int lx) {
        thresholdLux = Math.max(0, lx);
        if ("auto".equals(mode)) apply();
    }

    private void apply() {
        boolean want;
        if ("on".equals(mode))        want = true;
        else if ("auto".equals(mode)) want = lux >= 0 && lux < thresholdLux;
        else                          want = false;
        setTorch(want);
    }

    /**
     * Пытается привести фонарик в желаемое состояние. Возвращает true, только если
     * HAL подтвердил, что флаг применился.
     */
    private synchronized void setTorch(boolean on) {
        if (camera == null) {
            lastError = "camera==null";
            return;
        }
        if (!available) {
            lastError = "torch not in supportedFlashModes";
            return;
        }
        if (torchOn == on) {
            return; // уже в нужном состоянии
        }

        long now = System.currentTimeMillis();
        if (lastWanted == on && !torchOn
                && now - lastAttemptTime < RETRY_COOLDOWN_MS) {
            // недавно пытались и не получилось — не спамим HAL
            return;
        }
        lastWanted = on;
        lastAttemptTime = now;

        String targetMode = on
                ? Camera.Parameters.FLASH_MODE_TORCH
                : Camera.Parameters.FLASH_MODE_OFF;

        // Попытка 1: прямой set с СВЕЖИМИ параметрами
        if (trySetFlashMode(targetMode)) {
            torchOn = on;
            lastError = "";
            return;
        }

        // Попытка 2: OFF → пауза → target (для HAL, которым нужен transition)
        Log.i(TAG, "setTorch: direct failed, trying OFF→" + targetMode);
        if (trySetFlashMode(Camera.Parameters.FLASH_MODE_OFF)) {
            try { Thread.sleep(TOGGLE_DELAY_MS); } catch (InterruptedException ignored) {}
            if (trySetFlashMode(targetMode)) {
                torchOn = on;
                lastError = "";
                return;
            }
        }

        // Оба способа не помогли
        torchOn = false;
        lastError = "failed to set " + targetMode
                + " (see logcat for setParameters details)";
        Log.e(TAG, "setTorch: " + lastError);
    }

    /**
     * Одна попытка: получить свежие параметры, отменить автофокус,
     * установить flashMode, прочитать и вернуть подтверждённое значение.
     */
    private boolean trySetFlashMode(String flashMode) {
        try {
            // 1. Автофокус в этот момент часто блокирует торч на старых HAL.
            try { camera.cancelAutoFocus(); } catch (Exception ignored) {}

            // 2. Свежие параметры — критично, старый объект часто «проглатывает» изменение.
            Camera.Parameters p = camera.getParameters();

            // 3. Отключаем то, что может мешать торчу
            try {
                if (p.isVideoStabilizationSupported()) {
                    p.setVideoStabilization(false);
                }
            } catch (Exception ignore) {}

            p.setFlashMode(flashMode);
            camera.setParameters(p);

            // 4. Проверяем, что HAL действительно применил режим
            Camera.Parameters after = camera.getParameters();
            String applied = after.getFlashMode();
            appliedMode = applied;

            boolean ok = flashMode.equals(applied);
            Log.i(TAG, "trySetFlashMode: want=" + flashMode
                    + " got=" + applied + " ok=" + ok);
            if (!ok) {
                lastError = "HAL returned '" + applied + "' instead of '" + flashMode + "'";
            }
            return ok;

        } catch (Exception e) {
            lastError = "setParameters: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "");
            Log.e(TAG, "trySetFlashMode(" + flashMode + ") failed", e);
            return false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            lux = e.values[0];
            if ("auto".equals(mode)) apply();
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    public String  getMode()           { return mode; }
    public boolean isOn()              { return torchOn; }
    public boolean isAvailable()       { return available; }
    public float   getLux()            { return lux; }
    public int     getThreshold()      { return thresholdLux; }
    public String  getLastError()      { return lastError; }
    public String  getAppliedMode()    { return appliedMode; }
    public List<String> getSupportedModes() { return supportedModes; }

    /** Отключает фонарик и снимает слушатель датчика. Камеру НЕ закрывает. */
    public synchronized void release() {
        setTorch(false);
        try {
            if (sensorManager != null) sensorManager.unregisterListener(this);
        } catch (Exception ignored) {}
        sensorManager = null;
        lightSensor = null;
        camera = null;
        available = false;
    }
}