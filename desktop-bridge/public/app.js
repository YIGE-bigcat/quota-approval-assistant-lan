const params = new URLSearchParams(location.search);
const token = params.get("token") ?? localStorage.getItem("codex-watch-token");
if (token) localStorage.setItem("codex-watch-token", token);

const elements = Object.fromEntries([
  "connection", "quota-state", "quota-updated",
  "short-card", "weekly-card",
  "short-remaining", "short-reset",
  "weekly-remaining", "weekly-reset",
  "approval-count", "approvals", "events", "refresh", "install-app",
].map((id) => [id, document.getElementById(id)]));

let installPrompt = null;

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

window.addEventListener("beforeinstallprompt", (event) => {
  event.preventDefault();
  installPrompt = event;
  elements["install-app"].hidden = false;
});

window.addEventListener("appinstalled", () => {
  installPrompt = null;
  elements["install-app"].hidden = true;
});

elements["install-app"].addEventListener("click", async () => {
  if (!installPrompt) return;
  installPrompt.prompt();
  await installPrompt.userChoice;
  installPrompt = null;
  elements["install-app"].hidden = true;
});

function relativeTime(timestamp) {
  if (!timestamp) return "--";
  const seconds = Math.round((timestamp * 1000 - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat("zh-CN", { numeric: "auto" });
  if (Math.abs(seconds) < 60) return formatter.format(seconds, "second");
  const minutes = Math.round(seconds / 60);
  if (Math.abs(minutes) < 60) return formatter.format(minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 48) return formatter.format(hours, "hour");
  return formatter.format(Math.round(hours / 24), "day");
}

function percent(value) {
  return Number.isFinite(value) ? `${Math.round(value)}%` : "--";
}

function quotaTone(value) {
  if (!Number.isFinite(value)) return "empty";
  if (value >= 50) return "good";
  if (value >= 25) return "warn";
  return "danger";
}

function displayWindow(window) {
  if (!window) return null;
  return {
    usedPercent: Number.isFinite(window.usedPercent) ? window.usedPercent : null,
    remainingPercent: Number.isFinite(window.remainingPercent) ? window.remainingPercent : null,
    resetAt: window.resetAt,
    resetAfterSeconds: window.resetAfterSeconds,
    windowMinutes: window.windowMinutes,
  };
}

function fallbackQuotaDisplay(quota) {
  if (!quota || quota.error) {
    return {
      available: false,
      stale: false,
      message: "暂无额度数据",
      weekly: null,
      shortTerm: null,
      planType: null,
      capturedAt: null,
    };
  }
  return {
    available: true,
    stale: false,
    message: quota.limitReached ? "额度已用尽" : "额度可用",
    weekly: displayWindow(quota.secondary),
    shortTerm: displayWindow(quota.primary),
    planType: quota.planType ?? null,
    capturedAt: quota.capturedAt,
  };
}

function resetText(window) {
  return window?.resetAt ? `${relativeTime(window.resetAt)}刷新` : "暂无刷新时间";
}

function updatedText(quotaDisplay) {
  if (!quotaDisplay?.capturedAt) return "运行一次 Codex 任务后自动更新";
  return `上次更新 ${relativeTime(quotaDisplay.capturedAt)}`;
}

function setWindow(prefix, window) {
  const card = elements[`${prefix}-card`];
  const remaining = window?.remainingPercent;
  card.style.setProperty("--level", Number.isFinite(remaining) ? `${Math.max(0, Math.min(100, remaining))}%` : "0%");
  card.classList.remove("tone-good", "tone-warn", "tone-danger", "tone-empty");
  card.classList.add(`tone-${quotaTone(remaining)}`);
  elements[`${prefix}-remaining`].innerHTML = window
    ? `<b>${percent(remaining)}</b><em>剩余</em>`
    : "<b>--</b><em>无数据</em>";
  elements[`${prefix}-reset`].textContent = window ? resetText(window) : "无数据";
}

function quotaLine(quotaDisplay) {
  if (!quotaDisplay?.available) return quotaDisplay?.message ?? "暂无额度数据";
  const shortTerm = quotaDisplay.shortTerm;
  const weekly = quotaDisplay.weekly;
  return [
    shortTerm ? `5小时剩${percent(shortTerm.remainingPercent)}` : "5小时暂无",
    weekly ? `本周剩${percent(weekly.remainingPercent)}` : "本周暂无",
  ].join(" · ");
}

function approvalTitle(approval) {
  return approval.summary?.brief || approval.summary?.toolName || "权限审批";
}

function approvalDetail(approval) {
  return approval.summary?.reason || approval.summary?.target || "暂无审批原因";
}

function approvalStatusText(status) {
  return {
    pending: "待审批",
    allow: "已批准",
    deny: "已拒绝",
    expired: "已过期",
  }[status] ?? "已处理";
}

function selectPendingApproval(id) {
  document.querySelectorAll(".approval-card.is-selected").forEach((node) => {
    node.classList.remove("is-selected");
  });
  const card = document.querySelector(`[data-approval-id="${id}"]`);
  if (!card) {
    toast("这条审批已处理");
    return;
  }
  card.classList.add("is-selected");
  card.scrollIntoView({ behavior: "smooth", block: "center" });
  toast("已选中待审批项");
}

function renderQuota(quota, quotaDisplay = fallbackQuotaDisplay(quota)) {
  setWindow("short", quotaDisplay.available ? quotaDisplay.shortTerm : null);
  setWindow("weekly", quotaDisplay.available ? quotaDisplay.weekly : null);

  if (!quotaDisplay.available) {
    elements["quota-state"].textContent = quotaDisplay.message ?? "暂无额度数据";
    elements["quota-updated"].textContent = quotaDisplay.stale
      ? `${updatedText(quotaDisplay)}，请运行一次 Codex 任务刷新`
      : "运行一次 Codex 任务后自动更新";
    return;
  }

  elements["quota-state"].textContent = quotaLine(quotaDisplay);
  elements["quota-updated"].textContent = [
    updatedText(quotaDisplay),
    `套餐 ${quotaDisplay.planType ?? "--"}`,
    "数据来自 Codex 实时响应",
  ].join(" · ");
}

async function decide(id, decision) {
  const response = await fetch(`/api/approvals/${id}/decision?token=${encodeURIComponent(token)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ decision }),
  });
  if (!response.ok) throw new Error("请求已处理或失效");
  toast(decision === "allow" ? "已批准" : "已拒绝");
  await refresh();
}

function renderApprovals(approvals, quotaDisplay) {
  const pending = approvals.filter((approval) => approval.status === "pending");
  elements["approval-count"].textContent = pending.length;
  elements.approvals.replaceChildren();
  elements.approvals.className = pending.length ? "stack" : "stack empty-state";
  if (!pending.length) {
    elements.approvals.textContent = "暂无待审核请求";
    return;
  }

  for (const approval of pending) {
    const card = document.getElementById("approval-template").content.cloneNode(true);
    const article = card.querySelector(".approval-card");
    article.dataset.approvalId = approval.id;
    article.querySelector(".tool-name").textContent = approval.summary.toolName;
    article.querySelector(".expires").textContent = approval.expiresAt
      ? `${relativeTime(Math.floor(approval.expiresAt / 1000))}过期`
      : "等待审批";
    article.querySelector(".reason").textContent = approval.summary.reason;
    const target = article.querySelector(".target");
    target.textContent = approval.summary.target || approval.summary.cwd || "未提供目标摘要";
    article.querySelector(".weekly-quota").textContent = quotaLine(quotaDisplay);
    article.querySelector(".allow").addEventListener("click", () => decide(approval.id, "allow").catch((error) => toast(error.message)));
    article.querySelector(".deny").addEventListener("click", () => decide(approval.id, "deny").catch((error) => toast(error.message)));
    elements.approvals.append(card);
  }
}

function renderRecentApprovals(approvals) {
  const recent = approvals.filter((approval) => approval.status !== "pending").slice(0, 5);
  elements.events.replaceChildren();
  elements.events.className = recent.length ? "timeline" : "timeline empty-state";
  if (!recent.length) {
    elements.events.textContent = "暂无审批历史";
    return;
  }
  for (const approval of recent) {
    const card = document.createElement("button");
    card.className = `event-card status-${approval.status}`;
    card.type = "button";
    card.innerHTML = `<i class="event-marker"></i><div><strong></strong><p></p><time></time></div>`;
    card.querySelector("strong").textContent = approvalTitle(approval);
    card.querySelector("p").textContent = approvalDetail(approval);
    card.querySelector("time").textContent = `${approvalStatusText(approval.status)} · ${new Date(approval.createdAt).toLocaleString("zh-CN")}`;
    card.addEventListener("click", () => {
      if (approval.status === "pending") {
        selectPendingApproval(approval.id);
      } else {
        toast(`已完成审批：${approvalStatusText(approval.status)}`);
      }
    });
    elements.events.append(card);
  }
}

function toast(message) {
  document.querySelector(".toast")?.remove();
  const node = document.createElement("div");
  node.className = "toast";
  node.textContent = message;
  document.body.append(node);
  setTimeout(() => node.remove(), 1800);
}

async function refresh(options = {}) {
  const manual = options.manual === true;
  if (manual) {
    elements.refresh.disabled = true;
    elements.refresh.classList.add("is-refreshing");
  }
  if (!token) {
    elements.connection.className = "status-dot offline";
    toast("缺少访问令牌，请使用服务启动时显示的链接");
    if (manual) {
      elements.refresh.disabled = false;
      elements.refresh.classList.remove("is-refreshing");
    }
    return;
  }
  try {
    const response = await fetch(`/api/status?token=${encodeURIComponent(token)}`, { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const status = await response.json();
    elements.connection.className = "status-dot online";
    const quotaDisplay = status.quotaDisplay ?? fallbackQuotaDisplay(status.quota);
    renderQuota(status.quota, quotaDisplay);
    renderApprovals(status.approvals, quotaDisplay);
    renderRecentApprovals(status.approvals);
    if (manual) toast("已刷新");
  } catch {
    elements.connection.className = "status-dot offline";
    if (manual) toast("连接失败，请检查电脑端");
  } finally {
    if (manual) {
      elements.refresh.disabled = false;
      elements.refresh.classList.remove("is-refreshing");
    }
  }
}

elements.refresh.addEventListener("click", () => refresh({ manual: true }));
await refresh();
setInterval(() => refresh(), 5_000);
