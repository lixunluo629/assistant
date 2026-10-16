# 安全边界与威胁模型

## 三条不可妥协的红线

1. **不代扫、不代确认。** 助手不接触、不转发、不伪造二维码登录票据。用户必须在官方 App 内扫码并手动点击「确认登录」。
2. **不联网传输二维码内容。** 解析全程本地完成；二维码原文在解析函数返回后即被丢弃。
3. **跳转目标只能是白名单常量。** `deep_link` 一律取自 `platform_rules.json` 的固定值，禁止任何形式的字符串拼接。

## 数据生命周期

| 数据 | 位置 | 生命周期 |
|---|---|---|
| 二维码原文 | `QrParser.parse()` 局部变量 | 函数返回即失效；不写入日志、不埋点、不落盘 |
| `ticket` / `uuid` / `scene` 等凭据值 | **从不读取** | — |
| `has_credential` 布尔标记 | `LoginTarget` | 仅表示"这个码带票据"，无法还原内容 |
| 原文指纹（加盐 SHA-256 前 12 位） | `LoginTarget.fingerprint` | 可入日志，不可逆，用于关联排障 |
| 平台枚举、安装状态 | `Decision` | 无隐私含义 |
| 相机帧 | ML Kit 解码缓冲 | 即时丢弃，不缓存、不上传 |

## 威胁清单与对策

### T1 钓鱼二维码：把官方二维码换成伪造链接
二维码里放 `https://evil.example.com/?next=wx.qq.com&ticket=x`。

**对策**：host 做最长后缀白名单匹配，且 **URL 存在但不在白名单时直接拒绝**，绝不用"文本里出现了 `wx.qq.com`"来放行。
对应测试：`test_unknown_domain_rejected`、`test_lookalike_domain_rejected`。

### T2 视觉诱骗域名：`weixin.qq.com.evil.example`
用户扫一眼以为是真的。

**对策**：后缀匹配要求 `host == domain` 或 `host.endswith("." + domain)`，`weixin.qq.com.evil.example` 不满足；同时 NFKC + 去零宽字符，防止不可见字符藏在域名里骗过匹配。

### T3 scheme 注入：二维码里直接塞 `weixin://dl/scan?url=...`
部分平台的私有 scheme 可以做任意跳转。

**对策**：非 `http(s)` 开头的内容一律 `reject`；助手的唤起链接只来自规则表常量，不解析二维码里的 scheme。
对应测试：`test_custom_scheme_rejected`、`test_deep_link_never_contains_qr_content`。

### T4 开放重定向拼接
如果实现成 `deep_link + "?url=" + 二维码内容`，就等于把跳转目标交给二维码作者。

**对策**：`decide_for_target()` 里 `deep_link=rule.deep_link`，没有任何 URL 拼接；`Decision` 上带 `is_safe_to_launch` 作为显式契约，并用回归测试锁死。

### T5 误唤起错误 App（体验与安全双重问题）
`qq.com` 是短后缀，会命中 `login.weixin.qq.com`；`v.qq.com`（腾讯视频）也会命中 `qq.com`。

**对策**：最长后缀优先 + `host_denylist` 一票否决。测试：`test_wechat_beats_qq_by_longest_suffix`、`test_denylist_blocks_tencent_video`。

### T6 宽松匹配被滥用
只靠中文"微信"两字就判平台，会把普通文案当登录码。

**对策**：宽松匹配仅在**完全没有 URL** 时启用，**要求登录语境词**，结果标记 `loose=true`，路由层**禁止自动跳转**，只做提示。
测试：`test_loose_match_never_autolaunches_even_if_installed`。

### T7 安装检测误判为"未安装"
Android 11+ 包可见性限制下，`getPackageInfo` 会抛 `NameNotFoundException`，可能被误读成"没装"。

**对策**：规则表中的包名必须同步写进 `AndroidManifest.xml` 的 `<queries>`；检测异常一律返回 `UNKNOWN`，路由降级为"手动打开"。

### T8 日志泄漏票据
排障时顺手 `Log.d(raw)`。

**对策**：`LoginTarget` 根本不持有原文与凭据；日志用 `LoginTarget.log_line()`，只有平台、命中域名、布尔标记和指纹。

## 系统级保证：没有网络权限

"我们承诺不上传"不够硬，本项目用系统能力把它变成事实：

- APK 最终声明的权限只有 `CAMERA`（外加 androidx 的内部权限）；
- ML Kit 依赖库会在自己的 manifest 里声明 `INTERNET` 与 `ACCESS_NETWORK_STATE`，merger 会合并进来，
  所以 `AndroidManifest.xml` 里显式移除：

  ```xml
  <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
  ```

- 解码模型用 bundled 版（随 APK 分发，4 MB 左右），不依赖首次联网下载模型；
- 跳应用商店用 `market://`，由商店 App 自己联网，本 App 不需要网络能力。

复核方式（升级依赖后必须重跑）：

```bash
aapt2 dump permissions dist/.../app-release.apk
python3 tools/check_consistency.py   # 已把这条纳入自动检查
```

## 明确不做的事（写在这里以免后续被"顺手加上"）

- ❌ 不做无障碍服务 / 自动点击 / 模拟输入，不接管其他 App 的界面
- ❌ 不读取其他 App 的数据、不做 root/越狱检测绕过
- ❌ 不提供"自动确认登录""批量登录""代别人登录"类功能
- ❌ 不采集账号、手机号、通讯录等任何个人数据
- ❌ 不为了让流程"更顺"而放宽白名单、跳过用户确认

## 上线前检查清单

- [ ] `platform_rules.json` 的 `deep_link` 字段全部是常量，无模板变量
- [ ] 新平台的包名已同步进 Android `<queries>` 与 iOS `LSApplicationQueriesSchemes`
- [ ] 所有 `reject` / `manual_scan` 分支的文案都明确提示"警惕钓鱼"
- [ ] `aapt2 dump permissions` 确认最终 APK 里没有 `INTERNET` / `ACCESS_NETWORK_STATE`
- [ ] 抓包确认：App 在扫码 → 决策 → 跳转全过程中无任何外发请求（无网络权限时天然成立）
- [ ] 反编译/静态检查确认日志与埋点里不含二维码原文
