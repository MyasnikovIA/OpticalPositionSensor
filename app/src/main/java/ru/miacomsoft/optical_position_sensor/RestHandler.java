package ru.miacomsoft.optical_position_sensor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.json.JSONObject;

public class RestHandler implements HttpHandler {

    private final SensorService svc;

    public RestHandler(SensorService svc) { this.svc = svc; }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        if (HttpUtil.handleOptions(ex)) return;

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();

        try {
            // ---------- last / status ----------
            if ("/api/sensor/last".equals(path)) {
                JSONObject r = svc.getBroadcaster().getLast();
                if (r == null) {
                    JSONObject o = new JSONObject();
                    o.put("valid", false);
                    o.put("reason", "no_frames_yet");
                    HttpUtil.sendJson(ex, 200, o.toString());
                } else {
                    HttpUtil.sendJson(ex, 200, r.toString());
                }
                return;
            }

            if ("/api/sensor/status".equals(path)) {
                HttpUtil.sendJson(ex, 200, svc.statusJson().toString());
                return;
            }

            // ---------- map (новая вкладка «Карта») ----------
            if ("/api/sensor/map".equals(path)) {
                HttpUtil.sendJson(ex, 200, svc.mapJsonPublic().toString());
                return;
            }

            // ---------- start / stop ----------
            if ("/api/sensor/start".equals(path) && "POST".equals(method)) {
                svc.startSensorOnly();
                HttpUtil.sendJson(ex, 200, svc.statusJson().toString());
                return;
            }

            if ("/api/sensor/stop".equals(path) && "POST".equals(method)) {
                svc.stopSensorOnly();
                HttpUtil.sendJson(ex, 200, svc.statusJson().toString());
                return;
            }

            // ---------- zero / reset ----------
            if ("/api/sensor/zero".equals(path) && "POST".equals(method)) {
                svc.setZero();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("position", svc.positionJsonPublic());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            if ("/api/sensor/reset".equals(path) && "POST".equals(method)) {
                svc.resetPosition();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("position", svc.positionJsonPublic());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            // ---------- калибровка покоя ----------
            if ("/api/sensor/calib/start".equals(path) && "POST".equals(method)) {
                String body = HttpUtil.readBody(ex, 4096);
                boolean thenZero = false;
                try {
                    if (!body.isEmpty()) {
                        JSONObject req = new JSONObject(body);
                        thenZero = req.optBoolean("thenZero", false);
                    }
                } catch (Exception ignored) {}
                svc.startCalibStatic(thenZero);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("calib", svc.calibStatusJson());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            if ("/api/sensor/calib/apply".equals(path) && "POST".equals(method)) {
                svc.applyCalibNow();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("calib", svc.calibStatusJson());
                o.put("position", svc.positionJsonPublic());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            if ("/api/sensor/calib/clear".equals(path) && "POST".equals(method)) {
                svc.clearCalibStatic();
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("calib", svc.calibStatusJson());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            if ("/api/sensor/calib/status".equals(path) && "GET".equals(method)) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("calib", svc.calibStatusJson());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            // ---------- фонарик ----------
            if ("/api/sensor/flashlight".equals(path)) {
                if ("GET".equals(method)) {
                    HttpUtil.sendJson(ex, 200, svc.flashlightJson().toString());
                    return;
                }
                if ("POST".equals(method)) {
                    String body = HttpUtil.readBody(ex, 4096);
                    JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                    String mode = req.optString("mode", "");
                    int thr = req.optInt("threshold", -1);
                    if (!mode.isEmpty()) svc.setFlashlightMode(mode);
                    if (thr >= 0)       svc.setFlashlightThreshold(thr);
                    HttpUtil.sendJson(ex, 200, svc.flashlightJson().toString());
                    return;
                }
                HttpUtil.sendError(ex, 405, "Method not allowed");
                return;
            }

            if ("/api/sensor/calib/start".equals(path) && "POST".equals(method)) {
                String body = HttpUtil.readBody(ex, 4096);
                boolean thenZero  = false;
                int     durationS = svc.getConfig().calibDurationSec;
                try {
                    if (!body.isEmpty()) {
                        JSONObject req = new JSONObject(body);
                        thenZero = req.optBoolean("thenZero", false);
                        if (req.has("durationSec")) {
                            durationS = req.optInt("durationSec", durationS);
                        }
                    }
                } catch (Exception ignored) {}
                svc.startCalibStatic(thenZero, durationS);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("calib", svc.calibStatusJson());
                HttpUtil.sendJson(ex, 200, o.toString());
                return;
            }

            // ---------- конфиг ----------
            if ("/api/sensor/config".equals(path)) {
                if (!HttpUtil.checkBasic(ex, svc.getConfig())) {
                    ex.getResponseHeaders().set("WWW-Authenticate",
                            "Basic realm=\"SensorConfig\"");
                    HttpUtil.sendError(ex, 401, "Auth required");
                    return;
                }
                if ("GET".equals(method)) {
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("config", svc.getConfig().toPublicJson());
                    o.put("httpPortActive", SensorService.getCurrentHttpPort());
                    o.put("cameraList", svc.getCameraList());
                    HttpUtil.sendJson(ex, 200, o.toString());
                    return;
                }
                if ("POST".equals(method)) {
                    String body = HttpUtil.readBody(ex, 16384);
                    JSONObject patch = new JSONObject(body);
                    int oldHttpPort = svc.getConfig().httpPort;
                    svc.patchConfig(patch);
                    int newHttpPort = svc.getConfig().httpPort;

                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("config", svc.getConfig().toPublicJson());
                    o.put("httpPortActive", SensorService.getCurrentHttpPort());
                    o.put("cameraList", svc.getCameraList());
                    if (newHttpPort != oldHttpPort) {
                        o.put("portChanged", true);
                        o.put("oldPort", oldHttpPort);
                        o.put("newPort", newHttpPort);
                        o.put("note", "Порт сохранён. Нажмите «Применить HTTP-порт».");
                    }
                    HttpUtil.sendJson(ex, 200, o.toString());
                    return;
                }
                HttpUtil.sendError(ex, 405, "Method not allowed");
                return;
            }

            // ---------- перезапуск HTTP ----------
            if ("/api/sensor/restart-http".equals(path) && "POST".equals(method)) {
                if (!HttpUtil.checkBasic(ex, svc.getConfig())) {
                    ex.getResponseHeaders().set("WWW-Authenticate",
                            "Basic realm=\"SensorConfig\"");
                    HttpUtil.sendError(ex, 401, "Auth required");
                    return;
                }
                String body = HttpUtil.readBody(ex, 4096);
                JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                int requested = req.optInt("port", svc.getConfig().httpPort);
                int oldPort   = SensorService.getCurrentHttpPort();
                boolean ok = svc.scheduleHttpRestart(requested);
                JSONObject resp = new JSONObject();
                resp.put("ok", ok);
                resp.put("oldPort", oldPort);
                resp.put("newPort", requested);
                if (ok && requested != oldPort) {
                    resp.put("note", "Сервер перезапустится на порту "
                            + requested + " через ~300 мс");
                }
                HttpUtil.sendJson(ex, 200, resp.toString());
                return;
            }

            // ---------- внешний HTTP-push ----------
            if ("/api/sensor/push".equals(path) && "POST".equals(method)) {
                String body = HttpUtil.readBody(ex, 65536);
                JSONObject o = new JSONObject(body);
                o.put("received_ms", System.currentTimeMillis());
                o.put("received_via", "http_push");
                svc.getBroadcaster().publish(o);
                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                HttpUtil.sendJson(ex, 200, ok.toString());
                return;
            }

            HttpUtil.sendError(ex, 404, "Unknown: " + path);
        } catch (Exception e) {
            HttpUtil.sendError(ex, 500, e.getMessage());
        }
    }
}