import { spawnSync } from "node:child_process";
import { loadHookConfig } from "./common.mjs";

const payload = process.argv[2] ?? "{}";

try {
  const config = await loadHookConfig();
  if (Array.isArray(config.previousNotify) && config.previousNotify.length > 0) {
    const [command, ...args] = config.previousNotify;
    spawnSync(command, [...args, payload], { windowsHide: true, stdio: "ignore" });
  }
} catch {
  // A previous notifier is best-effort only.
}

await import("./turn-ended.mjs");
