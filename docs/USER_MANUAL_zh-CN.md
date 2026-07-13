# 额度审批助手（局域网版，含 Windows 桌面额度小工具）使用说明

## 1. 这个应用做什么

它把 Codex 的权限审批和额度信息同步到手机和智能穿戴设备：

- 电脑端负责接收 Codex 权限请求、读取额度、提供网页 Dashboard。
- Windows 桌面额度小工具从 Dashboard 一键启动，置顶显示 5 小时和本周额度、刷新时间与待审批数量。
- 手机端负责审批、查看额度、接收系统通知，并给手表端提供中继。
- 手表端负责查看额度、查看通知、批准或拒绝审批。

当前仓库里的手表工程是 HarmonyOS `wearable` 智能穿戴版，不是 LiteWearable 版。

## 2. 界面预览

电脑端 Dashboard：

![电脑端网页 Dashboard](images/desktop-dashboard.png)

Windows 桌面额度小工具：

![Windows 原生桌面额度小工具](images/desktop-widget.png)

手机端：

| 启动画面 | 额度面板 | 连接设置 |
| --- | --- | --- |
| <img src="images/mobile-splash.jpg" width="220"> | <img src="images/mobile-quota-dashboard.jpg" width="220"> | <img src="images/mobile-settings-redacted.jpg" width="220"> |

通知和手表端：

| 手机通知 | 手表界面 |
| --- | --- |
| <img src="images/mobile-notifications-redacted.jpg" width="320"> | <img src="images/watch-app-photo.png" width="220"> |

手机桌面图标：

<img src="images/phone-home-icon.jpg" width="220">

说明：设置页截图已经脱敏，真实使用时需要填写自己的电脑局域网地址和 token。

## 3. 电脑端配置

进入电脑端目录：

```powershell
cd desktop-bridge
npm.cmd test
node --check src/server.mjs
.\start.ps1
```

启动后记录：

- 电脑局域网 IP，例如 `192.168.1.100`。
- Bridge 端口，默认是 `8788`。
- Dashboard token。

### 3.1 使用桌面额度小工具

Windows 电脑可在 Dashboard 右上角点“悬浮窗”，打开原生无边框桌面额度小工具：

- 实时显示 5 小时额度、本周额度、刷新时间与待审批数量。
- 每 5 秒向本机 Bridge 同步一次；它不会把 token 显示在窗口中。
- 在设置中可选择显示一个或两个额度窗口，也可切换紧凑布局。
- 鼠标左键拖动窗口可移动位置，右上角 `×` 可关闭。

源代码和已构建程序位于 `desktop-bridge/scripts/`。如需重新构建，先关闭小工具，再运行：

```powershell
.\scripts\build-floating-window.ps1
```

手机和手表必须能访问电脑地址，例如：

```text
http://192.168.1.100:8788
```

如果手机打不开这个地址，优先检查：

- 手机和电脑是否在同一个 Wi-Fi。
- Windows 防火墙是否允许 Node.js / 8788 端口。
- 电脑 IP 是否变化。

## 4. 手机端安装和使用

安装包：

```text
release/phone-android-debug.apk
```

安装后打开 App：

1. 填写电脑 Bridge 地址，例如 `http://192.168.1.100:8788`。
2. 填写电脑端 token。
3. 点击保存。
4. 允许通知权限。
5. 按 App 提示打开后台运行/忽略电池优化权限。

手机端能做：

- 查看 5 小时额度和本周额度。
- 查看待审批列表。
- 批准或拒绝审批。
- 查看最近审批记录。
- 电脑断开时显示 App 内的连接失败页面。
- 给手表端提供 `8790` 中继服务。

## 5. 手表端安装和使用

手表源码目录：

```text
watch-wearable-app
```

安装前必须先配置：

```text
watch-wearable-app/entry/src/main/ets/common/BridgeConfig.ets
```

推荐通过手机中继连接：

```ts
export const BRIDGE_BASE_URL: string = 'http://手机IP:8790';
export const BRIDGE_TOKEN: string = '你的电脑端token';
```

也可以让手表直连电脑：

```ts
export const BRIDGE_BASE_URL: string = 'http://电脑IP:8788';
export const BRIDGE_TOKEN: string = '你的电脑端token';
```

然后在 DevEco Studio 中：

1. 打开 `watch-wearable-app`。
2. 选择 Wearable 设备或 Wearable 模拟器。
3. 配置自己的调试签名。
4. 构建 HAP。
5. 使用 DevEco Assistant 或 DevEco Studio 安装到支持 `wearable` 的设备。

注意：`release/watch-wearable-unsigned.hap` 是未签名公开包，不能直接安装到真机。真机安装需要你自己的签名 profile。

## 6. 日常使用流程

1. 电脑启动 `desktop-bridge`。
2. 手机 App 连接电脑 Bridge。
3. 手机保持后台提醒开启。
4. 手表 App 连接手机中继或电脑 Bridge。
5. Codex 发起权限审批时，手机和网页会显示同一条审批。
6. 任意一端批准或拒绝后，其他端会刷新到历史记录，不再显示为待审批。

## 7. 常见问题

### 手机提示连接失败

一般是电脑 Bridge 没启动、电脑 IP 变了、手机不在同一局域网，或 Windows 防火墙拦截。

### 手机能审批，手表不能审批

先确认手表端 `BridgeConfig.ets` 的地址和 token 正确。手表直连电脑不稳定时，优先改用手机中继地址 `http://手机IP:8790`。

### HAP 安装失败

公开包是未签名包，真机安装需要 DevEco Studio 中的调试签名。不同手表设备类型也必须匹配：本仓库手表工程是 `wearable`，不是 `liteWearable`。

### 能不能离家使用

当前版本是局域网方案。离家使用需要后续增加安全的公网服务器或 HTTPS 隧道，并加入身份认证和访问限制。
