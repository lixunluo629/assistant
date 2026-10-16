# 平台技术边界说明

写这个文档是因为评审时必被问到："既然手机已经扫到了二维码，为什么还要用户再扫一次？"

## 结论先说

**PC 端登录二维码的票据，只能由官方客户端扫描后上报给官方服务器。**

第三方 App 即使读到了二维码内容，也无法把它换成"已登录"，因为：

1. 二维码里通常是一次性随机票据（`uuid` / `ticket` / `scene`），只能由官方服务端校验并绑定"扫码者身份"；
2. 校验时必须带上官方客户端的登录态（会话密钥 / 设备指纹 / 签名）；
3. 服务端在收到"扫码人已确认"的回执后才把 PC 会话置为已登录。

第三方想跳过这一步，只有两条路：伪造官方客户端（违法，且必然触发风控）、或接管官方 App 界面自动点击（即自动化授权，明确不做）。

## 那助手到底省了什么

| 原来的步骤 | 助手之后 |
|---|---|
| 看到二维码 → 人眼判断是哪个平台 | 助手解析域名，直接告诉你"这是微信的码" |
| 回桌面/抽屉里找对应 App 图标 | 助手直接唤起对应 App |
| 记不住没装哪个 App、去哪下载 | 助手给官方商店链接，并提示可改用账号密码 |
| 担心扫到钓鱼码 | 助手拒绝非白名单域名，并提示风险 |

**"对准屏幕扫、在 App 内点确认"这两步不变**——这正是它的安全价值所在。

## 各平台细节

| 平台 | 登录二维码域名 | 启动方式（首选 → 兜底） | 应用包名 | 密码登录兜底 |
|---|---|---|---|---|
| 微信 | `login.weixin.qq.com`、`wx.qq.com` | 包名启动 → `weixin://` → 商店 | `com.tencent.mm` | 无（仅支持扫码） |
| 钉钉 | `login.dingtalk.com`、`oapi.dingtalk.com` | 包名启动 → `dingtalk://` → 商店 | `com.alibaba.android.rimet` | `https://login.dingtalk.com/` |
| QQ | `xui.ptlogin2.qq.com`、`qun.qq.com`、`w.qq.com` | 包名启动 → `mqq://` → 商店 | `com.tencent.mobileqq` | `https://xui.ptlogin2.qq.com/` |
| 支付宝 | `qr.alipay.com`、`auth.alipay.com`、`login.alipay.com` | 扫一扫深链 → 包名启动 → `alipays://` → 商店 | `com.eg.android.AlipayGphone` | `https://auth.alipay.com/login/index.htm` |

### 启动 App 与「直达扫一扫」：实测结论

真机验证踩过的两个坑，决定了现在的启动顺序：

1. 裸 scheme `dingtalk://` 钉钉不注册（它所有 filter 都带 host），直接 `startActivity` 抛
   `ActivityNotFoundException`，用户看到「找不到对应的 App」——而安装检测明明说它装了；
2. 只看 manifest 就断言"微信、QQ 没有扫一扫入口"，结果**错得很离谱**：它们的深链统一进一个
   `exported=true` 的路由 Activity，真正的功能入口藏在 dex 的路由字符串里。

因此把四个官方 APK 的 manifest **和 dex 路由串**都翻了一遍，并且反编译了微信的 scheme 分发入口：

| 平台 | 直达扫一扫 | 入口 | 依据 |
|---|---|---|---|
| 微信 | ❌ **官方不开放** | — | 微信唯一对外的 `weixin://` 入口是 `com.tencent.mm.plugin.base.stub.WXCustomSchemeEntryActivity`（`exported=true`）。反编译后可见它的 `onCreate` **只对 6 类 host 分发**：`cardpackage` / `connectToFreeWifi` / `wap` / `pay` / `yuanbao` / `wxpay`，兜底分支会打印 `host[%s] hit but no inner branch matched`。`weixin://dl/scan` 实际只存在于微信**内部 webview 的 URL 权限位映射表**（`Map<String, Long>`，scan→64），是内部路由，第三方调不动 |
| 钉钉 | ✅ | `dingtalk://dingtalkclient/page/scan` | manifest 里 `exported=true` 的 `com.alibaba.android.rimet.biz.BokuiActivity` 注册了 host=`dingtalkclient`/`qr.dingtalk.com` + path=`/page/scan`；真正的界面 `com.taobao.ma.ui.ScanActivity` 是 `exported=false`，只能由钉钉自己拉起 |
| QQ | ✅ | `mqqapi://qrscan/open?source=1` | 外部深链统一进 `com.tencent.mobileqq.activity.JumpActivity`（`exported=true`，`mqqapi` 不带 host）；dex 中 `mqqapi://qrscan` 与 `com.tencent.mobileqq.qrscan.*` 模块同 dex |
| 支付宝 | ✅ | `alipays://platformapi/startapp?appId=10000007` | `com.alipay.mobile.quinox.SchemeLauncherActivity`（`exported=true`，`alipays` 不带 host）；appId 的三条依据见下 |

**为什么系统扫一扫（魅族 Flyme 等）能直达微信，我们不行**：

Flyme 的扫一扫是系统应用，持有 `START_ANY_ACTIVITY`（`signature|privileged`）权限，可以启动微信
`exported=false` 的扫一扫界面（`com.tencent.mm.plugin.scanner.ui.BaseScanUI`）。普通第三方 App
调 `startActivity` 会被系统直接拦下抛 `SecurityException`。

也就是说：**这不是"我们没找到入口"，而是"微信根本没有对外开放这个入口"**。即便拿到系统签名，
那也属于"接管其他 App 的界面"，按本项目的红线同样不做。

微信场景下的最优体验因此是：自动打开微信 → 用户点「首页右上角 + → 扫一扫」→ 扫 PC 屏幕 → 确认登录。
只剩这一步，且必须由用户完成。

| 哔哩哔哩 | ✅ | `bilibili://qrscan` | manifest 里 `exported=true` 的 `tv.danmaku.bili.ui.intent.IntentHandlerActivity` 显式注册了 host=`qrscan`（同一 activity 的 host=`qrcode` 是展示自己的二维码，未采用） |
| 百度 | ✅ | `baiduboxapp://v16/ucenter/scan` | dex 中可见该路由；另有 4 个 `exported=true` 的扫码 Activity（`com.baidu.searchbox.barcode.entry.CodeScannerActivity` 等）**没有 scheme filter**，因此额外用显式类名兜底 |
| 小红书 | ✅ | `xhsdiscover://qr_scan` | 对外路由 `com.xingin.xhs.routers.RouterPageActivity`（`exported=true`）注册了大量 `xhsdiscover://` 子路由，`qr_scan` 属同一套体系 |
| 抖音 | ✅ | `snssdk1128://aweme/scan` | dex 中可见该路由；对外入口 `TransDeepLinkHandlerActivity` / `DeepLinkHandlerActivity` 均为 `exported=true`。只取不带参数的版本 |
| 企业微信 | ❌ | — | 对外入口只有 `AppSchemeLaunchActivity`（`wxwork`/`wework`/`wxworkpaid`/`wxworksaas`/`native`，host 为空）与 `WWAPIActivity`（`wxworkapi`）；`wxwork://` 下没有任何 scan/qr 路由，导出组件里也没有扫码 Activity |
| 夸克 | ❌ | — | `quark://` 下只有 `alipay`、`camera_auto_test` 两个路由；`uclink://`/`qklink://` 只用于打开网页/小程序 |
| Steam | ❌ | — | 国内应用商店没有上架该包，本轮拿不到官方 APK，**未做验证**；只提供识别 + 打开 App，包名与 `steam://` scheme 来自公开资料 |

**域名互相包含的坑**：企业微信的 `work.weixin.qq.com` 是微信 `weixin.qq.com` 的子域，
如果匹配顺序不对，企业微信的码会被判成微信。规则表的实现是**按域名长度降序匹配**，
`work.weixin.qq.com`（20 字符）先于 `weixin.qq.com`（13 字符）命中。已加回归测试。

**教训：只解析 manifest 是不够的。** 微信、QQ、支付宝都是"一个对外路由 Activity + 内部路由表"的结构：
manifest 里只有那个路由 Activity，具体功能入口（扫一扫、付款码、我的二维码……）全在 dex 的字符串里。
判断某个 App 有没有某某入口，必须 manifest 与 dex 一起看。钉钉是例外 —— 它把几百条 host/path 直接写在了 manifest 里。

**`appId=10000007` 就是支付宝扫一扫**，三条独立线索互相印证：

```
"scheme":"alipays://platformapi/startapp?appId=10000007&source=nougat_shortcut&sourceId=nougat_shortcut_scan"
"placeholder":"local://widget_pay_scan"        ← 桌面小组件的扫一扫按钮
ch_appid-10000007 ≡ ch_scan                    ← 场景码映射里两者落在同一个值
```

**为什么裸 scheme 在不同 App 上表现不同**：钉钉所有 `<data>` 都带 host，空 host 匹配不到任何组件；
微信/QQ/支付宝的对外路由 Activity 都不限制 host，所以裸 scheme 能打开（但只会进主界面）。

最终启动顺序（`MainActivity.launchOfficialApp`）：

```kotlin
1. decision.scanDeepLinks        // 扫一扫深链，按候选依次尝试
2. decision.scanActivityClasses  // 显式 Activity 类名（百度这类没有 scheme 的情况）
3. getLaunchIntentForPackage()   // 按包名启动主界面（与安装检测同源，最可靠）
4. deepLink（裸 scheme）          // 尽力而为
5. market:// 商店 / 网页登录入口    // 逐级兜底
6. 都失败 → 提示用户手动打开 App
```

任何一环失效都会自动降级，用户不会卡在报错上。规则表里每条深链都记录了 `verified_against`
（验证时依赖的 App 版本），App 改版导致失效时便于排查。

### 扫码后直接跳转（不再要求点按钮）

`Decision.auto_launch` 决定 UI 是否还需要用户点一下：

| 动作 | auto_launch | 理由 |
|---|---|---|
| `OPEN_APP` | ✅ 自动 | 用户主动扫这个码，本身就是明确意图；再要求点按钮纯属多余 |
| `PROMPT_DOWNLOAD` | ✅ 自动 | 未安装时下一步就是去商店，同样有确定目标 |
| `MANUAL_SCAN` | ❌ 手动 | 结果不确定（可能是钓鱼码），必须让用户先看清提示 |
| `REJECT` | ❌ 手动 | 安全警告绝不能被"自动跳走"冲掉 |
| `PASSWORD_LOGIN` | ❌ 手动 | 这是用户在界面上主动选择的动作 |

UI 侧（`MainActivity.onDecoded`）在渲染结果卡片后延迟 350ms 执行 `performPrimaryAction()`，
让用户知道刚才发生了什么；从官方 App 返回时 `onResume` 自动恢复扫描。

**一个容易踩的坑**：恢复扫描时**不清空** `lastHandledFingerprint`。否则手机还对着同一张码时
会立刻再次触发自动跳转，形成"一回来就被跳走"的死循环。要重扫同一张码，点「重新扫描」按钮重置指纹。

### 直达扫一扫 ≠ 代扫：三条刻意不走的近路

翻 dex 时发现，几个平台都提供"把二维码内容交给客户端直接处理"的路由：

| 平台 | 代扫路由（一律不使用） | 后果 |
|---|---|---|
| 微信 | `weixin://dl/scanWxaQrcode?qrcodeText=`、`weixin://dl/handleScanResult?appid=&result=` | 把二维码文本/扫码结果直接交给微信处理 |
| 钉钉 | `qr.dingtalk.com/web_login.html`（`WebLoginActivity`，`exported=false`） | 拼上票据即可让钉钉代用户确认登录 |
| 支付宝 | `alipays://platformapi/startapp?appId=10000007&qrcode=` | 传参即等于代扫 |

一旦使用，就等于绕过"用户必须在官方 App 内亲自扫码并确认"这一步 —— 正是本项目一开始划定的红线。因此：

- 规则表里所有扫一扫深链都是写死的常量，**代码里禁止任何拼接**；
- `tools/check_consistency.py` 维护了一份「代扫参数」黑名单
  （`qrcodetext` / `qrcode=` / `handlescanresult` / `scanresult` / `result=` / `data=` / `url=` / `content=` / `ticket=` / `code=`），
  扫一扫深链里出现任何一个就直接报错；
- 一致性检查还要求扫一扫深链的 scheme 必须落在该平台的合法 scheme 集合里
  （`deep_link` 的 scheme 加 `extra_schemes`，例如 QQ 的 `mqqapi`），防止跳错 App；
- `core/tests/test_router.py` 与 Android 单测各有回归用例，逐条检查四平台深链不含上述参数。

### 其它注意点

- **腾讯系域名同源**：`login.weixin.qq.com` 是 `qq.com` 的子域，必须最长后缀优先，否则微信码会被判成 QQ 码；
- **腾讯非登录站要排除**：`v.qq.com`（视频）、`mail.qq.com`（邮箱）、`y.qq.com`（音乐）等放进 `host_denylist`，避免误唤起；
- **不保证直达「扫一扫」页**：期望管理写在 UI 文案里，由用户点一下 App 内的扫一扫。若某平台官方文档提供了带参数的扫码入口，再单独在规则表里新增字段，不要靠猜；
- **包名变更风险**：应用改名/换包名（如钉钉国际版）时，规则表与 `AndroidManifest` 的 `<queries>` 要一起改，否则安装检测会误判为未安装。

## 若后续要支持更多平台

1. 抓取该平台 PC 登录页的二维码，确认其域名归属（注意 CDN/短链跳转后的真实域名）；
2. 在 `platform_rules.json` 里加一条规则，按需填 `host_denylist`；
3. 在桌面端/Android 端各加一条探针能力（包名、scheme）；
4. 用 `core/tests/test_parser.py` 的风格补测试，至少覆盖：正确识别、同源子域冲突、denylist、伪造域名。
