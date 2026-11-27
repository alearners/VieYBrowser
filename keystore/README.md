# 固化签名（固定 release keystore）

`viey-release.jks` 是本仓库唯一的 release 签名，从 0.11.2-dev 起固定使用，不再重新生成。

- 类型：PKCS12
- alias：`viey`
- storepass / keypass：`viey-release`（可用环境变量 `VIEY_STORE_PASS` / `VIEY_KEY_PASS` / `VIEY_KEY_ALIAS` 覆盖）
- 密钥：RSA 2048，SHA256withRSA
- 有效期：2026-11-27 → 2056-11-19（10950 天）
- 证书 SHA-256：`F5:11:D2:E9:42:01:ED:04:EF:EB:59:E5:CF:51:E3:65:0B:4A:77:4E:0D:88:FA:A6:97:C3:7F:BE:8D:05:DE:37`

校验收包签名：

```bash
apksigner verify --print-certs build/outputs/apk/release/VieY-release.apk | grep SHA-256
```

**注意**：0.11.1-dev 及更早的包用的是旧证书（`ad:7a:e6:58…`），与本签名不兼容，覆盖安装会失败——升级前需先卸载旧版（会清空本地数据，可先用「设置 → 订阅 → 导出订阅备份」留档）。
