# 开发说明

## 目录

```text
desktop-bridge/       Node.js bridge and web dashboard
android-app/          Android phone app
watch-wearable-app/   HarmonyOS wearable watch app
release/              Built packages and source zips
```

## 电脑端校验

```powershell
cd desktop-bridge
npm.cmd test
node --check src/server.mjs
node --check public/app.js
node --check hooks/permission-request.mjs
```

## Android 构建

需要 Android SDK、Gradle、JDK。

```powershell
cd android-app
gradle assembleDebug
```

输出：

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

## Wearable HAP 构建

需要 DevEco Studio、HarmonyOS SDK、Wearable SDK、hvigor。

```powershell
cd watch-wearable-app
hvigorw assembleHap --no-daemon
```

输出：

```text
watch-wearable-app/entry/build/default/outputs/default/entry-default-unsigned.hap
```

真机安装前需要配置签名。不要把 `.p12`、`.p7b`、`.cer`、调试 profile 或私有 token 提交到仓库。
