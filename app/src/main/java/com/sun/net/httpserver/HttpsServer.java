package com.sun.net.httpserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * HTTPS-версия HttpServer.
 *
 * Использование:
 *
 *     HttpsServer srv = HttpsServer.create(new InetSocketAddress("0.0.0.0", 8443), 0);
 *     srv.setHttpsConfigurator(new HttpsConfigurator(sslContext));
 *     srv.createContext("/", handler);
 *     srv.setExecutor(...);
 *     srv.start();
 *
 * Жизненный цикл сокета:
 *
 *   1. create()            — резервирует TCP-порт обычным ServerSocket
 *                            (сразу бросает IOException, если порт занят).
 *   2. setHttpsConfigurator() — закрывает резерв и переоткрывает
 *                            SSLServerSocket на тот же адрес.
 *   3. start()             — запускает acceptor-поток на SSL-сокете.
 *
 * Если setHttpsConfigurator() не вызван — start() бросит IllegalStateException.
 * Если setHttpsConfigurator() не смог забиндиться (порт увели между шагами 1 и 2) —
 * бросает RuntimeException. Вызывающий код должен это поймать и откатиться на HTTP.
 */
public class HttpsServer extends HttpServer {

    private volatile HttpsConfigurator configurator;

    protected HttpsServer(InetSocketAddress address, int backlog) {
        super(address, backlog);
    }

    public static HttpsServer create(InetSocketAddress addr, int backlog) throws IOException {
        HttpsServer s = new HttpsServer(addr, backlog);
        s.reservePort();
        return s;
    }

    public void setHttpsConfigurator(HttpsConfigurator configurator) {
        if (configurator == null) {
            throw new NullPointerException("HttpsConfigurator must not be null");
        }
        if (configurator.getSSLContext() == null) {
            throw new NullPointerException("HttpsConfigurator has null SSLContext");
        }
        this.configurator = configurator;

        try {
            rebindWithSsl(configurator.getSSLContext());
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to bind HTTPS socket: " + e.getMessage(), e);
        }
    }

    public HttpsConfigurator getHttpsConfigurator() {
        return configurator;
    }

    @Override
    public void start() {
        if (configurator == null) {
            throw new IllegalStateException(
                    "setHttpsConfigurator() must be called before start()");
        }
        // Страховка: если setHttpsConfigurator ещё не переоткрыл сокет
        // (например, его вызывали до create() — маловероятно, но возможно).
        if (!(serverSocket instanceof SSLServerSocket)) {
            try {
                rebindWithSsl(configurator.getSSLContext());
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to bind HTTPS socket: " + e.getMessage(), e);
            }
        }
        super.start();
    }

    // ============================================================
    // Внутренние
    // ============================================================

    /**
     * Резервирует TCP-порт обычным ServerSocket, чтобы create() мог
     * бросить IOException при конфликте портов — так же, как HttpServer.
     */
    private void reservePort() throws IOException {
        ServerSocket placeholder = new ServerSocket();
        placeholder.setReuseAddress(true);
        placeholder.bind(address, backlog > 0 ? backlog : 50);
        this.serverSocket = placeholder;
    }

    /**
     * Закрывает текущий ServerSocket (если был) и биндит SSLServerSocket
     * на тот же адрес с тем же backlog.
     */
    private void rebindWithSsl(SSLContext ssl) throws IOException {
        ServerSocket old = this.serverSocket;
        if (old != null && !old.isClosed()) {
            try { old.close(); } catch (IOException ignored) {}
        }

        SSLServerSocket ss =
                (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket();
        ss.setReuseAddress(true);

        // Принудительно включаем современные протоколы, которые требует WebView
        try {
            ss.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        } catch (Exception e) {
            try { ss.setEnabledProtocols(new String[]{"TLSv1.2"}); } catch (Exception ignored) {}
        }

        // Включаем все доступные шифры, чтобы избежать сбоя рукопожатия (Handshake)
        try {
            ss.setEnabledCipherSuites(ss.getSupportedCipherSuites());
        } catch (Exception ignored) {}

        ss.bind(address, backlog > 0 ? backlog : 50);
        this.serverSocket = ss;
    }
}