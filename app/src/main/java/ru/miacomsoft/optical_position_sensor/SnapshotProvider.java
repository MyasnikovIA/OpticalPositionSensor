package ru.miacomsoft.optical_position_sensor;

import android.util.Log;

/**
 * Кодирует кадр камеры в JPEG с заданным FPS и раздаёт его всем клиентам
 * MJPEG-потока. Работает только пока есть хотя бы один клиент (refcount).
 */
public class SnapshotProvider {

    private static final String TAG = "SnapshotProvider";

    private final SensorService svc;
    private final int fps;
    private final int quality;

    private volatile byte[] lastJpeg;
    private volatile long   lastJpegTime;

    private Thread encoder;
    private volatile boolean running = false;
    private int clients = 0;

    public SnapshotProvider(SensorService svc, int fps, int quality) {
        this.svc     = svc;
        this.fps     = Math.max(1, Math.min(15, fps));
        this.quality = Math.max(10, Math.min(95, quality));
    }

    public synchronized void acquire() {
        clients++;
        if (!running) startEncoder();
    }

    public synchronized void release() {
        clients--;
        if (clients <= 0) stopEncoder();
    }

    public byte[] getLatest()      { return lastJpeg; }
    public long   getLatestTime()  { return lastJpegTime; }

    /** Однократное кодирование текущего кадра (для /snapshot.jpg). */
    public byte[] encodeOnce() {
        return svc.encodeSnapshotJpeg(quality);
    }

    private void startEncoder() {
        running = true;
        encoder = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "SnapshotEncoder");
        encoder.setDaemon(true);
        encoder.start();
        Log.i(TAG, "encoder started, fps=" + fps + ", quality=" + quality);
    }

    private void stopEncoder() {
        running = false;
        if (encoder != null) {
            encoder.interrupt();
            encoder = null;
        }
        lastJpeg = null;
        Log.i(TAG, "encoder stopped");
    }

    private void loop() {
        final long interval = 1000L / fps;
        while (running) {
            long t0 = System.currentTimeMillis();
            byte[] jpeg = svc.encodeSnapshotJpeg(quality);
            if (jpeg != null) {
                lastJpeg = jpeg;
                lastJpegTime = System.currentTimeMillis();
            }
            long dt = System.currentTimeMillis() - t0;
            long sleep = interval - dt;
            if (sleep > 0) {
                try { Thread.sleep(sleep); }
                catch (InterruptedException ie) { return; }
            }
        }
    }
}