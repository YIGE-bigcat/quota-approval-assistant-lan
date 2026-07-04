function percent(value) {
  return Number.isFinite(value) ? `${Math.round(value)}%` : "--";
}

function resetLabel(window) {
  if (!window?.resetAfterSeconds && !window?.resetAt) return "暂无刷新时间";
  const seconds = Number(window.resetAfterSeconds);
  if (Number.isFinite(seconds)) {
    if (seconds < 90) return "约1分钟后刷新";
    const minutes = Math.round(seconds / 60);
    if (minutes < 90) return `约${minutes}分钟后刷新`;
    const hours = Math.round(minutes / 60);
    if (hours < 36) return `约${hours}小时后刷新`;
    return `约${Math.round(hours / 24)}天后刷新`;
  }
  return "稍后刷新";
}

function windowPayload(label, window) {
  if (!window) {
    return {
      label,
      available: false,
      usedPercent: null,
      remainingPercent: null,
      resetLabel: "暂无额度数据",
    };
  }

  return {
    label,
    available: true,
    usedPercent: window.usedPercent,
    remainingPercent: window.remainingPercent,
    resetAt: window.resetAt,
    resetAfterSeconds: window.resetAfterSeconds,
    resetLabel: resetLabel(window),
    summary: `${label}剩余${percent(window.remainingPercent)}`,
  };
}

function compactApproval(approval) {
  const summary = approval.summary ?? {};
  return {
    id: approval.id,
    status: approval.status,
    title: summary.brief || summary.toolName || "权限审批",
    reason: summary.reason || "Codex 请求额外权限",
    target: summary.target || summary.cwd || "",
    toolName: summary.toolName || "",
    createdAt: approval.createdAt,
    expiresAt: approval.expiresAt,
    decidedAt: approval.decidedAt ?? null,
  };
}

function compactEvent(event) {
  return {
    id: event.id,
    type: event.type,
    title: event.title || "通知",
    message: event.message || "",
    approvalId: event.approvalId || null,
    createdAt: event.createdAt,
  };
}

export function buildWatchStatusPayload({ quotaDisplay, approvals, events = [], serverTime = Date.now() }) {
  const pending = approvals.filter((approval) => approval.status === "pending").map(compactApproval);
  const recent = approvals.filter((approval) => approval.status !== "pending").slice(0, 5).map(compactApproval);
  const shortTerm = windowPayload("5小时", quotaDisplay?.shortTerm);
  const weekly = windowPayload("本周", quotaDisplay?.weekly);
  const available = Boolean(quotaDisplay?.available);

  return {
    serverTime,
    quota: {
      available,
      message: available ? "额度可用" : quotaDisplay?.message ?? "暂无额度数据",
      planType: quotaDisplay?.planType ?? null,
      updatedAt: quotaDisplay?.capturedAt ?? null,
      shortTerm,
      weekly,
      title: available
        ? `${shortTerm.summary ?? "5小时暂无"} · ${weekly.summary ?? "本周暂无"}`
        : quotaDisplay?.message ?? "暂无额度数据",
    },
    approvals: {
      pending,
      recent,
      pendingCount: pending.length,
    },
    notifications: events.slice(0, 10).map(compactEvent),
  };
}
