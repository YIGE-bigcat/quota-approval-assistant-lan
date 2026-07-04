import { bridgeFetch } from "./common.mjs";

const payload = (() => {
  try {
    return JSON.parse(process.argv[2] ?? "{}");
  } catch {
    return { message: process.argv.slice(2).join(" ") };
  }
})();

try {
  await bridgeFetch("/internal/turn-ended", {
    method: "POST",
    body: JSON.stringify(payload),
    timeout: 10_000,
  });
} catch {
  // Notification delivery must never make a completed Codex turn fail.
}
