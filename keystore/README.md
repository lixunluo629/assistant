# 签名密钥

| 文件 | 说明 |
|---|---|
| `assistant.jks` | 小助手的 release 签名密钥（别名 `qrlogin`，RSA 2048，有效期 9125 天） |
| `../android/keystore.properties` | Gradle 读取的配置：密钥路径 + 密码 |

`android/app/build.gradle.kts` 的 `signingConfigs` 会读 `keystore.properties`；
**该文件不存在时 release 包回退用 debug 签名**，所以删掉配置也能构建，只是出的包不能覆盖安装。

签名证书指纹（SHA-256）：

```
d7b1840d2f51be396a76d3d71c2354f2672b4aca003d117bf8f54b27561bf5bf
```

覆盖安装新版本时，签名必须与设备上已装的一致 —— 换了密钥就得先卸载旧版。

---

## ⚠️ 请注意

这份密钥**随项目一起分发**，方便拿到源码的人直接出包。代价是：

- **密钥泄露 = 任何人都能签出「看起来是你发的」更新包**。请勿把本目录提交到公开仓库（`.gitignore` 已默认忽略 `*.jks`）。
- 如果这个 App 之后要**对外发布 / 上架 / 给客户演示**，请换成你自己的密钥：

```bash
# 1) 生成新密钥
keytool -genkeypair -v -keystore ~/keystores/assistant-release.jks -alias assistant \
  -keyalg RSA -keysize 2048 -validity 9125

# 2) 改 android/keystore.properties 指向它
# 3) 重新出包
cd android && ./gradlew :app:assembleRelease
```

> 另外提醒：币面图案上的「广州地铁 / Guangzhou Metro」与羊角标志是广州地铁的注册商标，
> 对外发布前请一并替换（见 `../tools/coin-make.py`，用 `--logo 你的图.png` 可换标志）。
