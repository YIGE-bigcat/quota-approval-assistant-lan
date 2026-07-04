package com.codexbridge.approval;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApprovalMonitorService extends Service {
    static final String ACTION_START = "com.codexbridge.approval.START";
    static final String ACTION_STOP = "com.codexbridge.approval.STOP";
    static final String CHANNEL_MONITOR = "approval_monitor";
    static final String CHANNEL_APPROVAL = "approval_requests";
    static final String CHANNEL_QUOTA = "quota_status";
    static final long POLL_MS = 8000L;
    private static final long QUOTA_NOTIFY_MS = 10L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> visibleApprovalIds = new HashSet<>();
    private String lastQuotaSummary = "";
    private long lastQuotaNotifiedAt;
    private boolean stopped;
    private boolean pollInFlight;

    public static void start(Context context) {
        Intent intent = new Intent(context, ApprovalMonitorService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ApprovalMonitorService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void publishQuotaNow(Context context) {
        ensureChannels(context);
        new Thread(() -> {
            try {
                publishQuotaNotification(context, BridgeClient.fetchStatus(context), true);
            } catch (Exception error) {
                showSimpleNotification(context, "额度刷新失败", error.getMessage());
            }
        }, "quota-publish").start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setMonitorEnabled(this, false);
            stopped = true;
            WatchRelayServer.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Prefs.setMonitorEnabled(this, true);
        startForegroundCompat(buildMonitorNotification("正在监听审批请求"));
        WatchRelayServer.start(this);
        stopped = false;
        schedulePoll(0);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        WatchRelayServer.stop();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void schedulePoll(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        if (!stopped) handler.postDelayed(pollRunnable, delayMs);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (stopped || pollInFlight) {
                schedulePoll(POLL_MS);
                return;
            }
            pollInFlight = true;
            executor.execute(() -> {
                try {
                    StatusSnapshot snapshot = BridgeClient.fetchStatus(ApprovalMonitorService.this);
                    handleSnapshot(snapshot);
                } catch (Exception error) {
                    updateMonitorNotification("连接失败：" + error.getMessage());
                } finally {
                    pollInFlight = false;
                    handler.post(() -> schedulePoll(POLL_MS));
                }
            });
        }
    };

    private void handleSnapshot(StatusSnapshot snapshot) {
        List<Approval> pending = snapshot.pendingApprovals();
        Set<String> pendingIds = new HashSet<>();
        for (Approval approval : pending) {
            pendingIds.add(approval.id);
            if (!visibleApprovalIds.contains(approval.id)) {
                showApprovalNotification(approval, snapshot.quotaSummary);
                visibleApprovalIds.add(approval.id);
            }
        }

        NotificationManager manager = notificationManager();
        Set<String> previouslyVisible = new HashSet<>(visibleApprovalIds);
        for (String visibleId : previouslyVisible) {
            if (!pendingIds.contains(visibleId)) {
                manager.cancel(NotificationIds.approval(visibleId));
                visibleApprovalIds.remove(visibleId);
            }
        }

        String text = pending.isEmpty()
                ? "无待审批，" + snapshot.quotaSummary
                : "待审批 " + pending.size() + " 条，" + snapshot.quotaSummary;
        updateMonitorNotification(text + "；" + WatchRelayServer.statusLine());
        maybePublishQuota(snapshot);
    }

    private void maybePublishQuota(StatusSnapshot snapshot) {
        long now = System.currentTimeMillis();
        boolean changed = !snapshot.quotaSummary.equals(lastQuotaSummary);
        boolean due = now - lastQuotaNotifiedAt >= QUOTA_NOTIFY_MS;
        if (changed || due) {
            publishQuotaNotification(this, snapshot, false);
            lastQuotaSummary = snapshot.quotaSummary;
            lastQuotaNotifiedAt = now;
        }
    }

    private void showApprovalNotification(Approval approval, String quotaSummary) {
        PendingIntent openIntent = PendingIntent.getActivity(
                this,
                approval.id.hashCode(),
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent allowIntent = ApprovalActionReceiver.intentFor(this, approval.id, "allow");
        PendingIntent denyIntent = ApprovalActionReceiver.intentFor(this, approval.id, "deny");

        String detail = approval.detail();
        String title = "您的 " + approval.title + " 项目有新权限需要审批！";
        String content = "Codex 想要 " + detail;
        String bigText = content + "\n" + quotaSummary;
        Notification.Builder builder = builder(CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_stat_approval)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(bigText))
                .setContentIntent(openIntent)
                .setAutoCancel(false)
                .setOngoing(false)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_approval, "批准", allowIntent).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_approval, "拒绝", denyIntent).build());

        notificationManager().notify(NotificationIds.approval(approval.id), builder.build());
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationIds.MONITOR, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NotificationIds.MONITOR, notification);
        }
    }

    private void updateMonitorNotification(String text) {
        notificationManager().notify(NotificationIds.MONITOR, buildMonitorNotification(text));
    }

    private Notification buildMonitorNotification(String text) {
        PendingIntent openIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return builder(CHANNEL_MONITOR)
                .setSmallIcon(R.drawable.ic_stat_approval)
                .setContentTitle("审批同步助手正在运行")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private Notification.Builder builder(String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, channelId);
        }
        return new Notification.Builder(this);
    }

    private static void publishQuotaNotification(Context context, StatusSnapshot snapshot, boolean alert) {
        ensureChannels(context);
        PendingIntent openIntent = PendingIntent.getActivity(
                context,
                3,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_QUOTA)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_approval)
                .setContentTitle("额度状态")
                .setContentText(snapshot.quotaTitle)
                .setStyle(new Notification.BigTextStyle().bigText(snapshot.quotaSummary))
                .setContentIntent(openIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setPriority(alert ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NotificationIds.QUOTA, notification);
    }

    private static void showSimpleNotification(Context context, String title, String text) {
        ensureChannels(context);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_QUOTA)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_stat_approval)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NotificationIds.QUOTA, notification);
    }

    private static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                "后台监听",
                NotificationManager.IMPORTANCE_LOW
        );
        monitor.setDescription("保持审批同步助手后台运行");

        NotificationChannel approvals = new NotificationChannel(
                CHANNEL_APPROVAL,
                "权限审批",
                NotificationManager.IMPORTANCE_HIGH
        );
        approvals.setDescription("Codex 权限审批通知");

        NotificationChannel quota = new NotificationChannel(
                CHANNEL_QUOTA,
                "额度状态",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        quota.setDescription("在手机和手表通知中查看 5 小时与本周剩余额度");

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(monitor);
        manager.createNotificationChannel(approvals);
        manager.createNotificationChannel(quota);
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
