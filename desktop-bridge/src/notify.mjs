import { spawn } from "node:child_process";

function percent(value) {
  return Number.isFinite(value) ? `${Math.round(value)}%` : "--";
}

function quotaWindowSummary(label, window) {
  if (!window) return `${label}暂无`;
  return `${label}已用${percent(window.usedPercent)} 剩${percent(window.remainingPercent)}`;
}

export function quotaSummary(quota) {
  if (!quota || quota.error) return "额度暂无最新数据";
  if (!quota.capturedAt || Date.now() / 1000 - quota.capturedAt > 30 * 60) return "额度暂无最新数据";
  return [quotaWindowSummary("5小时", quota.primary), quotaWindowSummary("本周", quota.secondary)].join("\n");
}

function cleanSummaryText(value) {
  return String(value ?? "")
    .replace(/[\r\n\t]+/g, " ")
    .replace(/\s+/g, " ")
    .replace(/[。！？!?]+$/g, "")
    .trim();
}

export function shortNotificationSummary(candidates, maxLength = 10) {
  const list = Array.isArray(candidates) ? candidates : [candidates];
  const text = cleanSummaryText(list.find((candidate) => cleanSummaryText(candidate)));
  if (!text) return "任务完成";

  const withoutPrefix = text
    .replace(/^Codex\s*/i, "")
    .replace(/^任务(已)?(完成|结束)[:：\s]*/i, "")
    .replace(/^需要[:：\s]*/i, "")
    .trim();

  return Array.from(withoutPrefix || text).slice(0, maxLength).join("");
}

function asciiHeader(value, fallback) {
  const text = String(value ?? "");
  return /^[\x00-\x7F]*$/.test(text) ? text : fallback;
}

function sendWithCurl(args, body) {
  return new Promise((resolve, reject) => {
    const child = spawn("curl.exe", args, { windowsHide: true });
    let stdout = "";
    let stderr = "";
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("curl timed out"));
    }, 20_000);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(stdout);
      else reject(new Error(stderr || `curl exited with code ${code}`));
    });
    child.stdin.end(body, "utf8");
  });
}

export async function sendNtfy(config, notification) {
  if (!config.ntfy?.enabled || !config.ntfy.topic) return { skipped: true };

  const baseUrl = config.ntfy.baseUrl.replace(/\/$/, "");
  const headers = {
    Title: asciiHeader(notification.title, notification.fallbackTitle ?? "Codex"),
    Priority: notification.priority ?? "default",
    Tags: notification.tags ?? "computer",
  };
  if (notification.click) headers.Click = notification.click;
  if (notification.actions?.length) headers.Actions = notification.actions.join("; ");

  if (config.ntfy.proxy) {
    const args = ["--silent", "--show-error", "--max-time", "15", "--proxy", config.ntfy.proxy];
    for (const [key, value] of Object.entries(headers)) {
      args.push("-H", `${key}: ${value}`);
    }
    args.push("--data-binary", "@-", `${baseUrl}/${config.ntfy.topic}`);
    const stdout = await sendWithCurl(args, notification.message);
    return stdout ? JSON.parse(stdout) : { ok: true };
  }

  const response = await fetch(`${baseUrl}/${config.ntfy.topic}`, {
    method: "POST",
    headers,
    body: notification.message,
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`ntfy returned HTTP ${response.status}`);
  return response.json();
}
