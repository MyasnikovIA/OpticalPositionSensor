package ru.miacomsoft.optical_position_sensor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA = 2001;
    private static final int MAX_POLL_ATTEMPTS = 40;
    private static final int POLL_INTERVAL_MS  = 700;

    private static final String PREF_NAME       = "launcher_prefs";
    private static final String KEY_PROMPTED    = "prompted_as_launcher";

    private WebView webLive;
    private WebView webSettings;
    private Button btnToggle;
    private LinearLayout loadingPanel;
    private TextView loadingText;
    private Button grantBtn;

    private boolean settingsVisible = false;
    private boolean settingsLoaded  = false;
    private boolean liveLoaded      = false;
    private boolean pollingActive   = false;

    private int    currentPort;
    private String HOST;
    private String URL_LIVE;
    private String URL_SETTINGS;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // --- двойное нажатие VolUp + VolDown → меню лаунчера ---
    private boolean volUpPressed   = false;
    private boolean volDownPressed = false;

    private final SensorService.HttpPortListener portListener =
            new SensorService.HttpPortListener() {
                @Override
                public void onHttpPortChanged(final int requestedPort, final int actualPort) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (actualPort == currentPort) {
                                if (requestedPort != actualPort) {
                                    Toast.makeText(MainActivity.this,
                                            "Не удалось сменить порт на " + requestedPort
                                                    + " (занят?). Остался на " + actualPort + ".",
                                            Toast.LENGTH_LONG).show();
                                }
                                return;
                            }
                            Log.i(TAG, "HTTP port changed: " + currentPort + " → " + actualPort);
                            currentPort = actualPort;
                            rebuildUrls();
                            if (settingsVisible) {
                                webSettings.loadUrl(URL_SETTINGS);
                            } else if (liveLoaded) {
                                webLive.loadUrl(URL_LIVE);
                            }
                        }
                    });
                }
            };

    private void rebuildUrls() {
        HOST = "http://127.0.0.1:" + currentPort;
        URL_LIVE = HOST + "/sensor.html";
        URL_SETTINGS = HOST + "/config.html";
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);

        try {
            SensorConfig cfg = ConfigStore.load(getApplicationContext());
            currentPort = (cfg != null && cfg.httpPort > 0) ? cfg.httpPort : 8080;
        } catch (Exception e) {
            currentPort = 8080;
        }
        rebuildUrls();

        webLive      = findViewById(R.id.webLive);
        webSettings  = findViewById(R.id.webSettings);
        btnToggle    = findViewById(R.id.btnToggle);
        loadingPanel = findViewById(R.id.loadingPanel);
        loadingText  = findViewById(R.id.loadingText);
        grantBtn     = findViewById(R.id.grantPermBtn);

        setupWebView(webLive);
        setupWebView(webSettings);

        webLive.setVisibility(View.GONE);
        webSettings.setVisibility(View.GONE);
        btnToggle.setVisibility(View.GONE);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSettings(); }
        });
        // Долгое нажатие на ⚙ → меню лаунчера
        btnToggle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showLauncherMenu();
                return true;
            }
        });

        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestPermAndStart(); }
        });

        if (hasCameraPermission()) startSensorService();
        else showPermScreen();

        maybePromptAsLauncher();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SensorService.setHttpPortListener(portListener);
    }

    @Override
    protected void onPause() {
        SensorService.setHttpPortListener(null);
        super.onPause();
    }

    // ============================================================
    // Launcher-specific: HOME нажат повторно, Back, VolUp+VolDown
    // ============================================================

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Пользователь нажал HOME (мы уже активны). Просто остаёмся на месте —
        // WebView не перезагружаем, SSE-поток продолжает работать.
        Log.i(TAG, "onNewIntent (HOME pressed)");
    }

    @Override
    public void onBackPressed() {
        // 1. Настройки открыты — закрыть их
        if (settingsVisible) { toggleSettings(); return; }
        // 2. WebView может — назад в WebView
        if (webLive != null && webLive.canGoBack()) { webLive.goBack(); return; }
        // 3. Лаунчер: НЕ выходим
        Toast.makeText(this,
                "Это лаунчер. Удерживайте ⚙ или нажмите Vol+ и Vol- одновременно.",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)   { volUpPressed = true;   checkVolCombo(); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volDownPressed = true; checkVolCombo(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)   { volUpPressed = false;   return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volDownPressed = false; return true; }
        return super.onKeyUp(keyCode, event);
    }

    private void checkVolCombo() {
        if (volUpPressed && volDownPressed) {
            volUpPressed = false;
            volDownPressed = false;
            showLauncherMenu();
        }
    }

    // ============================================================
    // Меню лаунчера
    // ============================================================

    private void showLauncherMenu() {
        final String[] items = {
                "📱 Открыть приложение…",
                "⚙ Настройки Android",
                "🏠 Сменить лаунчер",
                "ℹ Сведения о приложении",
                "✕ Закрыть меню"
        };
        new AlertDialog.Builder(this)
                .setTitle("Меню лаунчера")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: showAppPicker(); break;
                            case 1: safeStart(new Intent(Settings.ACTION_SETTINGS)); break;
                            case 2: openHomeSettings(); break;
                            case 3: openAppSettings(); break;
                            default: break;
                        }
                    }
                })
                .setCancelable(true)
                .show();
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);

        // Исключаем сами себя
        final List<ResolveInfo> filtered = new ArrayList<ResolveInfo>();
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo == null) continue;
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            filtered.add(ri);
        }
        Collections.sort(filtered, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                CharSequence la = a.loadLabel(pm);
                CharSequence lb = b.loadLabel(pm);
                return la.toString().compareToIgnoreCase(lb.toString());
            }
        });

        if (filtered.isEmpty()) {
            Toast.makeText(this, "Приложений не найдено", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] names = new CharSequence[filtered.size()];
        for (int i = 0; i < filtered.size(); i++) {
            names[i] = filtered.get(i).loadLabel(pm);
        }

        new AlertDialog.Builder(this)
                .setTitle("Открыть приложение")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ResolveInfo ri = filtered.get(which);
                        Intent launch = getPackageManager()
                                .getLaunchIntentForPackage(ri.activityInfo.packageName);
                        if (launch != null) {
                            try { startActivity(launch); }
                            catch (Exception e) {
                                Toast.makeText(MainActivity.this,
                                        "Не удалось запустить: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Настройки недоступны", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            safeStart(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void safeStart(Intent i) {
        try { startActivity(i); }
        catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // Проверка/предложение стать лаунчером
    // ============================================================

    private boolean isDefaultLauncher() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return false;
            return getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    private void maybePromptAsLauncher() {
        if (isDefaultLauncher()) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PROMPTED, false)) return;
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply();

        new AlertDialog.Builder(this)
                .setTitle("Сделать лаунчером?")
                .setMessage("Сделать это приложение домашним экраном?\n\n"
                        + "Приложение будет запускаться при включении устройства "
                        + "и при нажатии HOME.\n\n"
                        + "Вернуться к обычному лаунчеру можно через меню: "
                        + "удерживайте ⚙ или нажмите Vol+ и Vol− одновременно.")
                .setPositiveButton("Настроить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        openHomeSettings();
                    }
                })
                .setNegativeButton("Позже", null)
                .show();
    }

    // ============================================================
    // Разрешения и запуск сервиса
    // ============================================================

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermAndStart() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAMERA && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startSensorService();
        } else {
            loadingText.setText("Камера не разрешена — приложение не может работать");
        }
    }

    private void showPermScreen() {
        loadingPanel.setVisibility(View.VISIBLE);
        loadingText.setText("Для работы нужна камера");
        grantBtn.setVisibility(View.VISIBLE);
    }

    private void startSensorService() {
        loadingPanel.setVisibility(View.VISIBLE);
        loadingText.setText("Запуск сервиса и камеры…");
        grantBtn.setVisibility(View.GONE);

        Intent i = new Intent(this, SensorService.class);
        i.setAction(SensorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);

        ui.postDelayed(new Runnable() {
            @Override public void run() { pollAttempt(1); }
        }, 1000);
    }

    private void pollAttempt(final int attempt) {
        if (pollingActive) return;
        pollingActive = true;
        Log.i(TAG, "poll attempt #" + attempt + " → " + HOST);

        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String err = "";
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(HOST + "/api/sensor/status").openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(2000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try {
                            InputStream is = conn.getInputStream();
                            byte[] buf = new byte[512];
                            while (is.read(buf) > 0) {}
                            is.close();
                        } catch (Exception ignored) {}
                        ok = true;
                    } else {
                        err = "HTTP " + code;
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : "");
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
                }
                final boolean ready = ok;
                final String errorMsg = err;
                Log.i(TAG, "poll #" + attempt + " → ready=" + ready + " err=" + errorMsg);
                ui.post(new Runnable() {
                    @Override public void run() {
                        pollingActive = false;
                        if (isFinishing()) return;
                        if (ready) {
                            onServerReady();
                        } else if (attempt < MAX_POLL_ATTEMPTS) {
                            loadingText.setText(
                                    "Запуск… (попытка " + attempt + "/"
                                            + MAX_POLL_ATTEMPTS + ")\n" + errorMsg);
                            ui.postDelayed(new Runnable() {
                                @Override public void run() { pollAttempt(attempt + 1); }
                            }, POLL_INTERVAL_MS);
                        } else {
                            loadingText.setText(
                                    "Сервер не отвечает: " + errorMsg
                                            + "\nОткрываю страницу вручную через 1.5 с…");
                            ui.postDelayed(new Runnable() {
                                @Override public void run() { onServerReady(); }
                            }, 1500);
                        }
                    }
                });
            }
        }, "SensorPoll").start();
    }

    private void onServerReady() {
        if (liveLoaded) return;
        liveLoaded = true;
        loadingPanel.setVisibility(View.GONE);
        webLive.setVisibility(View.VISIBLE);
        webLive.loadUrl(URL_LIVE);
        btnToggle.setText("⚙");
        btnToggle.setVisibility(View.VISIBLE);
        Log.i(TAG, "loading " + URL_LIVE);
    }

    private void toggleSettings() {
        if (!settingsVisible) {
            settingsVisible = true;
            if (!settingsLoaded) {
                settingsLoaded = true;
                webSettings.loadUrl(URL_SETTINGS);
            }
            webSettings.setVisibility(View.VISIBLE);
            webLive.setVisibility(View.GONE);
            btnToggle.setText("✕");
        } else {
            settingsVisible = false;
            webSettings.setVisibility(View.GONE);
            webLive.setVisibility(View.VISIBLE);
            btnToggle.setText("⚙");
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        wv.setBackgroundColor(0xFF101418);
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "WebView page finished: " + url);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (webLive != null) {
            try { webLive.loadUrl("about:blank"); webLive.stopLoading(); webLive.destroy(); }
            catch (Exception ignored) {}
            webLive = null;
        }
        if (webSettings != null) {
            try { webSettings.loadUrl("about:blank"); webSettings.stopLoading(); webSettings.destroy(); }
            catch (Exception ignored) {}
            webSettings = null;
        }
        super.onDestroy();
    }
}