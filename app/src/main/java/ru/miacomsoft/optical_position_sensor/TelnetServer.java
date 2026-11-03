package ru.miacomsoft.optical_position_sensor;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Простой Telnet/TCP-сервер. Каждая строка — JSON-измерение.
 * Команды: HELP GET STATUS FLASHLIGHT <off|on|auto> [thr] QUIT
 */
public class TelnetServer {

    private static final String TAG = "TelnetServer";

    private final SensorService svc;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Socket> clients = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();
    private int port;

    public TelnetServer(SensorService svc) { this.svc = svc; }

    public int getPort() { return port; }
    public int getClientCount() { return clients.size(); }
    public boolean isRunning() { return running.get(); }

    public void start(int port) throws IOException {
        if (running.get()) return;
        this.port = port;
        serverSocket = new ServerSocket(port);
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "TelnetServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Listening on :" + port);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                final String id = "t-" + idGen.incrementAndGet();
                clients.put(id, s);
                Thread t = new Thread(() -> serve(id, s), "Telnet-" + id);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void serve(String id, Socket s) {
        OutputStream out;
        try { out = s.getOutputStream(); }
        catch (IOException e) { clients.remove(id); try { s.close(); } catch (Exception ignored) {} return; }

        final Object lock = new Object();

        SensorBroadcaster.Subscriber sub = new SensorBroadcaster.Subscriber() {
            @Override public void onReading(JSONObject r) throws Exception {
                synchronized (lock) {
                    out.write((r.toString() + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        };
        String subId = svc.getBroadcaster().subscribe(sub);

        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));
            synchronized (lock) {
                out.write(("WELCOME OpticalPositionSensor Telnet\r\n"
                        + "TYPE HELP FOR COMMANDS\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String resp = handleCommand(line);
                synchronized (lock) {
                    out.write((resp + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if ("BYE".equals(resp)) break;
            }
        } catch (Exception e) {
            // disconnect
        } finally {
            svc.getBroadcaster().unsubscribe(subId);
            clients.remove(id);
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private String handleCommand(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toUpperCase();
        try {
            switch (cmd) {
                case "HELP":
                    return "COMMANDS: HELP GET STATUS FLASHLIGHT <off|on|auto> [thr] QUIT";
                case "GET": {
                    JSONObject r = svc.getBroadcaster().getLast();
                    return r == null ? "{\"valid\":false}" : r.toString();
                }
                case "STATUS":
                    return svc.statusJson().toString();
                case "FLASHLIGHT": {
                    if (parts.length < 2) return "ERR: FLASHLIGHT <off|on|auto> [threshold]";
                    svc.setFlashlightMode(parts[1]);
                    if (parts.length >= 3) {
                        try { svc.setFlashlightThreshold(Integer.parseInt(parts[2])); }
                        catch (NumberFormatException ignore) {}
                    }
                    return svc.flashlightJson().toString();
                }
                case "QUIT":
                case "EXIT":
                    return "BYE";
                default:
                    return "ERR: unknown command";
            }
        } catch (Exception e) {
            return "ERR: " + e.getMessage();
        }
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        for (Socket s : clients.values()) { try { s.close(); } catch (Exception ignored) {} }
        clients.clear();
        serverSocket = null;
        Log.i(TAG, "stopped");
    }
}