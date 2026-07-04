import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

function parseState(text) {
  try {
    return JSON.parse(text);
  } catch (initialError) {
    const recovered = firstJsonValue(text);
    if (!recovered) throw initialError;
    return JSON.parse(recovered);
  }
}

function firstJsonValue(text) {
  const start = text.search(/\S/);
  if (start < 0 || !["{", "["].includes(text[start])) return null;

  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < text.length; index += 1) {
    const char = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === "\"") {
        inString = false;
      }
      continue;
    }

    if (char === "\"") {
      inString = true;
    } else if (char === "{" || char === "[") {
      depth += 1;
    } else if (char === "}" || char === "]") {
      depth -= 1;
      if (depth === 0) return text.slice(start, index + 1);
    }
  }
  return null;
}

export class BridgeStore {
  constructor(dataDirectory) {
    this.dataDirectory = dataDirectory;
    this.statePath = path.join(dataDirectory, "state.json");
    this.state = { approvals: [], events: [] };
    this.waiters = new Map();
  }

  async load() {
    await mkdir(this.dataDirectory, { recursive: true });
    try {
      this.state = parseState(await readFile(this.statePath, "utf8"));
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    this.expireApprovals();
  }

  async persist() {
    await writeFile(this.statePath, `${JSON.stringify(this.state, null, 2)}\n`);
  }

  async addEvent(event) {
    this.state.events.unshift({ id: randomUUID(), createdAt: Date.now(), ...event });
    this.state.events = this.state.events.slice(0, 50);
    await this.persist();
    return this.state.events[0];
  }

  listEvents() {
    const approvalsById = new Map(this.state.approvals.map((approval) => [approval.id, approval]));
    return this.state.events
      .filter((event) => {
        if (event.type !== "approval-request" || !event.approvalId) return false;
        return approvalsById.get(event.approvalId)?.status === "pending";
      })
      .slice(0, 20);
  }

  async createApproval(summary, timeoutSeconds) {
    const timeout = Number(timeoutSeconds);
    const expiresAt = timeout > 0 ? Date.now() + timeout * 1000 : null;
    const approval = {
      id: randomUUID(),
      actionToken: randomUUID().replaceAll("-", ""),
      status: "pending",
      createdAt: Date.now(),
      expiresAt,
      summary,
    };
    this.state.approvals.unshift(approval);
    this.state.approvals = this.state.approvals.slice(0, 100);
    await this.persist();
    return approval;
  }

  listApprovals() {
    this.expireApprovals();
    return this.state.approvals.map(({ actionToken, ...approval }) => approval);
  }

  findApproval(id) {
    this.expireApprovals();
    return this.state.approvals.find((approval) => approval.id === id);
  }

  findByActionToken(actionToken) {
    this.expireApprovals();
    return this.state.approvals.find((approval) => approval.actionToken === actionToken);
  }

  async decide(approval, decision) {
    if (!approval || approval.status !== "pending") return false;
    approval.status = decision;
    approval.decidedAt = Date.now();
    approval.actionToken = null;
    await this.persist();

    const waiters = this.waiters.get(approval.id) ?? [];
    for (const resolve of waiters) resolve(decision);
    this.waiters.delete(approval.id);
    return true;
  }

  waitForDecision(id, timeoutMs) {
    const approval = this.findApproval(id);
    if (!approval) return Promise.resolve("missing");
    if (approval.status !== "pending") return Promise.resolve(approval.status);

    return new Promise((resolve) => {
      const waiters = this.waiters.get(id) ?? [];
      const done = (decision) => {
        clearTimeout(timer);
        resolve(decision);
      };
      waiters.push(done);
      this.waiters.set(id, waiters);

      const timer = setTimeout(() => {
        const current = this.waiters.get(id) ?? [];
        this.waiters.set(id, current.filter((waiter) => waiter !== done));
        resolve("pending");
      }, timeoutMs);
    });
  }

  expireApprovals() {
    const now = Date.now();
    for (const approval of this.state.approvals) {
      if (approval.status === "pending" && Number.isFinite(approval.expiresAt) && approval.expiresAt <= now) {
        approval.status = "expired";
        approval.actionToken = null;
        const waiters = this.waiters.get(approval.id) ?? [];
        for (const resolve of waiters) resolve("expired");
        this.waiters.delete(approval.id);
      }
    }
  }
}
