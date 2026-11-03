package com.sun.net.httpserver;

import javax.net.ssl.SSLContext;

/**
 * Обёртка над SSLContext для HttpsServer.
 * Совместима по имени/форме с com.sun.net.httpserver.HttpsConfigurator из JDK,
 * но не содержит HttpsParameters (для FileServer они не нужны).
 */
public class HttpsConfigurator {

    private final SSLContext sslContext;

    public HttpsConfigurator(SSLContext sslContext) {
        if (sslContext == null) {
            throw new NullPointerException("SSLContext must not be null");
        }
        this.sslContext = sslContext;
    }

    public SSLContext getSSLContext() {
        return sslContext;
    }
}