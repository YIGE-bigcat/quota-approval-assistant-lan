import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import {
  extractThreadIds,
  hasAutoApprovalSignal,
  isAutoApprovedThread,
  shouldSkipRemoteApproval,
} from "../src/autoApproval.mjs";

test("payload auto-review signals skip remote approval", async () => {
  const payload = {
    tool_name: "Bash",
    _bridgeContext: {
      approvalsReviewer: "auto_review",
    },
  };

  assert.equal(hasAutoApprovalSignal(payload), true);
  assert.deepEqual(await shouldSkipRemoteApproval(payload), {
    skip: true,
    reason: "payload-auto-approved",
  });
});

test("full access payload shapes skip remote approval", async () => {
  assert.equal(hasAutoApprovalSignal({
    _bridgeContext: {
      activePermissionProfile: ":danger-full-access",
    },
  }), true);

  assert.equal(hasAutoApprovalSignal({
    _bridgeContext: {
      sandboxMode: "dangerFullAccess",
    },
  }), true);
});

test("thread id resolves to auto-approved Codex state", async () => {
  const threadId = "019ee87b-6fab-7343-9486-73840623c36c";
  const state = {
    "electron-persisted-atom-state": {
      "heartbeat-thread-permissions-by-id": {
        [threadId]: {
          approvalPolicy: "on-request",
          approvalsReviewer: "auto_review",
          sandboxPolicy: { type: "workspaceWrite" },
        },
      },
    },
  };
  const directory = mkdtempSync(path.join(tmpdir(), "codex-watch-auto-"));
  const statePath = path.join(directory, ".codex-global-state.json");
  writeFileSync(statePath, JSON.stringify(state), "utf8");

  assert.deepEqual(extractThreadIds({ _bridgeContext: { codexThreadId: threadId } }), [threadId]);
  assert.equal(isAutoApprovedThread(state, threadId), true);
  assert.deepEqual(await shouldSkipRemoteApproval(
    { _bridgeContext: { codexThreadId: threadId } },
    { codexGlobalStatePath: statePath },
  ), {
    skip: true,
    reason: "thread-auto-approved",
    threadId,
  });
});

test("thread id resolves object-shaped full access state", async () => {
  const threadId = "019eeb20-1c24-7a43-b9be-4f7e0b660334";
  const state = {
    "electron-persisted-atom-state": {
      "heartbeat-thread-permissions-by-id": {
        [threadId]: {
          activePermissionProfile: { id: ":danger-full-access", extends: null },
          approvalPolicy: "never",
          approvalsReviewer: "user",
          sandboxPolicy: { type: "dangerFullAccess" },
        },
      },
    },
  };

  assert.equal(isAutoApprovedThread(state, threadId), true);
});

test("normal user-reviewed threads still create remote approvals", async () => {
  const threadId = "019ede29-a95f-7ee2-8f8a-4e5e8f9ce792";
  const state = {
    "electron-persisted-atom-state": {
      "heartbeat-thread-permissions-by-id": {
        [threadId]: {
          approvalPolicy: "on-request",
          approvalsReviewer: "user",
          sandboxPolicy: { type: "workspaceWrite" },
        },
      },
    },
  };
  const directory = mkdtempSync(path.join(tmpdir(), "codex-watch-auto-"));
  const statePath = path.join(directory, ".codex-global-state.json");
  writeFileSync(statePath, JSON.stringify(state), "utf8");

  assert.equal(hasAutoApprovalSignal({ _bridgeContext: { approvalsReviewer: "user" } }), false);
  assert.equal(isAutoApprovedThread(state, threadId), false);
  assert.deepEqual(await shouldSkipRemoteApproval(
    { _bridgeContext: { codexThreadId: threadId } },
    { codexGlobalStatePath: statePath },
  ), {
    skip: false,
    reason: "thread-needs-user-approval",
  });
});
