import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const hookDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.dirname(hookDirectory);

export async function loadHookConfig() {
  const configPath = process.env.CODEX_WATCH_CONFIG ?? path.join(projectDirectory, "config.local.json");
  return JSON.parse(await readFile(configPath, "utf8"));
}

export async function readStdinJson() {
  let body = "";
  for await (const chunk of process.stdin) body += chunk;
  return body.trim() ? JSON.parse(body) : {};
}

export async function bridgeFetch(pathname, options = {}) {
  const config = await loadHookConfig();
  const origin = `http://127.0.0.1:${config.port}`;
  return fetch(`${origin}${pathname}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Bridge-Secret": config.internalSecret,
      ...options.headers,
    },
    signal: AbortSignal.timeout(options.timeout ?? 30_000),
  });
}
