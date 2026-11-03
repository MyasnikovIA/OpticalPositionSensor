package ru.miacomsoft.optical_position_sensor;

import org.json.JSONException;
import org.json.JSONObject;

public class SensorConfig {

    public int     httpPort           = 8080;
    public String  httpHost           = "0.0.0.0";

    public boolean cameraPreviewEnabled    = true;
    public int     cameraPreviewFps        = 5;
    public int     cameraPreviewQuality    = 50;
    public int     cameraPreviewRotation   = 90;   // 0|90|180|270 (по часовой)

    public boolean enabled            = true;
    public boolean autoStart          = true;

    public boolean serverEnabled      = true;
    public boolean restEnabled        = true;
    public boolean sseEnabled         = true;
    public boolean wsEnabled          = true;
    public int     wsPort             = 8090;
    public boolean telnetEnabled      = false;
    public int     telnetPort         = 8091;

    public boolean clientEnabled      = false;
    public String  clientProtocol     = "http";      // http|ws|telnet
    public String  clientServerUrl    = "";
    public String  clientPath         = "/api/sensor/push";
    public String  clientToken        = "";
    public int     clientIntervalMs   = 100;

    public int     targetWidth        = 112;
    public int     searchRadius       = 4;
    public int     blockSize          = 8;
    public int     minContrast        = 3;
    public int     maxSadPx           = 18;


    // ---- Обнуление + калибровка покоя ----
    public double  posBiasX          = 0.0;      // систематическое смещение по X (вычитается)
    public double  posBiasY          = 0.0;
    public double  posDeadbandPx     = 0.15;     // deadband при интегрировании (px на кадр)
    public boolean posCalibrated     = false;    // калибровка применена

    // ---- Контраст (100 = без изменений) ----
    public int     trackerContrast   = 100;      // для входа OpticalFlowTracker
    public int     outputContrast    = 100;      // для grayscale JPEG

    // ---- Разрешение камеры (0,0 = auto = самое низкое) ----
    public int     cameraPreviewWidth  = 0;
    public int     cameraPreviewHeight = 0;

    public String  flashlightMode     = "off";       // off|on|auto
    public int     lightThreshold     = 50;

    // ---- Фильтр публикации JSON: только при изменении положения ----
    public boolean publishOnlyOnChange         = true;
    public double  publishMotionThresholdPx    = 0.4;   // порог |dx| или |dy|
    public double  publishRotationThresholdDeg = 0.2;   // порог |rotation|
    public long    publishHeartbeatMs          = 5000L; // 0 = выключить heartbeat

    // ---- Ориентация осей для монтажа / сценария использования ----
    public int     cameraMountRotationDeg = 0;     // 0|90|180|270
    public boolean invertDx               = false;
    public boolean invertDy               = true;  // «вперёд» = +Y для flat-phone

    // ---- Шумоподавление Optical Flow ----
    public int     flowMinVariance       = 400;
    public int     flowMinGradient       = 120;
    public boolean flowSubpixel          = true;
    public boolean flowTemporalSmoothing = true;
    public double  flowTemporalAlpha     = 0.6;
    public double  flowMaxMotionPx       = 40.0;

    // ---- Вывод ----
    public boolean grayscaleOutput       = true;

    public String  configUser         = "admin";
    public String  configPass         = "admin";

    public SensorConfig copy() {
        SensorConfig c = new SensorConfig();
        c.httpPort          = httpPort;
        c.httpHost          = httpHost;
        c.enabled           = enabled;
        c.autoStart         = autoStart;
        c.serverEnabled     = serverEnabled;
        c.restEnabled       = restEnabled;
        c.sseEnabled        = sseEnabled;
        c.wsEnabled         = wsEnabled;
        c.wsPort            = wsPort;
        c.telnetEnabled     = telnetEnabled;
        c.telnetPort        = telnetPort;
        c.clientEnabled     = clientEnabled;
        c.clientProtocol    = clientProtocol;
        c.clientServerUrl   = clientServerUrl;
        c.clientPath        = clientPath;
        c.clientToken       = clientToken;
        c.clientIntervalMs  = clientIntervalMs;
        c.targetWidth       = targetWidth;
        c.searchRadius      = searchRadius;
        c.blockSize         = blockSize;
        c.minContrast       = minContrast;
        c.maxSadPx          = maxSadPx;
        c.flashlightMode    = flashlightMode;
        c.lightThreshold    = lightThreshold;
        c.configUser        = configUser;
        c.configPass        = configPass;
        c.posBiasX            = posBiasX;
        c.posBiasY            = posBiasY;
        c.posDeadbandPx       = posDeadbandPx;
        c.posCalibrated       = posCalibrated;
        c.trackerContrast     = trackerContrast;
        c.outputContrast      = outputContrast;
        c.cameraPreviewWidth  = cameraPreviewWidth;
        c.cameraPreviewHeight = cameraPreviewHeight;
        return c;
    }

    public static SensorConfig fromJson(JSONObject o) {
        SensorConfig c = new SensorConfig();
        JSONObject http = o.optJSONObject("http");
        if (http != null) {
            c.httpPort = http.optInt("port", c.httpPort);
            c.httpHost = http.optString("host", c.httpHost);
        }
        JSONObject s = o.optJSONObject("sensor");
        if (s == null) return c;

        c.enabled          = s.optBoolean("enabled",           c.enabled);
        c.autoStart        = s.optBoolean("autoStart",         c.autoStart);
        c.serverEnabled    = s.optBoolean("serverEnabled",     c.serverEnabled);
        c.restEnabled      = s.optBoolean("restEnabled",       c.restEnabled);
        c.sseEnabled       = s.optBoolean("sseEnabled",        c.sseEnabled);
        c.wsEnabled        = s.optBoolean("wsEnabled",         c.wsEnabled);
        c.wsPort           = s.optInt    ("wsPort",            c.wsPort);
        c.telnetEnabled    = s.optBoolean("telnetEnabled",     c.telnetEnabled);
        c.telnetPort       = s.optInt    ("telnetPort",        c.telnetPort);
        c.clientEnabled    = s.optBoolean("clientEnabled",     c.clientEnabled);
        c.clientProtocol   = s.optString ("clientProtocol",    c.clientProtocol);
        c.clientServerUrl  = s.optString ("clientServerUrl",   c.clientServerUrl);
        c.clientPath       = s.optString ("clientPath",        c.clientPath);
        c.clientToken      = s.optString ("clientToken",       c.clientToken);
        c.clientIntervalMs = s.optInt    ("clientIntervalMs",  c.clientIntervalMs);
        c.targetWidth      = s.optInt    ("targetWidth",       c.targetWidth);
        c.searchRadius     = s.optInt    ("searchRadius",      c.searchRadius);
        c.blockSize        = s.optInt    ("blockSize",         c.blockSize);
        c.minContrast      = s.optInt    ("minContrast",       c.minContrast);
        c.maxSadPx         = s.optInt    ("maxSadPx",          c.maxSadPx);
        c.flashlightMode   = s.optString ("flashlightMode",    c.flashlightMode);
        c.lightThreshold   = s.optInt    ("lightThreshold",    c.lightThreshold);
        c.configUser       = s.optString ("configUser",        c.configUser);
        c.configPass       = s.optString ("configPass",        c.configPass);
        c.cameraPreviewEnabled = s.optBoolean("cameraPreviewEnabled", c.cameraPreviewEnabled);
        c.cameraPreviewFps     = s.optInt    ("cameraPreviewFps",     c.cameraPreviewFps);
        c.cameraPreviewQuality = s.optInt    ("cameraPreviewQuality", c.cameraPreviewQuality);
        c.cameraPreviewRotation = s.optInt("cameraPreviewRotation", c.cameraPreviewRotation);
        c.publishOnlyOnChange         = s.optBoolean("publishOnlyOnChange",         c.publishOnlyOnChange);
        c.publishMotionThresholdPx    = s.optDouble ("publishMotionThresholdPx",    c.publishMotionThresholdPx);
        c.publishRotationThresholdDeg = s.optDouble ("publishRotationThresholdDeg", c.publishRotationThresholdDeg);
        c.publishHeartbeatMs          = s.optLong   ("publishHeartbeatMs",          c.publishHeartbeatMs);
        c.flowMinVariance       = s.optInt    ("flowMinVariance",       c.flowMinVariance);
        c.flowMinGradient       = s.optInt    ("flowMinGradient",       c.flowMinGradient);
        c.flowSubpixel          = s.optBoolean("flowSubpixel",          c.flowSubpixel);
        c.flowTemporalSmoothing = s.optBoolean("flowTemporalSmoothing", c.flowTemporalSmoothing);
        c.flowTemporalAlpha     = s.optDouble ("flowTemporalAlpha",     c.flowTemporalAlpha);
        c.flowMaxMotionPx       = s.optDouble ("flowMaxMotionPx",       c.flowMaxMotionPx);
        c.grayscaleOutput       = s.optBoolean("grayscaleOutput",       c.grayscaleOutput);
        c.posBiasX            = s.optDouble ("posBiasX",            c.posBiasX);
        c.posBiasY            = s.optDouble ("posBiasY",            c.posBiasY);
        c.posDeadbandPx       = s.optDouble ("posDeadbandPx",       c.posDeadbandPx);
        c.posCalibrated       = s.optBoolean("posCalibrated",       c.posCalibrated);
        c.trackerContrast     = s.optInt    ("trackerContrast",     c.trackerContrast);
        c.outputContrast      = s.optInt    ("outputContrast",      c.outputContrast);
        c.cameraPreviewWidth  = s.optInt    ("cameraPreviewWidth",  c.cameraPreviewWidth);
        c.cameraPreviewHeight = s.optInt    ("cameraPreviewHeight", c.cameraPreviewHeight);
        return c;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        JSONObject http = new JSONObject();
        http.put("port", httpPort);
        http.put("host", httpHost);
        root.put("http", http);

        JSONObject s = new JSONObject();
        s.put("enabled",          enabled);
        s.put("autoStart",        autoStart);
        s.put("serverEnabled",    serverEnabled);
        s.put("restEnabled",      restEnabled);
        s.put("sseEnabled",       sseEnabled);
        s.put("wsEnabled",        wsEnabled);
        s.put("wsPort",           wsPort);
        s.put("telnetEnabled",    telnetEnabled);
        s.put("telnetPort",       telnetPort);
        s.put("clientEnabled",    clientEnabled);
        s.put("clientProtocol",   clientProtocol);
        s.put("clientServerUrl",  clientServerUrl);
        s.put("clientPath",       clientPath);
        s.put("clientToken",      clientToken);
        s.put("clientIntervalMs", clientIntervalMs);
        s.put("targetWidth",      targetWidth);
        s.put("searchRadius",     searchRadius);
        s.put("blockSize",        blockSize);
        s.put("minContrast",      minContrast);
        s.put("maxSadPx",         maxSadPx);
        s.put("flashlightMode",   flashlightMode);
        s.put("lightThreshold",   lightThreshold);
        s.put("configUser",       configUser);
        s.put("configPass",       configPass);
        s.put("cameraPreviewEnabled", cameraPreviewEnabled);
        s.put("cameraPreviewFps",     cameraPreviewFps);
        s.put("cameraPreviewQuality", cameraPreviewQuality);
        s.put("cameraPreviewRotation", cameraPreviewRotation);
        s.put("publishOnlyOnChange",         publishOnlyOnChange);
        s.put("publishMotionThresholdPx",    publishMotionThresholdPx);
        s.put("publishRotationThresholdDeg", publishRotationThresholdDeg);
        s.put("publishHeartbeatMs",          publishHeartbeatMs);
        s.put("cameraMountRotationDeg", cameraMountRotationDeg);
        s.put("invertDx",               invertDx);
        s.put("invertDy",               invertDy);
        s.put("flowMinVariance",       flowMinVariance);
        s.put("flowMinGradient",       flowMinGradient);
        s.put("flowSubpixel",          flowSubpixel);
        s.put("flowTemporalSmoothing", flowTemporalSmoothing);
        s.put("flowTemporalAlpha",     flowTemporalAlpha);
        s.put("flowMaxMotionPx",       flowMaxMotionPx);
        s.put("grayscaleOutput",       grayscaleOutput);
        s.put("posBiasX",            posBiasX);
        s.put("posBiasY",            posBiasY);
        s.put("posDeadbandPx",       posDeadbandPx);
        s.put("posCalibrated",       posCalibrated);
        s.put("trackerContrast",     trackerContrast);
        s.put("outputContrast",      outputContrast);
        s.put("cameraPreviewWidth",  cameraPreviewWidth);
        s.put("cameraPreviewHeight", cameraPreviewHeight);
        root.put("sensor", s);
        return root;
    }

    public JSONObject toPublicJson() throws JSONException {
        JSONObject o = toJson().getJSONObject("sensor");
        o.remove("configPass");
        return o;
    }

    public void applyPatch(JSONObject p) {
        // HTTP-сервер
        httpPort = p.optInt   ("httpPort", httpPort);
        httpHost = p.optString("httpHost", httpHost);

        enabled          = p.optBoolean("enabled",          enabled);
        autoStart        = p.optBoolean("autoStart",        autoStart);
        serverEnabled    = p.optBoolean("serverEnabled",    serverEnabled);
        restEnabled      = p.optBoolean("restEnabled",      restEnabled);
        sseEnabled       = p.optBoolean("sseEnabled",       sseEnabled);
        wsEnabled        = p.optBoolean("wsEnabled",        wsEnabled);
        wsPort           = p.optInt    ("wsPort",           wsPort);
        telnetEnabled    = p.optBoolean("telnetEnabled",    telnetEnabled);
        telnetPort       = p.optInt    ("telnetPort",       telnetPort);
        clientEnabled    = p.optBoolean("clientEnabled",    clientEnabled);
        clientProtocol   = p.optString ("clientProtocol",   clientProtocol);
        clientServerUrl  = p.optString ("clientServerUrl",  clientServerUrl);
        clientPath       = p.optString ("clientPath",       clientPath);
        clientToken      = p.optString ("clientToken",      clientToken);
        clientIntervalMs = p.optInt    ("clientIntervalMs", clientIntervalMs);
        targetWidth      = p.optInt    ("targetWidth",      targetWidth);
        searchRadius     = p.optInt    ("searchRadius",     searchRadius);
        blockSize        = p.optInt    ("blockSize",        blockSize);
        minContrast      = p.optInt    ("minContrast",      minContrast);
        maxSadPx         = p.optInt    ("maxSadPx",         maxSadPx);
        flashlightMode   = p.optString ("flashlightMode",   flashlightMode);
        lightThreshold   = p.optInt    ("lightThreshold",   lightThreshold);

        cameraPreviewEnabled  = p.optBoolean("cameraPreviewEnabled",  cameraPreviewEnabled);
        cameraPreviewFps      = p.optInt    ("cameraPreviewFps",      cameraPreviewFps);
        cameraPreviewQuality  = p.optInt    ("cameraPreviewQuality",  cameraPreviewQuality);
        cameraPreviewRotation = p.optInt    ("cameraPreviewRotation", cameraPreviewRotation);

        cameraMountRotationDeg = p.optInt    ("cameraMountRotationDeg", cameraMountRotationDeg);
        invertDx               = p.optBoolean("invertDx",               invertDx);
        invertDy               = p.optBoolean("invertDy",               invertDy);

        publishOnlyOnChange         = p.optBoolean("publishOnlyOnChange",         publishOnlyOnChange);
        publishMotionThresholdPx    = p.optDouble ("publishMotionThresholdPx",    publishMotionThresholdPx);
        publishRotationThresholdDeg = p.optDouble ("publishRotationThresholdDeg", publishRotationThresholdDeg);
        publishHeartbeatMs          = p.optLong   ("publishHeartbeatMs",          publishHeartbeatMs);

        flowMinVariance       = p.optInt    ("flowMinVariance",       flowMinVariance);
        flowMinGradient       = p.optInt    ("flowMinGradient",       flowMinGradient);
        flowSubpixel          = p.optBoolean("flowSubpixel",          flowSubpixel);
        flowTemporalSmoothing = p.optBoolean("flowTemporalSmoothing", flowTemporalSmoothing);
        flowTemporalAlpha     = p.optDouble ("flowTemporalAlpha",     flowTemporalAlpha);
        flowMaxMotionPx       = p.optDouble ("flowMaxMotionPx",       flowMaxMotionPx);
        grayscaleOutput       = p.optBoolean("grayscaleOutput",       grayscaleOutput);

        posBiasX            = p.optDouble ("posBiasX",            posBiasX);
        posBiasY            = p.optDouble ("posBiasY",            posBiasY);
        posDeadbandPx       = p.optDouble ("posDeadbandPx",       posDeadbandPx);
        posCalibrated       = p.optBoolean("posCalibrated",       posCalibrated);
        trackerContrast     = p.optInt    ("trackerContrast",     trackerContrast);
        outputContrast      = p.optInt    ("outputContrast",      outputContrast);
        cameraPreviewWidth  = p.optInt    ("cameraPreviewWidth",  cameraPreviewWidth);
        cameraPreviewHeight = p.optInt    ("cameraPreviewHeight", cameraPreviewHeight);

// Clamp
        if (posDeadbandPx < 0)     posDeadbandPx = 0;
        if (posDeadbandPx > 10)    posDeadbandPx = 10;
        if (trackerContrast < 50)  trackerContrast = 50;
        if (trackerContrast > 300) trackerContrast = 300;
        if (outputContrast < 50)   outputContrast = 50;
        if (outputContrast > 300)  outputContrast = 300;

        String u    = p.optString("configUser", "");
        String pass = p.optString("configPass", "");
        if (!u.isEmpty())    configUser = u;
        if (!pass.isEmpty()) configPass = pass;

        // Защита от некорректных значений
        if (httpPort < 1 || httpPort > 65535) httpPort = 8080;
        if (wsPort   < 1 || wsPort   > 65535) wsPort   = 8090;
        if (telnetPort < 1 || telnetPort > 65535) telnetPort = 8091;
    }
}