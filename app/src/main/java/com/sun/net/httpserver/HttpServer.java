package com.sun.net.httpserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpServer {

    protected final InetSocketAddress address;
    protected final int backlog;
    protected ServerSocket serverSocket;

    protected final List<HttpContext> contexts = new ArrayList<HttpContext>();

    // API-19-совместимая замена ConcurrentHashMap.newKeySet()
    protected final Set<Socket> activeSockets =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    protected Thread acceptorThread;
    protected Executor executor;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    protected HttpServer(InetSocketAddress address, int backlog) {
        this.address = address;
        this.backlog = backlog;
    }

    public static HttpServer create(InetSocketAddress addr, int backlog) throws IOException {
        HttpServer s = new HttpServer(addr, backlog);
        s.serverSocket = new ServerSocket();
        s.serverSocket.setReuseAddress(true);
        s.serverSocket.bind(addr, backlog > 0 ? backlog : 50);
        return s;
    }

    public HttpContext createContext(String path, HttpHandler handler) {
        if (path == null) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        HttpContext ctx = new HttpContext(path, handler);
        synchronized (contexts) {
            contexts.add(ctx);
            // API-19-совместимая замена List.sort(Comparator.comparingInt(...).reversed())
            Collections.sort(contexts, new Comparator<HttpContext>() {
                @Override
                public int compare(HttpContext a, HttpContext b) {
                    int la = a.getPath().length();
                    int lb = b.getPath().length();
                    if (la > lb) return -1;
                    if (la < lb) return 1;
                    return 0;
                }
            });
        }
        return ctx;
    }

    public void removeContext(HttpContext ctx) {
        synchronized (contexts) { contexts.remove(ctx); }
    }

    public void setExecutor(Executor executor) { this.executor = executor; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        if (executor == null) executor = Executors.newCachedThreadPool();

        acceptorThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "HttpServer-Acceptor");
        acceptorThread.setDaemon(true);
        acceptorThread.start();
    }

    public void stop(int delaySeconds) {
        if (!running.compareAndSet(true, false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (Socket s : activeSockets) {
            try { s.close(); } catch (IOException ignored) {}
        }
        activeSockets.clear();
    }

    public InetSocketAddress getAddress() { return address; }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(120000);
                socket.setTcpNoDelay(true);
                activeSockets.add(socket);
                executor.execute(new Runnable() {
                    @Override public void run() { handleConnection(socket); }
                });
            } catch (IOException e) {
                if (running.get()) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 3) { sendSimple(socket, 400, "Bad Request"); return; }
            String method = parts[0];
            String target = parts[1];

            Headers reqHeaders = new Headers();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    reqHeaders.add(line.substring(0, idx).trim(),
                            line.substring(idx + 1).trim());
                }
            }

            long contentLength = -1;
            String cl = reqHeaders.getFirst("Content-Length");
            if (cl != null) {
                try { contentLength = Long.parseLong(cl); } catch (NumberFormatException ignored) {}
            }

            InputStream body = contentLength > 0
                    ? new BoundedInputStream(in, contentLength)
                    : new ByteArrayInputStream(new byte[0]);

            URI uri;
            try { uri = new URI(target); }
            catch (URISyntaxException e) { sendSimple(socket, 400, "Bad URI"); return; }

            Headers respHeaders = new Headers();
            HttpExchange exchange = new HttpExchange(uri, method, reqHeaders,
                    body, out, respHeaders, socket);

            HttpHandler handler = findHandler(uri.getPath());
            if (handler == null) {
                try { exchange.sendResponseHeaders(404, -1); } catch (IOException ignored) {}
                exchange.close();
                return;
            }

            try {
                handler.handle(exchange);
            } catch (Exception e) {
                try { exchange.sendResponseHeaders(500, -1); } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        } catch (javax.net.ssl.SSLException sslEx) {
            android.util.Log.w("HttpServer",
                    "SSL connection rejected by client: " + sslEx.getMessage());
        } catch (Exception e) {
            android.util.Log.e("HttpServer",
                    "Connection error: " + e.getMessage(), e);
        } finally {
            activeSockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private HttpHandler findHandler(String path) {
        if (path == null) path = "/";
        synchronized (contexts) {
            for (HttpContext c : contexts) {
                String prefix = c.getPath();
                if ("/".equals(prefix)) {
                    // "/" — fallback, всегда последний в отсортированном contexts
                    return c.getHandler();
                }

                // Нормализация: убираем завершающий "/" у префикса (кроме корня)
                String p = (prefix.length() > 1 && prefix.endsWith("/"))
                        ? prefix.substring(0, prefix.length() - 1)
                        : prefix;

                // Матч по точному пути, по префиксу с оригинальным слэшем,
                // и по префиксу без завершающего слэша + "/"
                if (path.equals(prefix)                       // /api/sensor/sse
                        || path.equals(p)                     // /api/sensor
                        || path.startsWith(p + "/")) {        // /api/sensor/map
                    return c.getHandler();
                }
            }
        }
        return null;
    }

    private static void sendSimple(Socket socket, int code, String text) {
        try {
            String resp = "HTTP/1.1 " + code + " " + text + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(resp.getBytes(ISO_8859_1));
            socket.getOutputStream().flush();
        } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b; boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                byte[] arr = buf.toByteArray();
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r') len--;
                return new String(arr, 0, len, ISO_8859_1);
            }
            buf.write(b);
        }
        return any ? new String(buf.toByteArray(), ISO_8859_1) : null;
    }

    static class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;
        BoundedInputStream(InputStream in, long limit) { this.in = in; this.remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read(); if (b >= 0) remaining--; return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }
        @Override public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }
    }
}