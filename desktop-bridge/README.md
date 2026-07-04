# Codex Watch Bridge for HUAWEI WATCH GT 5 Pro

中文安装和使用步骤请阅读：[`华为手表GT5Pro安装使用说明.md`](./华为手表GT5Pro安装使用说明.md)

This local bridge provides three functions:

1. Sends Codex task-complete and permission-review notifications to an Android phone through `ntfy`; Huawei Health can mirror those notifications to WATCH GT 5 Pro.
2. Reads the latest real `codex.rate_limits` event from Codex Desktop's local log database and shows remaining percentages for the 5-hour and 7-day windows.
3. Lets a permission request wait for an allow/deny decision. `ntfy` notification actions and the mobile dashboard both return that decision to the Codex `PermissionRequest` hook.

## Requirements

- Windows with Codex Desktop and Node.js 22.5 or newer.
- Android phone paired with WATCH GT 5 Pro through Huawei Health.
- `ntfy` Android app for push notifications, or another notification provider added in `src/notify.mjs`.
- Phone and PC on the same LAN for dashboard access. Remote approval requires an HTTPS tunnel and `publicBaseUrl`.

## Install

1. Install `ntfy` on the phone and subscribe to a private, random topic.
2. In Huawei Health, enable watch notifications for `ntfy`.
3. Run PowerShell in this folder:

```powershell
.\install.ps1 -NtfyTopic "your-private-random-topic" -RegisterStartup
.\start.ps1
```

4. Restart Codex Desktop after installation.
5. Open the dashboard URL printed by `start.ps1` on the phone. Add it to the home screen if desired.

The installer backs up `~/.codex/config.toml` and `~/.codex/hooks.json`. It preserves the previous `notify` command through `notify-dispatcher.mjs`.

## Watch behavior

- Task-complete and review notifications are visible on the watch when Huawei Health notification mirroring is enabled.
- Whether GT 5 Pro exposes third-party notification action buttons depends on phone OS, Huawei Health, region, and watch firmware. If actions are not exposed on the watch, tapping the notification opens the approval page on the phone.
- The dashboard is a phone/watch-browser-friendly companion page, not a signed native HarmonyOS watch package. A native package requires a Huawei developer account, DevEco Studio, signing credentials, and device-side testing.

## Security

- Approval action URLs are random, single-use, and expire after five minutes by default.
- The bridge only pushes the tool name, reason, and a short target summary.
- Keep the `ntfy` topic, dashboard token, and `config.local.json` private.
- For internet access, use an authenticated HTTPS tunnel. Do not expose port `8787` directly to the public internet.

## Test

```powershell
npm.cmd test
node --check src/server.mjs
node --check hooks/permission-request.mjs
```
