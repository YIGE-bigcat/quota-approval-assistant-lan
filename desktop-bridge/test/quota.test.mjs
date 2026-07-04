import assert from "node:assert/strict";
import { test } from "node:test";
import { DatabaseSync } from "node:sqlite";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { buildQuotaDisplay, extractJsonObject, readLatestQuota } from "../src/quota.mjs";

function createLogDatabase() {
  const directory = mkdtempSync(path.join(tmpdir(), "codex-watch-"));
  const databasePath = path.join(directory, "logs.sqlite");
  const database = new DatabaseSync(databasePath);
  database.exec("CREATE TABLE logs (id INTEGER PRIMARY KEY, ts INTEGER, target TEXT, feedback_log_body TEXT)");
  return { database, databasePath };
}

test("extractJsonObject handles nested rate limit payloads", () => {
  const payload = { type: "codex.rate_limits", rate_limits: { primary: { used_percent: 42 } } };
  const text = `prefix websocket event: ${JSON.stringify(payload)} suffix`;
  assert.deepEqual(JSON.parse(extractJsonObject(text)), payload);
});

test("readLatestQuota returns real remaining percentages", () => {
  const { database, databasePath } = createLogDatabase();
  const event = {
    type: "codex.rate_limits",
    plan_type: "plus",
    rate_limits: {
      allowed: true,
      limit_reached: false,
      primary: { used_percent: 52, window_minutes: 300, reset_at: 1000, reset_after_seconds: 10 },
      secondary: { used_percent: 37, window_minutes: 10080, reset_at: 2000, reset_after_seconds: 20 },
    },
  };
  database.prepare("INSERT INTO logs VALUES (?, ?, ?, ?)").run(1, 123, "codex_api::endpoint::responses_websocket", `event ${JSON.stringify(event)}`);
  database.close();

  const quota = readLatestQuota(databasePath);
  assert.equal(quota.primary.remainingPercent, 48);
  assert.equal(quota.secondary.remainingPercent, 63);
  assert.equal(quota.planType, "plus");
});

test("readLatestQuota skips rows that only mention rate limit strings", () => {
  const { database, databasePath } = createLogDatabase();
  const realEvent = {
    type: "codex.rate_limits",
    rate_limits: {
      allowed: true,
      limit_reached: false,
      primary: { used_percent: 12, window_minutes: 300, reset_at: 1000, reset_after_seconds: 10 },
    },
  };
  const fakeEvent = { type: "response.output_item.done", arguments: '{"type":"codex.rate_limits"}' };
  database.prepare("INSERT INTO logs VALUES (?, ?, ?, ?)").run(1, 123, "codex_api::sse::responses", JSON.stringify(realEvent));
  database.prepare("INSERT INTO logs VALUES (?, ?, ?, ?)").run(2, 124, "codex_api::sse::responses", JSON.stringify(fakeEvent));
  database.close();

  const quota = readLatestQuota(databasePath);
  assert.equal(quota.primary.usedPercent, 12);
});

test("readLatestQuota reads current session rate limits before old database rows", () => {
  const directory = mkdtempSync(path.join(tmpdir(), "codex-watch-"));
  const sessionRoot = path.join(directory, "sessions", "2026", "06", "19");
  mkdirSync(sessionRoot, { recursive: true });
  const sessionPath = path.join(sessionRoot, "rollout.jsonl");
  writeFileSync(
    sessionPath,
    [
      JSON.stringify({ timestamp: "2026-06-19T07:00:00.000Z", type: "event_msg", payload: { type: "agent_message" } }),
      JSON.stringify({
        timestamp: "2026-06-19T07:01:18.000Z",
        type: "event_msg",
        payload: {
          type: "token_count",
          rate_limits: {
            primary: { used_percent: 24, window_minutes: 300, resets_at: 1781861768 },
            secondary: { used_percent: 4, window_minutes: 10080, resets_at: 1782448568 },
            credits: { has_credits: false, unlimited: false, balance: "0" },
          },
        },
      }),
      "",
    ].join("\n"),
  );

  const { database, databasePath } = createLogDatabase();
  const oldEvent = {
    type: "codex.rate_limits",
    rate_limits: {
      allowed: true,
      limit_reached: false,
      primary: { used_percent: 52, window_minutes: 300, reset_at: 1000 },
      secondary: { used_percent: 37, window_minutes: 10080, reset_at: 2000 },
    },
  };
  database.prepare("INSERT INTO logs VALUES (?, ?, ?, ?)").run(1, 123, "codex_api::sse::responses", JSON.stringify(oldEvent));
  database.close();

  const quota = readLatestQuota({ sessionRoot: path.join(directory, "sessions"), databasePaths: [databasePath] });
  assert.equal(quota.primary.usedPercent, 24);
  assert.equal(quota.primary.remainingPercent, 76);
  assert.equal(quota.secondary.usedPercent, 4);
  assert.equal(quota.secondary.remainingPercent, 96);
  assert.equal(quota.credits.balance, "0");
});

test("buildQuotaDisplay exposes weekly and short-term windows", () => {
  const quota = {
    planType: "plus",
    allowed: true,
    limitReached: false,
    primary: { usedPercent: 52, remainingPercent: 48, resetAt: 1000 },
    secondary: { usedPercent: 37, remainingPercent: 63, resetAt: 2000 },
    capturedAt: 123,
  };

  const display = buildQuotaDisplay(quota, { nowMs: 123_000, maxAgeSeconds: 60 });
  assert.equal(display.available, true);
  assert.equal(display.weekly.usedPercent, 37);
  assert.equal(display.weekly.remainingPercent, 63);
  assert.equal(display.shortTerm.remainingPercent, 48);
});

test("buildQuotaDisplay handles missing quota data", () => {
  const display = buildQuotaDisplay(null);
  assert.equal(display.available, false);
  assert.equal(display.message, "暂无额度数据");
  assert.equal(display.weekly, null);
});

test("buildQuotaDisplay marks stale quota as unavailable", () => {
  const quota = {
    planType: "plus",
    limitReached: false,
    primary: { usedPercent: 52, remainingPercent: 48, resetAt: 1000 },
    secondary: { usedPercent: 37, remainingPercent: 63, resetAt: 2000 },
    capturedAt: 123,
  };

  const display = buildQuotaDisplay(quota, { nowMs: 10_000_000, maxAgeSeconds: 60 });
  assert.equal(display.available, false);
  assert.equal(display.stale, true);
  assert.equal(display.message, "额度数据已过期");
});
