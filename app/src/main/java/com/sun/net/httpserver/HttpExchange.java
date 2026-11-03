package com.sun.net.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

public class HttpExchange {

    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    private final URI uri;
    private final String method;
    private final Headers requestHeaders;
    private final InputStream requestBody;
    private final OutputStream rawOutput;
    private final Headers responseHeaders;
    private final Socket socket;

    private OutputStream responseBody;
    private boolean responseSent;
    private boolean closed;

    HttpExchange(URI uri,
                 String method,
                 Headers requestHeaders,
                 InputStream requestBody,
                 OutputStream rawOutput,
                 Headers responseHeaders,
                 Socket socket) {
        this.uri = uri;
        this.method = method;
        this.requestHeaders = requestHeaders;
        this.requestBody = requestBody;
        this.rawOutput = rawOutput;
        this.responseHeaders = responseHeaders;
        this.socket = socket;
    }

    public URI getRequestURI() { return uri; }
    public String getRequestMethod() { return method; }
    public Headers getRequestHeaders() { return requestHeaders; }
    public Headers getResponseHeaders() { return responseHeaders; }
    public InputStream getRequestBody() { return requestBody; }

    public OutputStream getResponseBody() {
        if (!responseSent) {
            throw new IllegalStateException(
                    "sendResponseHeaders() must be called before getResponseBody()");
        }
        return responseBody;
    }

    public void sendResponseHeaders(int code, long length) throws IOException {
        if (responseSent) {
            throw new IOException("response headers already sent");
        }
        responseSent = true;

        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ')
                .append(reason(code)).append("\r\n");

        boolean hasContentLength = false;
        boolean hasTransferEncoding = false;
        boolean hasConnection = false;

        for (Map.Entry<String, List<String>> e : responseHeaders.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String lower = k.toLowerCase();
            if ("content-length".equals(lower)) hasContentLength = true;
            if ("transfer-encoding".equals(lower)) hasTransferEncoding = true;
            if ("connection".equals(lower)) hasConnection = true;
        }

        if (length > 0) {
            if (!hasContentLength) {
                sb.append("Content-Length: ").append(length).append("\r\n");
            }
        } else if (length == 0) {
            if (!hasTransferEncoding) {
                sb.append("Transfer-Encoding: chunked\r\n");
            }
        } else {
            if (!hasContentLength) {
                sb.append("Content-Length: 0\r\n");
            }
        }

        if (!hasConnection) {
            sb.append("Connection: close\r\n");
        }

        for (Map.Entry<String, List<String>> e : responseHeaders.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            for (String v : e.getValue()) {
                sb.append(k).append(": ").append(v).append("\r\n");
            }
        }
        sb.append("\r\n");

        rawOutput.write(sb.toString().getBytes(ISO_8859_1));
        rawOutput.flush();

        if (length == 0) {
            responseBody = new ChunkedOutputStream(rawOutput, socket);
        } else {
            responseBody = new FixedOutputStream(rawOutput, socket);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;

        try { requestBody.close(); } catch (IOException ignored) {}
        try {
            if (responseBody != null) responseBody.close();
            else rawOutput.close();
        } catch (IOException ignored) {}

        try { socket.close(); } catch (IOException ignored) {}
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default:  return "Status";
        }
    }

    static class ChunkedOutputStream extends OutputStream {
        private final OutputStream out;
        private final Socket socket;
        private boolean done = false;

        ChunkedOutputStream(OutputStream out, Socket socket) {
            this.out = out;
            this.socket = socket;
        }

        @Override public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (done) throw new IOException("stream closed");
            if (len == 0) return;
            String header = Integer.toHexString(len) + "\r\n";
            out.write(header.getBytes(ISO_8859_1));
            out.write(b, off, len);
            out.write("\r\n".getBytes(ISO_8859_1));
            out.flush();
        }

        @Override public void flush() throws IOException {
            if (!done) out.flush();
        }

        @Override public void close() throws IOException {
            if (done) return;
            done = true;
            try {
                out.write("0\r\n\r\n".getBytes(ISO_8859_1));
                out.flush();
            } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    static class FixedOutputStream extends OutputStream {
        private final OutputStream out;
        private final Socket socket;
        private boolean closed = false;

        FixedOutputStream(OutputStream out, Socket socket) {
            this.out = out;
            this.socket = socket;
        }

        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }
        @Override public void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { out.flush(); } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}