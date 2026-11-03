package com.sun.net.httpserver;

public class HttpContext {

    private final String path;
    private final HttpHandler handler;

    HttpContext(String path, HttpHandler handler) {
        this.path = path;
        this.handler = handler;
    }

    public String getPath() {
        return path;
    }

    public HttpHandler getHandler() {
        return handler;
    }
}