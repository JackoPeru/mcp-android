package com.example.androidmcp;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small bounded HTTP/1.1 server for the private Tailscale endpoint. */
public final class McpHttpServer {
    public static final int PORT = 8_765;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_LINE_BYTES = 4 * 1024;
    private static final int MAX_HEADERS = 32;
    static final int SOCKET_IO_TIMEOUT_MS = 8_000;
    static final int REQUEST_DEADLINE_MS = 20_000;
    private final Context context;
    private final RpcDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<Socket, RequestScope> clients = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;
    private volatile ThreadPoolExecutor executor;
    private volatile ScheduledExecutorService timeouts;
    private volatile Inet4Address address;

    public McpHttpServer(Context context) {
        this.context = context.getApplicationContext();
        dispatcher = new RpcDispatcher(this.context);
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        Inet4Address bindAddress = TailscaleAddress.find(context);
        ServerSocket socket = new ServerSocket();
        ThreadPoolExecutor pool = null;
        ScheduledExecutorService timeoutPool = null;
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, PORT), 32);
            pool = new ThreadPoolExecutor(
                    2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                    runnable -> {
                        Thread thread = new Thread(runnable, "android-mcp-rpc");
                        thread.setDaemon(true);
                        return thread;
                    });
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "android-mcp-timeout");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setRemoveOnCancelPolicy(true);
            timeoutPool = scheduler;
            address = bindAddress;
            serverSocket = socket;
            executor = pool;
            timeouts = timeoutPool;
            running.set(true);
            Thread acceptor = new Thread(this::acceptLoop, "android-mcp-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (timeoutPool != null) {
                timeoutPool.shutdownNow();
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            throw e;
        }
    }

    public synchronized void stop() {
        running.set(false);
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket client : clients.keySet()) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        for (RequestScope scope : clients.values()) scope.cancel();
        clients.clear();
        ThreadPoolExecutor pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        ScheduledExecutorService timeoutPool = timeouts;
        timeouts = null;
        if (timeoutPool != null) {
            timeoutPool.shutdownNow();
        }
        address = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String address() {
        Inet4Address current = address;
        return current == null ? "" : current.getHostAddress();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client;
            RequestScope scope;
            try {
                ServerSocket socket = serverSocket;
                if (socket == null) {
                    return;
                }
                client = socket.accept();
                scope = new RequestScope(REQUEST_DEADLINE_MS);
                synchronized (this) {
                    if (!running.get()) { close(client); continue; }
                    clients.put(client, scope);
                }
            } catch (IOException e) {
                if (running.get()) {
                    // A transient accept failure is retried while service remains running.
                    continue;
                }
                return;
            }
            ScheduledFuture<?> timeout = null;
            try {
                ThreadPoolExecutor pool = executor;
                if (pool == null) {
                    close(client);
                    clients.remove(client);
                    continue;
                }
                ScheduledExecutorService timeoutPool = timeouts;
                timeout = timeoutPool == null ? null : timeoutPool.schedule(() -> {
                    close(client);
                    scope.cancel();
                }, REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS);
                ScheduledFuture<?> requestTimeout = timeout;
                pool.execute(() -> handle(client, requestTimeout, scope));
            } catch (RejectedExecutionException e) {
                if (timeout != null) timeout.cancel(false);
                close(client);
                clients.remove(client);
            }
        }
    }

    private void handle(Socket socket, ScheduledFuture<?> timeout, RequestScope scope) {
        RequestScope.CURRENT.set(scope);
        try {
            Socket client = socket;
            client.setSoTimeout(SOCKET_IO_TIMEOUT_MS);
            InputStream input = client.getInputStream();
            Headers headers = readHeaders(input);
            if (!"POST".equals(headers.method) || !"/rpc".equals(headers.target)) {
                sendError(client, 404, "NOT_FOUND", "RPC endpoint not found");
                return;
            }
            if (!"application/json".equals(headers.contentType)) {
                sendError(client, 415, "UNSUPPORTED_MEDIA_TYPE", "JSON content type required");
                return;
            }
            String expected = SecretStore.current(context);
            if (!constantTimeBearer(headers.authorization, expected)) {
                sendError(client, 401, "AUTH_INVALID", "Bearer authentication required");
                return;
            }
            byte[] requestBytes = readBody(input, headers.contentLength);
            // Rotation during a bounded upload revokes the old token before dispatch.
            if (!constantTimeBearer(headers.authorization, SecretStore.current(context))) {
                sendError(client, 401, "AUTH_INVALID", "Bearer authentication required");
                return;
            }
            JSONObject request = parseObject(requestBytes);
            JsonArgs.only(request, "method", "params");
            String method = JsonArgs.requiredString(request, "method", 64);
            JSONObject params = request.has("params") ? request.optJSONObject("params") : new JSONObject();
            if (params == null) {
                throw new ApiException("INVALID_ARGUMENT", "params must be an object");
            }
            scope.check();
            if (!running.get() || client.isClosed()) {
                throw new ApiException("SERVICE_STOPPED", "Remote service is stopped");
            }
            Object result = dispatcher.dispatch(method, params);
            scope.check();
            JSONObject response = new JSONObject();
            response.put("result", result == null ? JSONObject.NULL : result);
            sendJson(client, 200, response);
        } catch (HttpException e) {
            sendError(socket, e.status, e.code, e.getMessage());
        } catch (ApiException e) {
            JSONObject error = new JSONObject();
            try {
                error.put("error", new JSONObject().put("code", e.code).put("message", e.getMessage()));
            } catch (JSONException ignored) {
            }
            sendJson(socket, httpStatus(e.code), error);
        } catch (JSONException e) {
            sendError(socket, 400, "INVALID_JSON", "Invalid JSON request");
        } catch (IOException e) {
            // Client disconnected or request timed out; no data/secret is logged.
        } catch (RuntimeException e) {
            sendError(socket, 500, "INTERNAL", "Internal server error");
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
            clients.remove(socket);
            close(socket);
            scope.cancel();
            RequestScope.CURRENT.remove();
        }
    }

    static int httpStatus(String code) {
        if ("INTERNAL".equals(code) || "RESPONSE_TOO_LARGE".equals(code)) return 500;
        if ("TIMEOUT".equals(code)) return 504;
        if ("SERVICE_STOPPED".equals(code)) return 503;
        if ("AUTH_REQUIRED".equals(code) || "AUTH_INVALID".equals(code)) {
            return 401;
        }
        if ("NOT_FOUND".equals(code) || "UNKNOWN_METHOD".equals(code)) {
            return 404;
        }
        if ("BUSY".equals(code)) {
            return 503;
        }
        return 400;
    }

    static Headers readHeaders(InputStream input) throws IOException, HttpException {
        String requestLine = readLine(input);
        if (requestLine == null) {
            throw new HttpException(400, "INVALID_REQUEST", "Request line required");
        }
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3 || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) {
            throw new HttpException(400, "INVALID_REQUEST", "Invalid request line");
        }
        Map<String, String> headers = new HashMap<>();
        int total = requestLine.length();
        for (int count = 0; count < MAX_HEADERS; count++) {
            String line = readLine(input);
            if (line == null) {
                throw new HttpException(400, "INVALID_REQUEST", "Headers incomplete");
            }
            total += line.length();
            if (total > MAX_HEADER_BYTES) {
                throw new HttpException(431, "HEADERS_TOO_LARGE", "Headers too large");
            }
            if (line.isEmpty()) {
                String length = headers.get("content-length");
                if (length == null) {
                    throw new HttpException(411, "LENGTH_REQUIRED", "Content-Length required");
                }
                long contentLength = parseLength(length);
                if (contentLength > SecurityValidators.MAX_JSON_BYTES) {
                    throw new HttpException(413, "BODY_TOO_LARGE", "Request body too large");
                }
                return new Headers(parts[0], parts[1], headers.get("authorization"),
                        headers.getOrDefault("content-type", ""), (int) contentLength);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(400, "INVALID_REQUEST", "Invalid header");
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (!isHeaderName(key) || value.length() > MAX_HEADER_LINE_BYTES) {
                throw new HttpException(400, "INVALID_REQUEST", "Invalid header");
            }
            if (headers.put(key, value) != null) {
                throw new HttpException(400, "INVALID_REQUEST", "Duplicate header");
            }
            if ("transfer-encoding".equals(key)) {
                throw new HttpException(400, "INVALID_REQUEST", "Chunked requests are unsupported");
            }
        }
        throw new HttpException(431, "HEADERS_TOO_LARGE", "Too many headers");
    }

    static byte[] readBody(InputStream input, int length) throws IOException, HttpException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count < 0) {
                throw new HttpException(400, "INVALID_REQUEST", "Request body incomplete");
            }
            offset += count;
        }
        return body;
    }

    private static JSONObject parseObject(byte[] body) throws JSONException, ApiException {
        String json = new String(body, StandardCharsets.UTF_8);
        if (!SecurityValidators.isBoundedJson(json)) throw new ApiException("INVALID_JSON", "Invalid or deeply nested JSON");
        JSONTokener tokener = new JSONTokener(json);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject)) {
            throw new ApiException("INVALID_JSON", "JSON object required");
        }
        if (tokener.nextClean() != 0) {
            throw new ApiException("INVALID_JSON", "Trailing JSON data");
        }
        return (JSONObject) value;
    }

    private static long parseLength(String raw) throws HttpException {
        if (raw.isEmpty() || raw.length() > 10) {
            throw new HttpException(400, "INVALID_REQUEST", "Invalid Content-Length");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                throw new HttpException(400, "INVALID_REQUEST", "Invalid Content-Length");
            }
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new HttpException(400, "INVALID_REQUEST", "Invalid Content-Length");
        }
    }

    private static boolean isHeaderName(String value) {
        if (value.isEmpty() || value.length() > 128) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static String readLine(InputStream input) throws IOException, HttpException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        for (;;) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII.name());
            }
            if (value == '\n') {
                if (previous == '\r') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                }
                throw new HttpException(400, "INVALID_REQUEST", "CRLF required");
            }
            line.write(value);
            previous = value;
            if (line.size() > MAX_HEADER_LINE_BYTES) {
                throw new HttpException(431, "HEADERS_TOO_LARGE", "Header line too large");
            }
        }
    }

    static boolean constantTimeBearer(String value, String expectedToken) {
        if (value == null || !value.startsWith("Bearer ")) {
            return false;
        }
        byte[] actual = value.substring(7).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedToken.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    private static void sendError(Socket socket, int status, String code, String message) {
        JSONObject body = new JSONObject();
        try {
            body.put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException ignored) {
        }
        sendJson(socket, status, body);
    }

    private static void sendJson(Socket socket, int status, JSONObject body) {
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > SecurityValidators.MAX_RESPONSE_BYTES) {
                bytes = "{\"error\":{\"code\":\"RESPONSE_TOO_LARGE\",\"message\":\"Response too large\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                status = 500;
            }
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 " + status + " " + reason(status)
                    + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        } catch (IOException ignored) {
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Error";
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    static final class Headers {
        final String method;
        final String target;
        final String authorization;
        final String contentType;
        final int contentLength;

        Headers(String method, String target, String authorization, String contentType, int contentLength) {
            this.method = method;
            this.target = target;
            this.authorization = authorization;
            this.contentType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.US);
            this.contentLength = contentLength;
        }
    }

    static final class HttpException extends Exception {
        final int status;
        final String code;

        HttpException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
