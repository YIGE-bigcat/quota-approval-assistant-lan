# 安全说明

## 不要上传的内容

- `config.local.json`
- Dashboard token
- ntfy topic
- `.p12`、`.p7b`、`.cer`、`.jks`、`.keystore`
- 华为开发者调试证书和 profile
- 本机日志、审批历史、运行数据库

## 网络边界

当前版本按局域网设计。默认不建议把电脑端口暴露到公网。

如果后续要支持离家审批，建议改为：

- 使用 HTTPS。
- 使用独立服务器或安全隧道。
- 增加强认证和 token 轮换。
- 对审批 action 做单次使用和审计日志。

## 公开发布前检查

可以运行：

```powershell
rg -n "20492c|hapDebug|\\.p12|\\.p7b|storePassword|keyPassword|password" .
```

如果命中真实 token 或签名材料，不要发布。
