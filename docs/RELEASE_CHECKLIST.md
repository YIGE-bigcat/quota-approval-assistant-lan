# 发布检查记录

## 构建产物

- `release/phone-android-debug.apk`
- `release/watch-wearable-unsigned.hap`
- `release/desktop-bridge-source.zip`
- `release/android-app-source.zip`
- `release/watch-wearable-app-source.zip`

## 验证

```powershell
cd desktop-bridge
npm.cmd test
node --check src/server.mjs
node --check public/app.js
node --check hooks/permission-request.mjs
```

结果：

- Node 测试：20 passed。
- 桌面端语法检查：通过。
- Android APK：`apksigner verify` 通过，包名 `com.codexbridge.approval`，版本 `2.7.0`。
- Wearable HAP：DevEco hvigor `assembleHap` 构建成功，当前为未签名包。

## 隐私检查

- 手机 APK 未包含本机私有 token。
- 手表 HAP 未包含本机私有 token。
- 公开源码中使用 `CHANGE_ME` 或示例局域网 IP。
- 签名证书、profile、运行数据和本机配置已排除。

说明：`docs/SECURITY.md` 中会出现 `.p12`、`.p7b`、`password` 等关键词，这是安全提醒文本，不是实际证书或密码。
