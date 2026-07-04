package com.codexbridge.approval;

final class NotificationIds {
    static final int MONITOR = 7001;
    static final int QUOTA = 7002;

    private NotificationIds() {
    }

    static int approval(String approvalId) {
        return 10000 + Math.abs(approvalId.hashCode() % 900000);
    }

    static int result(String approvalId) {
        return 920000 + Math.abs(approvalId.hashCode() % 50000);
    }
}
