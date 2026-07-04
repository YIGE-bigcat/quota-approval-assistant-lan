package com.codexbridge.approval;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class BridgeClient {
    private BridgeClient() {
    }

    static final class RawResponse {
        final int code;
        final String body;

        RawResponse(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    static StatusSnapshot fetchStatus(Context context) throws IOException, JSONException {
        HttpURLConnection connection = openConnection(Prefs.statusUrl(context), "GET");
        int code = connection.getResponseCode();
        String body = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (code != 200) throw new IOException("状态接口返回 " + code + ": " + body);
        return parseStatus(body);
    }

    static int decide(Context context, String approvalId, String decision) throws IOException {
        HttpURLConnection connection = openConnection(Prefs.decisionUrl(context, approvalId), "POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        String json = "{\"decision\":\"" + decision + "\"}";
        try (OutputStream output = connection.getOutputStream()) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        return code;
    }

    static RawResponse fetchWatchStatusRaw(Context context) throws IOException {
        return requestRaw(Prefs.watchStatusUrl(context), "GET", null);
    }

    static RawResponse decideWatchRaw(Context context, String approvalId, String requestBody) throws IOException {
        return requestRaw(Prefs.watchDecisionUrl(context, approvalId), "POST", requestBody);
    }

    private static StatusSnapshot parseStatus(String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        StatusSnapshot snapshot = new StatusSnapshot();
        JSONObject quotaDisplay = root.optJSONObject("quotaDisplay");
        snapshot.quotaSummary = quotaSummary(quotaDisplay);
        snapshot.quotaTitle = quotaTitle(quotaDisplay);

        JSONArray approvals = root.optJSONArray("approvals");
        if (approvals != null) {
            for (int i = 0; i < approvals.length(); i++) {
                JSONObject approvalJson = approvals.optJSONObject(i);
                if (approvalJson == null) continue;
                JSONObject summary = approvalJson.optJSONObject("summary");
                String toolName = opt(summary, "toolName", "权限审批");
                String title = firstNonBlank(opt(summary, "brief", ""), toolName, "权限审批");
                String reason = opt(summary, "reason", "");
                String target = opt(summary, "target", "");
                snapshot.approvals.add(new Approval(
                        approvalJson.optString("id"),
                        approvalJson.optString("status"),
                        trimTitle(title),
                        reason,
                        target,
                        toolName,
                        approvalJson.optLong("createdAt")
                ));
            }
        }
        return snapshot;
    }

    private static String quotaSummary(JSONObject display) {
        if (display == null || !display.optBoolean("available", false)) {
            String message = display == null ? "" : display.optString("message", "");
            return message.isEmpty() ? "暂无额度数据" : message;
        }
        String shortTerm = quotaPart("5小时", display.optJSONObject("shortTerm"));
        String weekly = quotaPart("本周", display.optJSONObject("weekly"));
        return shortTerm + "；" + weekly;
    }

    private static String quotaTitle(JSONObject display) {
        if (display == null || !display.optBoolean("available", false)) return "额度暂无数据";
        int shortRemaining = remaining(display.optJSONObject("shortTerm"));
        int weeklyRemaining = remaining(display.optJSONObject("weekly"));
        if (shortRemaining < 0 && weeklyRemaining < 0) return "额度暂无数据";
        if (shortRemaining < 0) return "本周剩余" + weeklyRemaining + "%";
        if (weeklyRemaining < 0) return "5小时剩余" + shortRemaining + "%";
        return "5小时剩余" + shortRemaining + "%，本周剩余" + weeklyRemaining + "%";
    }

    private static String quotaPart(String label, JSONObject object) {
        if (object == null) return label + "暂无数据";
        int used = object.optInt("usedPercent", -1);
        int remaining = object.optInt("remainingPercent", -1);
        if (used < 0 || remaining < 0) return label + "暂无数据";
        return label + "已用" + used + "% 剩余" + remaining + "%";
    }

    private static int remaining(JSONObject object) {
        if (object == null) return -1;
        return object.optInt("remainingPercent", -1);
    }

    private static HttpURLConnection openConnection(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static RawResponse requestRaw(String url, String method, String requestBody) throws IOException {
        HttpURLConnection connection = openConnection(url, method);
        if (requestBody != null) {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        String body = readBody(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        return new RawResponse(code, body);
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String opt(JSONObject object, String key, String fallback) {
        if (object == null) return fallback;
        String value = object.optString(key, fallback);
        return value == null ? fallback : value;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (!isBlank(first)) return first;
        if (!isBlank(second)) return second;
        return fallback;
    }

    private static String trimTitle(String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 16) return compact;
        return compact.substring(0, 16);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
