import { readFile } from "node:fs/promises";

const THREAD_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const THREAD_ID_KEYS = new Set([
  "threadid",
  "codexthreadid",
  "conversationid",
  "sessionid",
  "rolloutid",
]);

const AUTO_REVIEWER_VALUES = new Set([
  "auto_review",
  "auto-review",
]);

const FULL_ACCESS_VALUES = new Set([
  ":danger-full-access",
  "full_access",
  "full-access",
  "danger-full-access",
  "danger_full_access",
  "dangerfullaccess",
]);

const NO_MANUAL_APPROVAL_VALUES = new Set([
  "never",
  "no_approval",
  "no-approval",
]);

function normalizeKey(key) {
  return String(key).replace(/[^a-z0-9]/gi, "").toLowerCase();
}

function normalizeValue(value) {
  return String(value ?? "").trim().toLowerCase();
}

function normalizedCandidates(value) {
  const values = new Set();
  const add = (candidate) => {
    const normalized = normalizeValue(candidate);
    if (!normalized) return;
    values.add(normalized);
    values.add(normalized.replace(/^:+/, ""));
    values.add(normalized.replace(/[^a-z0-9]/g, ""));
  };

  add(value);
  if (value && typeof value === "object") {
    for (const key of ["id", "type", "mode", "name"]) add(value[key]);
  }
  return values;
}

function valueMatches(value, allowedValues) {
  for (const candidate of normalizedCandidates(value)) {
    if (allowedValues.has(candidate)) return true;
  }
  return false;
}

function walk(value, visit, depth = 0) {
  if (value == null || depth > 8) return;
  if (Array.isArray(value)) {
    for (const item of value) walk(item, visit, depth + 1);
    return;
  }
  if (typeof value !== "object") return;
  for (const [key, child] of Object.entries(value)) {
    visit(key, child);
    walk(child, visit, depth + 1);
  }
}

export function extractThreadIds(payload) {
  const ids = new Set();
  walk(payload, (key, value) => {
    if (typeof value !== "string") return;
    const normalizedKey = normalizeKey(key);
    const text = value.trim();
    if (THREAD_ID_KEYS.has(normalizedKey) && THREAD_ID_PATTERN.test(text)) ids.add(text);
  });
  return [...ids];
}

export function hasAutoApprovalSignal(payload) {
  let matched = false;
  walk(payload, (key, value) => {
    if (matched || typeof value !== "string") return;
    const normalizedKey = normalizeKey(key);
    const normalizedValue = normalizeValue(value);

    if (normalizedKey === "approvalsreviewer" && valueMatches(value, AUTO_REVIEWER_VALUES)) {
      matched = true;
      return;
    }

    if (
      ["activepermissionprofile", "permissionprofile", "permissionmode", "accessmode"].includes(normalizedKey)
      && valueMatches(value, FULL_ACCESS_VALUES)
    ) {
      matched = true;
      return;
    }

    if (normalizedKey === "approvalpolicy" && valueMatches(value, NO_MANUAL_APPROVAL_VALUES)) {
      matched = true;
      return;
    }

    if (
      ["sandboxmode", "sandboxpolicy"].includes(normalizedKey)
      && valueMatches(value, FULL_ACCESS_VALUES)
    ) {
      matched = true;
    }
  });
  return matched;
}

function permissionsFromCodexState(state) {
  return (
    state?.["electron-persisted-atom-state"]?.["heartbeat-thread-permissions-by-id"]
    ?? state?.["heartbeat-thread-permissions-by-id"]
    ?? {}
  );
}

export function isAutoApprovedThread(codexState, threadId) {
  const permissions = permissionsFromCodexState(codexState);
  const entry = permissions?.[threadId];
  if (!entry) return false;
  if (valueMatches(entry.approvalsReviewer, AUTO_REVIEWER_VALUES)) return true;
  if (valueMatches(entry.activePermissionProfile, FULL_ACCESS_VALUES)) return true;
  if (valueMatches(entry.approvalPolicy, NO_MANUAL_APPROVAL_VALUES)) return true;
  if (valueMatches(entry.sandboxPolicy, FULL_ACCESS_VALUES)) return true;
  return false;
}

export async function shouldSkipRemoteApproval(payload, { codexGlobalStatePath } = {}) {
  if (hasAutoApprovalSignal(payload)) {
    return { skip: true, reason: "payload-auto-approved" };
  }

  const threadIds = extractThreadIds(payload);
  if (!codexGlobalStatePath || threadIds.length === 0) {
    return { skip: false, reason: "no-auto-approval-signal" };
  }

  try {
    const codexState = JSON.parse(await readFile(codexGlobalStatePath, "utf8"));
    for (const threadId of threadIds) {
      if (isAutoApprovedThread(codexState, threadId)) {
        return { skip: true, reason: "thread-auto-approved", threadId };
      }
    }
  } catch {
    return { skip: false, reason: "codex-state-unavailable" };
  }

  return { skip: false, reason: "thread-needs-user-approval" };
}
