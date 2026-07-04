package com.codexbridge.approval;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WatchRelayServer {
    static final int PORT = 8790;

    private static WatchRelayServer instance;

    private final Context appContext;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;

    private WatchRelayServer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    static synchronized void start(Context context) {
        if (instance != null && instance.running) return;
        instance = new WatchRelayServer(context);
        instance.startInternal();
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.stopInternal();
        instance = null;
    }

    static synchronized boolean isRunning() {
        return instance != null && instance.running;
    }

    static String localUrl() {
        return "http://" + localIpAddress() + ":" + PORT;
    }

    static String statusLine() {
        return (isRunning() ? "手表中继已开启 · " : "手表中继未开启 · ") + localUrl();
    }

    private void startInternal() {
        running = true;
        acceptExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                while (running) {
                    Socket socket = serverSocket.accept();
                    connectionExecutor.execute(() -> handle(socket));
                }
            } catch (IOException error) {
                running = false;
            }
        });
    }

    private void stopInternal() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        acceptExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(closeable.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(closeable.getOutputStream(), StandardCharsets.UTF_8))) {
            closeable.setSoTimeout(12000);
            Request request = readRequest(reader);
            if (request == null) {
                writeJson(writer, 400, "{\"error\":\"bad request\"}");
                return;
            }

            if ("OPTIONS".equals(request.method)) {
                writeResponse(writer, 204, "");
                return;
            }

            if ("GET".equals(request.method) && "/health".equals(request.path)) {
                writeJson(writer, 200, "{\"ok\":true,\"mode\":\"phone-relay\"}");
                return;
            }

            if (!authorized(request)) {
                writeJson(writer, 401, "{\"error\":\"unauthorized\"}");
                return;
            }

            if ("GET".equals(request.method) && "/api/watch/status".equals(request.path)) {
                BridgeClient.RawResponse response = BridgeClient.fetchWatchStatusRaw(appContext);
                writeJson(writer, response.code, response.body);
                return;
            }

            String approvalId = approvalIdFromPath(request.path);
            if ("POST".equals(request.method) && approvalId != null) {
                BridgeClient.RawResponse response = BridgeClient.decideWatchRaw(appContext, approvalId, request.body);
                writeJson(writer, response.code, response.body);
                return;
            }

            writeJson(writer, 404, "{\"error\":\"not found\"}");
        } catch (Exception error) {
            try {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                writeJson(writer, 500, "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}");
            } catch (Exception ignored) {
            }
        }
    }

    private Request readRequest(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null || line.trim().isEmpty()) return null;
        String[] parts = line.split(" ", 3);
        if (parts.length < 2) return null;

        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            int colon = headerLine.indexOf(':');
            if (colon <= 0) continue;
            headers.put(
                    headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    headerLine.substring(colon + 1).trim()
            );
        }

        int contentLength = 0;
        try {
            contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        char[] bodyChars = new char[Math.max(0, contentLength)];
        int read = 0;
        while (read < bodyChars.length) {
            int count = reader.read(bodyChars, read, bodyChars.length - read);
            if (count < 0) break;
            read += count;
        }

        String target = parts[1];
        int queryStart = target.indexOf('?');
        String path = queryStart >= 0 ? target.substring(0, queryStart) : target;
        String query = queryStart >= 0 ? target.substring(queryStart + 1) : "";
        return new Request(parts[0].toUpperCase(Locale.ROOT), path, query, headers, new String(bodyChars, 0, read));
    }

    private boolean authorized(Request request) {
        String expected = Prefs.token(appContext);
        String token = queryParam(request.query, "token");
        String authorization = request.headers.get("authorization");
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            token = authorization.substring(7).trim();
        }
        return expected.equals(token);
    }

    private static String approvalIdFromPath(String path) {
        String prefix = "/api/watch/approvals/";
        String suffix = "/decision";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) return null;
        String encoded = path.substring(prefix.length(), path.length() - suffix.length());
        return decode(encoded);
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return "";
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            if (name.equals(decode(key))) {
                return decode(equals >= 0 ? pair.substring(equals + 1) : "");
            }
        }
        return "";
    }

    private static void writeJson(BufferedWriter writer, int status, String body) throws IOException {
        writeResponse(writer, status, body == null || body.isEmpty() ? "{}" : body);
    }

    private static void writeResponse(BufferedWriter writer, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writer.write("HTTP/1.1 " + status + " " + reason(status) + "\r\n");
        writer.write("Content-Type: application/json; charset=utf-8\r\n");
        writer.write("Cache-Control: no-store\r\n");
        writer.write("Access-Control-Allow-Origin: *\r\n");
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        writer.write("Access-Control-Allow-Headers: Authorization, Content-Type\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(body);
        writer.flush();
    }

    private static String localIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return value;
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String reason(int status) {
        if (status == 200) return "OK";
        if (status == 204) return "No Content";
        if (status == 400) return "Bad Request";
        if (status == 401) return "Unauthorized";
        if (status == 404) return "Not Found";
        if (status == 409) return "Conflict";
        return "Internal Server Error";
    }

    private static final class Request {
        final String method;
        final String path;
        final String query;
        final Map<String, String> headers;
        final String body;

        Request(String method, String path, String query, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }
}
