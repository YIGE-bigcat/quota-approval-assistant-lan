import { randomBytes } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { networkInterfaces } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { shouldSkipRemoteApproval } from "./autoApproval.mjs";
import { buildQuotaDisplay, readLatestQuota } from "./quota.mjs";
import { quotaSummary, sendNtfy, shortNotificationSummary } from "./notify.mjs";
import { BridgeStore } from "./store.mjs";
import { buildWatchStatusPayload } from "./watchPayload.mjs";

const sourceDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.dirname(sourceDirectory);
const publicDirectory = path.join(projectDirectory, "public");
const configPath = process.env.CODEX_WATCH_CONFIG ?? path.join(projectDirectory, "config.local.json");
const codexHome = process.env.CODEX_HOME ?? path.join(process.env.USERPROFILE ?? "", ".codex");
const quotaDatabasePaths = [
  path.join(codexHome, "sqlite", "logs_2.sqlite"),
  path.join(codexHome, "logs_2.sqlite"),
];
const quotaSources = {
  sessionRoot: path.join(codexHome, "sessions"),
  databasePaths: quotaDatabasePaths,
};
const codexGlobalStatePath = path.join(codexHome, ".codex-global-state.json");

async function loadConfig() {
  try {
    return withConfigDefaults(JSON.parse(await readFile(configPath, "utf8")));
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    const config = {
      port: 8787,
      bind: "0.0.0.0",
      publicBaseUrl: "",
      bridgeToken: randomBytes(24).toString("hex"),
      internalSecret: randomBytes(24).toString("hex"),
      approvalTimeoutSeconds: 300,
      ntfy: {
        enabled: false,
        baseUrl: "https://ntfy.sh",
        topic: `codex-watch-${randomBytes(12).toString("hex")}`,
      },
    };
    await writeFile(configPath, `${JSON.stringify(config, null, 2)}\n`);
    return withConfigDefaults(config);
  }
}

function withConfigDefaults(config) {
  config.notifications ??= {};
  config.notifications.turnEnded ??= {};
  config.notifications.turnEnded.enabled ??= false;
  config.notifications.showPushErrors ??= false;
  return config;
}

const config = await loadConfig();
const store = new BridgeStore(path.join(projectDirectory, "data"));
await store.load();

function localAddress() {
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family === "IPv4" && !address.internal) return address.address;
    }
  }
  return "127.0.0.1";
}

const baseUrl = (config.publicBaseUrl || `http://${localAddress()}:${config.port}`).replace(/\/$/, "");

function json(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify(body));
}

async function bodyJson(request) {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 1_000_000) throw new Error("Request body too large");
  }
  return body ? JSON.parse(body) : {};
}

function bearer(request, url) {
  const header = request.headers.authorization;
  if (header?.startsWith("Bearer ")) return header.slice(7);
  return url.searchParams.get("token");
}

function isAuthorized(request, url) {
  return bearer(request, url) === config.bridgeToken;
}

function isInternal(request) {
  return request.headers["x-bridge-secret"] === config.internalSecret;
}

function safeToolInput(input) {
  if (!input || typeof input !== "object") return "";
  const candidate = input.command ?? input.path ?? input.file_path ?? input.url ?? input.query;
  if (candidate == null) return "";
  const text = Array.isArray(candidate) ? candidate.join(" ") : String(candidate);
  return text.replace(/[\r\n]+/g, " ").slice(0, 240);
}

function permissionSummary(payload) {
  const toolInput = payload.tool_input ?? payload.toolInput;
  const toolName = payload.tool_name ?? payload.toolName ?? "\u672a\u77e5\u5de5\u5177";
  const reason = toolInput?.description ?? payload.reason ?? payload.permission ?? payload.message ?? "Codex \u8bf7\u6c42\u989d\u5916\u6743\u9650";
  const target = safeToolInput(toolInput);
  return {
    toolName,
    reason,
    target,
    brief: shortNotificationSummary([payload.title, reason, target, toolName]),
    cwd: payload.cwd ? path.basename(payload.cwd) : null,
  };
}

function approvalNotificationTitle(summary) {
  return `您的 ${summary.brief || summary.toolName || "Codex"} 项目有新权限需要审批！`;
}

function approvalNotificationMessage(summary, quota) {
  const intent = summary.reason || summary.target || "执行需要额外权限的操作";
  return [`Codex 想要 ${intent}`, quotaSummary(quota)].filter(Boolean).join("\n");
}

async function push(notification) {
  try {
    await sendNtfy(config, notification);
  } catch (error) {
    if (config.notifications?.showPushErrors) {
      await store.addEvent({ type: "push-error", title: "\u63a8\u9001\u5931\u8d25", message: error.message });
    }
  }
}

async function serveStatic(response, pathname) {
  const files = {
    "/": ["index.html", "text/html; charset=utf-8"],
    "/app.js": ["app.js", "text/javascript; charset=utf-8"],
    "/style.css": ["style.css", "text/css; charset=utf-8"],
    "/manifest.webmanifest": ["manifest.webmanifest", "application/manifest+json"],
    "/icon.svg": ["icon.svg", "image/svg+xml"],
    "/sw.js": ["sw.js", "text/javascript; charset=utf-8"],
  };
  const entry = files[pathname];
  if (!entry) return false;
  response.writeHead(200, { "Content-Type": entry[1], "Cache-Control": "no-cache" });
  response.end(await readFile(path.join(publicDirectory, entry[0])));
  return true;
}

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, baseUrl);
    if (request.method === "GET" && (await serveStatic(response, url.pathname))) return;

    if (request.method === "GET" && url.pathname === "/health") {
      return json(response, 200, { ok: true });
    }

    if (request.method === "GET" && url.pathname === "/api/status") {
      if (!isAuthorized(request, url)) return json(response, 401, { error: "unauthorized" });
      const quota = readLatestQuota(quotaSources);
      return json(response, 200, {
        quota,
        quotaDisplay: buildQuotaDisplay(quota),
        approvals: store.listApprovals(),
        events: store.listEvents(),
        serverTime: Date.now(),
      });
    }

    if (request.method === "GET" && url.pathname === "/api/watch/status") {
      if (!isAuthorized(request, url)) return json(response, 401, { error: "unauthorized" });
      const quota = readLatestQuota(quotaSources);
      return json(response, 200, buildWatchStatusPayload({
        quotaDisplay: buildQuotaDisplay(quota),
        approvals: store.listApprovals(),
        events: store.listEvents(),
        serverTime: Date.now(),
      }));
    }

    if (request.method === "POST" && url.pathname === "/internal/turn-ended") {
      if (!isInternal(request)) return json(response, 401, { error: "unauthorized" });
      if (!config.notifications?.turnEnded?.enabled) return json(response, 202, { ok: true, skipped: true });
      const payload = await bodyJson(request);
      const quota = readLatestQuota(quotaSources);
      const brief = shortNotificationSummary([
        payload.title,
        payload.task,
        payload.prompt,
        payload.message,
        payload.last_assistant_message,
      ]);
      const event = await store.addEvent({
        type: "turn-ended",
        title: "Codex \u4efb\u52a1\u5df2\u5b8c\u6210",
        message: brief,
      });
      await push({
        title: "Codex Done",
        fallbackTitle: "Codex Done",
        message: [`\u4efb\u52a1\uff1a${brief}`, quotaSummary(quota)].filter(Boolean).join("\n"),
        tags: "white_check_mark,computer",
        click: `${baseUrl}/?token=${config.bridgeToken}`,
      });
      return json(response, 202, { ok: true });
    }

    if (request.method === "POST" && url.pathname === "/internal/permission-request") {
      if (!isInternal(request)) return json(response, 401, { error: "unauthorized" });
      const payload = await bodyJson(request);
      const autoApproval = await shouldSkipRemoteApproval(payload, { codexGlobalStatePath });
      if (autoApproval.skip) {
        return json(response, 202, { skipped: true, reason: autoApproval.reason });
      }
      const quota = readLatestQuota(quotaSources);
      const approval = await store.createApproval(permissionSummary(payload), config.approvalTimeoutSeconds);
      await store.addEvent({
        type: "approval-request",
        approvalId: approval.id,
        title: "\u9700\u8981\u6743\u9650\u5ba1\u6279",
        message: approval.summary.brief,
      });
      const allowUrl = `${baseUrl}/action/${approval.actionToken}/allow`;
      const denyUrl = `${baseUrl}/action/${approval.actionToken}/deny`;
      await push({
        title: approvalNotificationTitle(approval.summary),
        fallbackTitle: approvalNotificationTitle(approval.summary),
        message: approvalNotificationMessage(approval.summary, quota),
        priority: "high",
        tags: "warning,computer",
        click: `${baseUrl}/?token=${config.bridgeToken}`,
        actions: [
          `http, Allow, ${allowUrl}, clear=true`,
          `http, Deny, ${denyUrl}, clear=true`,
          `view, Details, ${baseUrl}/?token=${config.bridgeToken}`,
        ],
      });
      return json(response, 201, { id: approval.id, expiresAt: approval.expiresAt });
    }

    const waitMatch = url.pathname.match(/^\/internal\/approvals\/([^/]+)\/wait$/);
    if (request.method === "GET" && waitMatch) {
      if (!isInternal(request)) return json(response, 401, { error: "unauthorized" });
      return json(response, 200, { decision: await store.waitForDecision(waitMatch[1], 25_000) });
    }

    const apiDecisionMatch = url.pathname.match(/^\/api\/approvals\/([^/]+)\/decision$/);
    if (request.method === "POST" && apiDecisionMatch) {
      if (!isAuthorized(request, url)) return json(response, 401, { error: "unauthorized" });
      const { decision } = await bodyJson(request);
      if (!["allow", "deny"].includes(decision)) return json(response, 400, { error: "invalid decision" });
      const changed = await store.decide(store.findApproval(apiDecisionMatch[1]), decision);
      return json(response, changed ? 200 : 409, { ok: changed });
    }

    const watchDecisionMatch = url.pathname.match(/^\/api\/watch\/approvals\/([^/]+)\/decision$/);
    if (request.method === "POST" && watchDecisionMatch) {
      if (!isAuthorized(request, url)) return json(response, 401, { error: "unauthorized" });
      const { decision } = await bodyJson(request);
      if (!["allow", "deny"].includes(decision)) return json(response, 400, { error: "invalid decision" });
      const changed = await store.decide(store.findApproval(watchDecisionMatch[1]), decision);
      return json(response, changed ? 200 : 409, { ok: changed });
    }

    const actionMatch = url.pathname.match(/^\/action\/([^/]+)\/(allow|deny)$/);
    if (request.method === "GET" && actionMatch) {
      const changed = await store.decide(store.findByActionToken(actionMatch[1]), actionMatch[2]);
      response.writeHead(changed ? 200 : 409, { "Content-Type": "text/html; charset=utf-8" });
      const resultText = changed
        ? (actionMatch[2] === "allow" ? "\u5df2\u6279\u51c6" : "\u5df2\u62d2\u7edd")
        : "\u8bf7\u6c42\u5df2\u5931\u6548";
      response.end(`<meta name="viewport" content="width=device-width"><body style="font:20px system-ui;background:#09110f;color:#fff;text-align:center;padding:25vh 24px"><h1>${resultText}</h1><p>\u53ef\u4ee5\u5173\u95ed\u6b64\u9875\u9762\u3002</p></body>`);
      return;
    }

    return json(response, 404, { error: "not found" });
  } catch (error) {
    return json(response, 500, { error: error.message });
  }
});

server.listen(config.port, config.bind, () => {
  console.log(`Codex Watch Bridge: ${baseUrl}/?token=${config.bridgeToken}`);
  console.log(`ntfy topic: ${config.ntfy.enabled ? config.ntfy.topic : "disabled"}`);
});
