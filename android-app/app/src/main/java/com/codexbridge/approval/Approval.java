package com.codexbridge.approval;

final class Approval {
    final String id;
    final String status;
    final String title;
    final String reason;
    final String target;
    final String toolName;
    final long createdAt;

    Approval(String id, String status, String title, String reason, String target, String toolName, long createdAt) {
        this.id = id;
        this.status = status;
        this.title = title;
        this.reason = reason;
        this.target = target;
        this.toolName = toolName;
        this.createdAt = createdAt;
    }

    boolean isPending() {
        return "pending".equals(status);
    }

    String detail() {
        if (!isBlank(reason)) return reason;
        if (!isBlank(target)) return target;
        if (!isBlank(toolName)) return toolName;
        return "需要审批";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
