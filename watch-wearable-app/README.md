# 审批同步助手 Watch V1

这是 HarmonyOS/Wearable 智能穿戴手表端第一版源码。它是 `wearable` 设备工程，不是 LiteWearable 工程。

## 当前版本能力

- 查看 5 小时剩余额度。
- 查看本周剩余额度。
- 查看待审批列表。
- 在手表上批准或拒绝审批。
- 查看最近 5 条审批历史。

## 当前联动方式

V1 采用直连 bridge 的方式：

```text
GT5 Pro 手表 App
  -> http://电脑IP:8788/api/watch/status
  -> Codex Watch Bridge
```

手机端仍然负责：

- 配置和测试电脑 bridge 地址。
- 后台通知兜底。
- 后续接入 Wear Engine 时作为手机到手表的中继。

如果真机不允许手表应用直接访问局域网 HTTP，可以改用手机中继或后续接入官方 Wear Engine 路线：

```text
GT5 Pro 手表 App
  -> Wear Engine
  -> 审批同步助手手机 App
  -> Codex Watch Bridge
```

## 需要安装的官方环境

1. DevEco Studio。
2. HarmonyOS SDK / Wearable SDK。
3. 华为开发者账号。
4. 手机安装 DevEco Assistant，用于把 HAP 安装到 GT 系列手表。

华为官方 Wear Engine 文档说明，手机与穿戴设备通信可通过 Wear Engine 完成；官方 codelab 也说明需要电脑、已登录华为运动健康的华为手机、手表、Android SDK、Wear Engine SDK 和 Android Studio。

## 构建前配置

打开：

```text
entry/src/main/ets/common/BridgeConfig.ets
```

把 `BRIDGE_BASE_URL` 和 `BRIDGE_TOKEN` 改成手机 App 里同一套电脑地址和 Token。

## 在 DevEco Studio 中使用

1. 打开 DevEco Studio。
2. 选择 `Open`，打开本目录 `watch-wearable-app`。
3. 等待 DevEco 下载 HarmonyOS SDK 与 hvigor 依赖。
4. 在 Project Structure / Signing Configs 中配置调试签名。
5. 选择 Wearable/GT 设备或生成 HAP。
6. Build -> Build Hap(s)/APP(s) -> Build Hap(s)。
7. 找到生成的 `entry-default-signed.hap`。
8. 把 HAP 传到手机，使用 DevEco Assistant 安装到 GT5 Pro。

## 注意

仓库 `release/watch-wearable-unsigned.hap` 是公开配置的未签名包。真机安装需要你在 DevEco Studio 中配置自己的调试签名后重新构建签名 HAP。
