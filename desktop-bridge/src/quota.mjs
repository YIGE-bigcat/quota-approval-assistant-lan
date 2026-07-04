import { DatabaseSync } from "node:sqlite";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

const RATE_LIMIT_TYPE = '"type":"codex.rate_limits"';
const DEFAULT_FRESH_SECONDS = 30 * 60;

export function extractJsonObject(text, marker = RATE_LIMIT_TYPE) {
  const markerIndex = text.lastIndexOf(marker);
  if (markerIndex < 0) return null;

  const start = text.lastIndexOf("{", markerIndex);
  if (start < 0) return null;

  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let index = start; index < text.length; index += 1) {
    const char = text[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }

    if (char === '"') inString = true;
    else if (char === "{") depth += 1;
    else if (char === "}") {
      depth -= 1;
      if (depth === 0) return text.slice(start, index + 1);
    }
  }

  return null;
}

export function normalizeRateLimits(event, capturedAt) {
  if (!event?.rate_limits) return null;

  const normalizeWindow = (window) => {
    if (!window) return null;
    const usedPercent = Number(window.used_percent);
    const resetAt = window.reset_at ?? window.resets_at;
    return {
      usedPercent,
      remainingPercent: Math.max(0, 100 - usedPercent),
      windowMinutes: window.window_minutes,
      resetAt,
      resetAfterSeconds: window.reset_after_seconds ?? (resetAt && capturedAt ? resetAt - capturedAt : null),
    };
  };

  return {
    planType: event.plan_type ?? null,
    allowed: event.rate_limits.allowed,
    limitReached: event.rate_limits.limit_reached,
    primary: normalizeWindow(event.rate_limits.primary),
    secondary: normalizeWindow(event.rate_limits.secondary),
    codeReview: event.code_review_rate_limits ?? null,
    additional: event.additional_rate_limits ?? null,
    credits: event.credits ?? null,
    capturedAt,
  };
}

function normalizeSessionRateLimits(rateLimits, capturedAt) {
  if (!rateLimits) return null;

  const normalizeWindow = (window) => {
    if (!window) return null;
    const usedPercent = Number(window.used_percent);
    if (!Number.isFinite(usedPercent)) return null;
    const resetAt = window.reset_at ?? window.resets_at;
    return {
      usedPercent,
      remainingPercent: Math.max(0, 100 - usedPercent),
      windowMinutes: window.window_minutes,
      resetAt,
      resetAfterSeconds: window.reset_after_seconds ?? (resetAt && capturedAt ? resetAt - capturedAt : null),
    };
  };

  const limitReached = Boolean(rateLimits.rate_limit_reached_type);
  return {
    planType: rateLimits.plan_type ?? null,
    allowed: !limitReached,
    limitReached,
    primary: normalizeWindow(rateLimits.primary),
    secondary: normalizeWindow(rateLimits.secondary),
    codeReview: null,
    additional: null,
    credits: rateLimits.credits ?? null,
    capturedAt,
  };
}

function normalizePercent(value) {
  if (!Number.isFinite(value)) return null;
  return Math.max(0, Math.min(100, Math.round(value)));
}

function displayWindow(window) {
  if (!window) return null;
  return {
    usedPercent: normalizePercent(window.usedPercent),
    remainingPercent: normalizePercent(window.remainingPercent),
    resetAt: window.resetAt,
    resetAfterSeconds: window.resetAfterSeconds,
    windowMinutes: window.windowMinutes,
  };
}

function isFresh(quota, nowMs = Date.now(), maxAgeSeconds = DEFAULT_FRESH_SECONDS) {
  if (!quota?.capturedAt) return false;
  return nowMs / 1000 - quota.capturedAt <= maxAgeSeconds;
}

export function buildQuotaDisplay(quota, options = {}) {
  if (!quota || quota.error) {
    return {
      available: false,
      stale: false,
      message: "暂无额度数据",
      planType: null,
      limitReached: false,
      weekly: null,
      shortTerm: null,
      capturedAt: null,
    };
  }

  if (!isFresh(quota, options.nowMs, options.maxAgeSeconds)) {
    return {
      available: false,
      stale: true,
      message: "额度数据已过期",
      planType: quota.planType ?? null,
      limitReached: Boolean(quota.limitReached),
      weekly: null,
      shortTerm: null,
      capturedAt: quota.capturedAt,
    };
  }

  return {
    available: true,
    stale: false,
    message: quota.limitReached ? "额度已用尽" : "额度可用",
    planType: quota.planType ?? null,
    limitReached: Boolean(quota.limitReached),
    weekly: displayWindow(quota.secondary),
    shortTerm: displayWindow(quota.primary),
    capturedAt: quota.capturedAt,
  };
}

function readLatestQuotaFromDatabase(databasePath) {
  let database;
  try {
    database = new DatabaseSync(databasePath, { readOnly: true });
    const rows = database
      .prepare(`
        SELECT ts, feedback_log_body
        FROM logs
        WHERE target IN (
          'codex_api::endpoint::responses_websocket',
          'codex_api::sse::responses'
        )
          AND feedback_log_body LIKE '%"type":"codex.rate_limits"%'
        ORDER BY id DESC
        LIMIT 100
      `)
      .all();

    for (const row of rows) {
      const json = extractJsonObject(row.feedback_log_body);
      if (!json) continue;
      try {
        const normalized = normalizeRateLimits(JSON.parse(json), row.ts);
        if (normalized?.primary || normalized?.secondary) return normalized;
      } catch {
        // Ignore rows that only contain quoted search strings or partial payloads.
      }
    }
    return null;
  } catch (error) {
    return { error: error.message };
  } finally {
    database?.close();
  }
}

function collectSessionFiles(root, limit = 30) {
  if (!root || !existsSync(root)) return [];
  const files = [];

  function visit(directory) {
    let entries;
    try {
      entries = readdirSync(directory, { withFileTypes: true });
    } catch {
      return;
    }

    for (const entry of entries) {
      const fullPath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(fullPath);
      } else if (entry.isFile() && entry.name.endsWith(".jsonl")) {
        try {
          const stats = statSync(fullPath);
          files.push({ path: fullPath, mtimeMs: stats.mtimeMs });
        } catch {
          // Ignore files that are rotated or locked while Codex writes them.
        }
      }
    }
  }

  visit(root);
  return files
    .sort((left, right) => right.mtimeMs - left.mtimeMs)
    .slice(0, limit)
    .map((file) => file.path);
}

function readLatestQuotaFromSessionFile(filePath) {
  let lines;
  try {
    lines = readFileSync(filePath, "utf8").trimEnd().split(/\r?\n/);
  } catch (error) {
    return { error: error.message };
  }

  for (let index = lines.length - 1; index >= 0; index -= 1) {
    const line = lines[index];
    if (!line.includes('"rate_limits"')) continue;
    try {
      const event = JSON.parse(line);
      const capturedAt = event.timestamp ? Math.floor(Date.parse(event.timestamp) / 1000) : null;
      const normalized = normalizeSessionRateLimits(event.rate_limits ?? event.payload?.rate_limits, capturedAt);
      if (normalized?.primary || normalized?.secondary) return normalized;
    } catch {
      // Ignore non-event lines or tool output that only quotes the field name.
    }
  }

  return null;
}

function readLatestQuotaFromSessionRoot(sessionRoot) {
  for (const filePath of collectSessionFiles(sessionRoot)) {
    const quota = readLatestQuotaFromSessionFile(filePath);
    if (quota && !quota.error) return quota;
  }
  return null;
}

export function readLatestQuota(databasePaths) {
  if (databasePaths && typeof databasePaths === "object" && !Array.isArray(databasePaths)) {
    const quota = readLatestQuotaFromSessionRoot(databasePaths.sessionRoot);
    if (quota) return quota;
    databasePaths = databasePaths.databasePaths;
  }

  const paths = Array.isArray(databasePaths) ? databasePaths : [databasePaths];
  let lastError = null;
  for (const databasePath of paths.filter(Boolean)) {
    const quota = readLatestQuotaFromDatabase(databasePath);
    if (quota && !quota.error) return quota;
    if (quota?.error) lastError = quota;
  }
  return lastError ?? null;
}
