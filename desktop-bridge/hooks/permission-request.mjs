import { bridgeFetch, readStdinJson } from "./common.mjs";
import { homedir } from "node:os";
import path from "node:path";
import { shouldSkipRemoteApproval } from "../src/autoApproval.mjs";

function hookResult(decision, message) {
  return {
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision: { behavior: decision, message },
    },
  };
}

try {
  const payload = await readStdinJson();
  payload._bridgeContext = {
    codexThreadId: process.env.CODEX_THREAD_ID,
    approvalsReviewer: process.env.CODEX_APPROVALS_REVIEWER,
    approvalPolicy: process.env.CODEX_APPROVAL_POLICY,
    activePermissionProfile: process.env.CODEX_ACTIVE_PERMISSION_PROFILE,
    sandboxMode: process.env.CODEX_SANDBOX_MODE,
  };
  const localSkip = await shouldSkipRemoteApproval(payload, {
    codexGlobalStatePath: path.join(homedir(), ".codex", ".codex-global-state.json"),
  });
  if (localSkip.skip) process.exit(0);

  const created = await bridgeFetch("/internal/permission-request", {
    method: "POST",
    body: JSON.stringify(payload),
    timeout: 15_000,
  });
  if (!created.ok) throw new Error(`bridge returned ${created.status}`);
  const approval = await created.json();
  if (approval.skipped) process.exit(0);

  let decision = "pending";
  const hasExpiry = Number.isFinite(approval.expiresAt);
  while (decision === "pending" && (!hasExpiry || Date.now() < approval.expiresAt)) {
    const response = await bridgeFetch(`/internal/approvals/${approval.id}/wait`, { timeout: 30_000 });
    if (!response.ok) break;
    decision = (await response.json()).decision;
  }

  if (decision === "allow") {
    console.log(JSON.stringify(hookResult("allow", "Approved from Codex Watch Bridge")));
  } else {
    console.log(JSON.stringify(hookResult("deny", "Denied or expired in Codex Watch Bridge")));
  }
} catch (error) {
  console.error(`Codex Watch Bridge permission hook failed: ${error.message}`);
  // Exit successfully without a decision so Codex keeps its normal approval UI.
}
