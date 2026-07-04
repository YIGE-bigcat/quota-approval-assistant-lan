import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { BridgeStore } from "../src/store.mjs";

test("approval decisions wake a waiting hook and invalidate action token", async () => {
  const store = new BridgeStore(mkdtempSync(path.join(tmpdir(), "codex-watch-store-")));
  await store.load();
  const approval = await store.createApproval({ toolName: "shell_command" }, 30);
  const token = approval.actionToken;
  const waiting = store.waitForDecision(approval.id, 1000);
  assert.equal(await store.decide(approval, "allow"), true);
  assert.equal(await waiting, "allow");
  assert.equal(store.findByActionToken(token), undefined);
  assert.equal(await store.decide(approval, "deny"), false);
});

test("zero timeout approvals never expire automatically", async () => {
  const store = new BridgeStore(mkdtempSync(path.join(tmpdir(), "codex-watch-store-")));
  await store.load();
  const approval = await store.createApproval({ toolName: "shell_command" }, 0);

  assert.equal(approval.expiresAt, null);
  store.expireApprovals();
  assert.equal(store.findApproval(approval.id).status, "pending");

  const waiting = store.waitForDecision(approval.id, 1000);
  assert.equal(await store.decide(approval, "deny"), true);
  assert.equal(await waiting, "deny");
});

test("events only list currently pending approval requests", async () => {
  const store = new BridgeStore(mkdtempSync(path.join(tmpdir(), "codex-watch-store-")));
  await store.load();
  const approval = await store.createApproval({ toolName: "shell_command" }, 0);
  await store.addEvent({ type: "approval-request", approvalId: approval.id, title: "需要权限审批", message: "测试" });
  await store.addEvent({ type: "approval-request", title: "旧事件", message: "没有关联审批" });

  assert.deepEqual(store.listEvents().map((event) => event.title), ["需要权限审批"]);
  await store.decide(approval, "allow");
  assert.deepEqual(store.listEvents(), []);
});

test("state loader recovers a valid object followed by trailing junk", async () => {
  const directory = mkdtempSync(path.join(tmpdir(), "codex-watch-store-"));
  const statePath = path.join(directory, "state.json");
  writeFileSync(statePath, `${JSON.stringify({
    approvals: [{ id: "approval-1", status: "pending", summary: { toolName: "Bash" } }],
    events: [],
  })}\n]\n}\n`, "utf8");

  const store = new BridgeStore(directory);
  await store.load();

  assert.equal(store.listApprovals().length, 1);
  assert.equal(store.findApproval("approval-1").status, "pending");
});
