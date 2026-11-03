package com.sun.net.httpserver;

public interface HttpHandler {
    void handle(HttpExchange exchange) throws Exception;
}