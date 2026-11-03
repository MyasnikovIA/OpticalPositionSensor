package ru.miacomsoft.optical_position_sensor;

import android.content.Context;
import android.content.res.AssetManager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Отдаёт файлы из assets/www/.
 * Корень "/" → "index.html".
 */
public class StaticHandler implements HttpHandler {

    private final AssetManager assets;

    public StaticHandler(Context ctx) { this.assets = ctx.getAssets(); }

    @Override
    public void handle(HttpExchange ex) throws Exception {
        if (HttpUtil.handleOptions(ex)) return;
        String method = ex.getRequestMethod().toUpperCase();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            HttpUtil.sendError(ex, 405, "Method not allowed");
            return;
        }

        String path = ex.getRequestURI().getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";

        String clean = path;
        while (clean.startsWith("/")) clean = clean.substring(1);

        // защита от ../
        if (clean.contains("..")) { HttpUtil.sendError(ex, 403, "Forbidden"); return; }

        String assetPath = "www/" + clean;
        byte[] data;
        try {
            InputStream is = assets.open(assetPath);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            is.close();
            data = buf.toByteArray();
        } catch (IOException e) {
            HttpUtil.sendError(ex, 404, "Not found: " + clean);
            return;
        }

        String ct = mimeOf(clean);
        HttpUtil.handleCors(ex);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");

        if ("HEAD".equals(method)) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private static String mimeOf(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html; charset=utf-8";
        if (l.endsWith(".js"))    return "application/javascript; charset=utf-8";
        if (l.endsWith(".css"))   return "text/css; charset=utf-8";
        if (l.endsWith(".json"))  return "application/json; charset=utf-8";
        if (l.endsWith(".svg"))   return "image/svg+xml";
        if (l.endsWith(".png"))   return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".ico"))   return "image/x-icon";
        return "application/octet-stream";
    }
}