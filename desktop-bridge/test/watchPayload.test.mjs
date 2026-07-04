import assert from "node:assert/strict";
import { test } from "node:test";
import { buildWatchStatusPayload } from "../src/watchPayload.mjs";

test("buildWatchStatusPayload exposes watch-friendly quota and approvals", () => {
  const payload = buildWatchStatusPayload({
    serverTime: 123,
    quotaDisplay: {
      available: true,
      planType: "plus",
      capturedAt: 100,
      shortTerm: { usedPercent: 30, remainingPercent: 70, resetAfterSeconds: 3600 },
      weekly: { usedPercent: 40, remainingPercent: 60, resetAfterSeconds: 172800 },
    },
    approvals: [
      {
        id: "pending-1",
        status: "pending",
        createdAt: 1,
        summary: { brief: "创建文件", reason: "创建桌面测试文件", target: "test.txt", toolName: "shell" },
      },
      {
        id: "done-1",
        status: "allow",
        createdAt: 2,
        decidedAt: 3,
        summary: { brief: "读取配置", reason: "读取配置文件", target: "config.json", toolName: "read" },
      },
    ],
    events: [
      {
        id: "event-1",
        type: "approval-request",
        approvalId: "pending-1",
        title: "需要权限审批",
        message: "创建文件",
        createdAt: 4,
      },
    ],
  });

  assert.equal(payload.serverTime, 123);
  assert.equal(payload.quota.shortTerm.remainingPercent, 70);
  assert.equal(payload.quota.weekly.remainingPercent, 60);
  assert.equal(payload.approvals.pendingCount, 1);
  assert.equal(payload.approvals.pending[0].title, "创建文件");
  assert.equal(payload.approvals.recent[0].status, "allow");
  assert.equal(payload.notifications[0].approvalId, "pending-1");
});
