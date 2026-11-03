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
                    if (newHttpPort != oldHttpPort) {
                        o.put("portChanged",   true);
                        o.put("oldPort",       oldHttpPort);
                        o.put("newPort",       newHttpPort);
                        o.put("note", "Порт сохранён. Сервер продолжает работать на "
                                + SensorService.getCurrentHttpPort()
                                + ". Нажмите «Применить порт и перезапустить».");
                    }
                    HttpUtil.sendJson(ex, 200, o.toString());
                    return;
                }
                HttpUtil.sendError(ex, 405, "Method not allowed");
                return;
            }

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
                    resp.put("note", "Сервер перезапустится на порту " + requested
                            + " через ~300 мс");
                }
                HttpUtil.sendJson(ex, 200, resp.toString());
                return;
            }

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