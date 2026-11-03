package ru.miacomsoft.optical_position_sensor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA = 2001;
    private static final int MAX_POLL_ATTEMPTS = 40;
    private static final int POLL_INTERVAL_MS  = 700;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);

        // Определяем актуальный порт из сохранённого конфига
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
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestPermAndStart(); }
        });

        if (hasCameraPermission()) startSensorService();
        else showPermScreen();
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
    public void onBackPressed() {
        if (settingsVisible) { toggleSettings(); return; }
        if (webLive != null && webLive.canGoBack()) { webLive.goBack(); return; }
        super.onBackPressed();
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