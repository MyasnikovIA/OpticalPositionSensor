package ru.miacomsoft.optical_position_sensor;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Читает config.json из assets как значения по умолчанию,
 * сохраняет рабочий конфиг в filesDir/config.json.
 */
public class ConfigStore {

    private static final String TAG = "ConfigStore";
    private static final String FILE_NAME = "config.json";

    public static SensorConfig load(Context ctx) {
        SensorConfig cfg = new SensorConfig();
        try {
            InputStream is = ctx.getAssets().open(FILE_NAME);
            byte[] data = readAll(is);
            is.close();
            JSONObject o = new JSONObject(new String(data, "utf-8"));
            cfg = SensorConfig.fromJson(o);
            Log.i(TAG, "Loaded defaults from assets");
        } catch (Exception e) {
            Log.w(TAG, "Assets config read failed: " + e.getMessage());
        }

        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (f.isFile()) {
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] data = readAll(in);
                in.close();
                JSONObject o = new JSONObject(new String(data, "utf-8"));
                cfg = SensorConfig.fromJson(o);
                Log.i(TAG, "Loaded user config from " + f);
            } catch (Exception e) {
                Log.w(TAG, "User config read failed: " + e.getMessage());
            }
        }
        return cfg;
    }

    public static void save(Context ctx, SensorConfig cfg) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try {
            JSONObject o = cfg.toJson();
            String text = o.toString(2);
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("utf-8"));
            out.flush();
            out.close();
            Log.i(TAG, "Saved to " + f);
        } catch (Exception e) {
            throw new IOException("save failed: " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
}