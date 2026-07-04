package com.codexbridge.approval;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ApprovalActionReceiver extends BroadcastReceiver {
    private static final String EXTRA_APPROVAL_ID = "approval_id";
    private static final String EXTRA_DECISION = "decision";

    static PendingIntent intentFor(Context context, String approvalId, String decision) {
        Intent intent = new Intent(context, ApprovalActionReceiver.class)
                .putExtra(EXTRA_APPROVAL_ID, approvalId)
                .putExtra(EXTRA_DECISION, decision);
        int requestCode = (approvalId + decision).hashCode();
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID);
        String decision = intent.getStringExtra(EXTRA_DECISION);
        if (approvalId == null || decision == null) return;

        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                int code = BridgeClient.decide(context, approvalId, decision);
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                manager.cancel(NotificationIds.approval(approvalId));
                if (code == 200 || code == 409) {
                    showResult(context, approvalId, "allow".equals(decision) ? "已批准" : "已拒绝");
                } else {
                    showResult(context, approvalId, "审批失败：" + code);
                }
                if (Prefs.monitorEnabled(context)) ApprovalMonitorService.start(context);
            } catch (Exception error) {
                showResult(context, approvalId, "审批失败：" + error.getMessage());
            } finally {
                pendingResult.finish();
            }
        }, "approval-action").start();
    }

    private static void showResult(Context context, String approvalId, String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, ApprovalMonitorService.CHANNEL_APPROVAL)
                : new Notification.Builder(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(5000);
        }
        PendingIntent openIntent = PendingIntent.getActivity(
                context,
                2,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_approval)
                .setContentTitle("审批同步助手")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NotificationIds.result(approvalId), notification);
    }
}
