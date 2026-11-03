package ru.miacomsoft.optical_position_sensor;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Base64;

import com.sun.net.httpserver.HttpExchange;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class HttpUtil {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long    IP_CACHE_MS = 5000L;

    private static volatile String cachedLocalIp;
    private static volatile long   cachedLocalIpTime;

    // ============================================================
    // CORS / Options
    // ============================================================

    public static void handleCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Authorization, X-Auth-Token");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    public static boolean handleOptions(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            handleCors(ex);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    // ============================================================
    // JSON / Error helpers
    // ============================================================

    public static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(UTF_8);
        handleCors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        OutputStream out = ex.getResponseBody();
        try { out.write(body); } finally { out.close(); }
    }

    public static void sendError(HttpExchange ex, int status, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", message);
            o.put("status", status);
            sendJson(ex, status, o.toString());
        } catch (JSONException e) {
            try { ex.sendResponseHeaders(status, -1); ex.close(); } catch (IOException ignored) {}
        } catch (IOException ignored) {}
    }

    public static String readBody(HttpExchange ex, int max) throws IOException {
        InputStream is = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            byte[] tmp = new byte[4096];
            int n; int total = 0;
            while ((n = is.read(tmp)) > 0) {
                total += n;
                if (total > max) throw new IOException("Body too large");
                buf.write(tmp, 0, n);
            }
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
        return new String(buf.toByteArray(), UTF_8).trim();
    }

    // ============================================================
    // Query parsing
    // ============================================================

    public static Map<String, String> parseQuery(String q) {
        Map<String, String> r = new HashMap<String, String>();
        if (q == null || q.isEmpty()) return r;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) r.put(urlDecode(pair), "");
            else r.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
        }
        return r;
    }

    public static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    // ============================================================
    // Local IP detection
    //
    // Источники (по приоритету):
    //   1. WifiManager.getConnectionInfo().getIpAddress()
    //   2. WifiManager.getDhcpInfo().ipAddress
    //   3. NetworkInterface enumeration (fallback)
    //
    // getLocalIp(Context) — предпочтительный вызов: покрывает случай,
    // когда на старых ROM перечисление интерфейсов не находит wlan0.
    // ============================================================

    /** Без Context: только NetworkInterface (устаревший вариант). */
    public static String getLocalIp() {
        return getLocalIpInternal(null);
    }

    /** С Context: сначала WifiManager, потом NetworkInterface. */
    public static String getLocalIp(Context ctx) {
        return getLocalIpInternal(ctx);
    }

    private static String getLocalIpInternal(Context ctx) {
        long now = System.currentTimeMillis();
        String cached = cachedLocalIp;
        // Кэшируем только «хороший» IP, иначе каждый раз перепроверяем
        if (cached != null && isValidLanIp(cached)
                && now - cachedLocalIpTime < IP_CACHE_MS) {
            return cached;
        }

        String ip = null;

        // 1. WifiManager (основной путь на старых Android)
        if (ctx != null) {
            ip = fromWifiManager(ctx);
        }

        // 2. NetworkInterface enumeration (резерв)
        if (!isValidLanIp(ip)) {
            ip = computeLocalIp();
        }

        if (isValidLanIp(ip)) {
            cachedLocalIp = ip;
            cachedLocalIpTime = now;
        }
        return ip;
    }

    private static String fromWifiManager(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;

            // a) Connection info
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                int ipInt = wi.getIpAddress();
                if (ipInt != 0) {
                    String s = intToIp(ipInt);
                    if (isValidLanIp(s)) return s;
                }
            }

            // b) DHCP info
            DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp != null && dhcp.ipAddress != 0) {
                String s = intToIp(dhcp.ipAddress);
                if (isValidLanIp(s)) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String intToIp(int ip) {
        return (ip & 0xFF) + "."
                + ((ip >> 8) & 0xFF) + "."
                + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 24) & 0xFF);
    }

    private static boolean isValidLanIp(String s) {
        if (s == null || s.isEmpty()) return false;
        if ("127.0.0.1".equals(s)) return false;
        if ("0.0.0.0".equals(s))   return false;
        if (s.startsWith("169.254.")) return false;
        if (!s.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return false;
        return true;
    }

    private static String computeLocalIp() {
        String preferredIp = null;
        String privateIp   = null;
        String fallback    = null;

        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return "127.0.0.1";

            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni == null) continue;

                String name = ni.getName();
                if (name == null) continue;
                if (isExcludedName(name)) continue;

                // isUp() иногда бросает на Spreadtrum — не прерываем цикл
                boolean up;
                try { up = ni.isUp(); }
                catch (Throwable t) { up = true; }   // оптимистично
                if (!up) continue;
                if (ni.isLoopback()) continue;

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                if (addrs == null) continue;

                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress()) continue;

                    String s = a.getHostAddress();
                    if (s == null || s.startsWith("169.254.")) continue;

                    if (isLanInterfaceName(name) && isValidLanIp(s)) {
                        return s;   // wlan0/eth0 — самый приоритетный
                    }
                    if (isPrivateIp(s) && preferredIp == null) {
                        preferredIp = s;
                    }
                    if (privateIp == null && isValidLanIp(s)) {
                        privateIp = s;
                    }
                    if (fallback == null) fallback = s;
                }
            }
        } catch (Throwable ignored) {}

        if (preferredIp != null) return preferredIp;
        if (privateIp   != null) return privateIp;
        if (fallback    != null) return fallback;
        return "127.0.0.1";
    }

    private static boolean isExcludedName(String name) {
        return name.startsWith("rmnet") || name.startsWith("ccmni")
                || name.startsWith("pdp")  || name.startsWith("tun")
                || name.startsWith("ppp")  || name.startsWith("dummy");
    }

    private static boolean isLanInterfaceName(String name) {
        return name.startsWith("wlan")   || name.startsWith("eth")
                || name.startsWith("wl")     || name.startsWith("ra")
                || name.startsWith("ath")    || name.startsWith("tiwlan")
                || name.startsWith("softap");
    }

    private static boolean isPrivateIp(String s) {
        if (s == null) return false;
        try {
            if (s.startsWith("10."))      return true;
            if (s.startsWith("192.168.")) return true;
            if (s.startsWith("172.")) {
                int i = s.indexOf('.', 4);
                if (i < 0) return false;
                int second = Integer.parseInt(s.substring(4, i));
                return second >= 16 && second <= 31;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Диагностика: все IPv4-интерфейсы + WifiManager info.
     * Возвращает массив вида [{source, name, ip, preferred}].
     */
    public static org.json.JSONArray getAllLocalIps(Context ctx) {
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();

        // 1) WifiManager
        if (ctx != null) {
            try {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null && wi.getIpAddress() != 0) {
                        String s = intToIp(wi.getIpAddress());
                        if (seen.add(s)) {
                            JSONObject o = new JSONObject();
                            o.put("source",    "WifiManager");
                            o.put("name",      "wlan0");
                            o.put("ip",        s);
                            o.put("preferred", isValidLanIp(s));
                            arr.put(o);
                        }
                    }
                    DhcpInfo dhcp = wm.getDhcpInfo();
                    if (dhcp != null && dhcp.ipAddress != 0) {
                        String s = intToIp(dhcp.ipAddress);
                        if (seen.add(s)) {
                            JSONObject o = new JSONObject();
                            o.put("source",    "DhcpInfo");
                            o.put("name",      "wlan0");
                            o.put("ip",        s);
                            o.put("preferred", isValidLanIp(s));
                            arr.put(o);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2) NetworkInterface
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en != null) {
                while (en.hasMoreElements()) {
                    NetworkInterface ni = en.nextElement();
                    if (ni == null) continue;

                    String name = ni.getName();
                    if (name == null) continue;

                    try {
                        if (ni.isLoopback()) continue;
                    } catch (Throwable t) { /* ignore */ }

                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    if (addrs == null) continue;

                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (!(a instanceof Inet4Address)) continue;
                        if (a.isLoopbackAddress()) continue;

                        String ip = a.getHostAddress();
                        if (ip == null) continue;
                        if (!seen.add(ip)) continue;

                        try {
                            JSONObject o = new JSONObject();
                            o.put("source",    "NetworkInterface");
                            o.put("name",      name);
                            o.put("ip",        ip);
                            o.put("preferred",
                                    !isExcludedName(name)
                                            && isValidLanIp(ip)
                                            && (isLanInterfaceName(name) || isPrivateIp(ip)));
                            arr.put(o);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        return arr;
    }

    // ============================================================
    // HTTP Basic Auth
    // ============================================================

    public static boolean checkBasic(HttpExchange ex, SensorConfig cfg) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Basic ")) return false;
        try {
            String b64 = h.substring("Basic ".length()).trim();
            byte[] dec = Base64.decode(b64, Base64.DEFAULT);
            String creds = new String(dec, UTF_8);
            int i = creds.indexOf(':');
            if (i < 0) return false;
            String u = creds.substring(0, i);
            String p = creds.substring(i + 1);
            return cfg.configUser.equals(u) && cfg.configPass.equals(p);
        } catch (Exception e) { return false; }
    }
}