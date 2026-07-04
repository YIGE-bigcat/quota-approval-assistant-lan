package com.codexbridge.approval;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

final class Prefs {
    private static final String NAME = "approval_sync";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_MONITOR_ENABLED = "monitor_enabled";
    private static final String KEY_WATCH_PAIRED_AT = "watch_paired_at";
    private static final String KEY_WATCH_PAIR_LABEL = "watch_pair_label";

    private Prefs() {
    }

    static String baseUrl(Context context) {
        String fallback = context.getString(R.string.default_bridge_url);
        String value = prefs(context).getString(KEY_BASE_URL, fallback);
        if (value == null || value.trim().isEmpty()) return fallback;
        return stripTrailingSlash(value.trim());
    }

    static String token(Context context) {
        String fallback = context.getString(R.string.default_bridge_token);
        String value = prefs(context).getString(KEY_TOKEN, fallback);
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    static void saveBridge(Context context, String baseUrl, String token) {
        prefs(context).edit()
                .putString(KEY_BASE_URL, stripTrailingSlash(baseUrl.trim()))
                .putString(KEY_TOKEN, token.trim())
                .apply();
    }

    static boolean monitorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MONITOR_ENABLED, false);
    }

    static void setMonitorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply();
    }

    static void saveWatchPair(Context context, String token, String label) {
        prefs(context).edit()
                .putString(KEY_TOKEN, token.trim())
                .putString(KEY_WATCH_PAIR_LABEL, label == null ? "" : label.trim())
                .putLong(KEY_WATCH_PAIRED_AT, System.currentTimeMillis())
                .apply();
    }

    static boolean watchPaired(Context context) {
        return prefs(context).getLong(KEY_WATCH_PAIRED_AT, 0L) > 0L;
    }

    static String watchPairLabel(Context context) {
        String label = prefs(context).getString(KEY_WATCH_PAIR_LABEL, "");
        return label == null ? "" : label;
    }

    static String dashboardUrl(Context context) {
        return baseUrl(context) + "/?token=" + encode(token(context));
    }

    static String statusUrl(Context context) {
        return baseUrl(context) + "/api/status?token=" + encode(token(context));
    }

    static String decisionUrl(Context context, String approvalId) {
        return baseUrl(context) + "/api/approvals/" + approvalId + "/decision?token=" + encode(token(context));
    }

    static String watchStatusUrl(Context context) {
        return baseUrl(context) + "/api/watch/status?token=" + encode(token(context));
    }

    static String watchDecisionUrl(Context context, String approvalId) {
        return baseUrl(context) + "/api/watch/approvals/" + approvalId + "/decision?token=" + encode(token(context));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static String stripTrailingSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return value;
        }
    }
}
