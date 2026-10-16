# 小助手（Assistant）

一个多功能工具集 App：**目前只包含一个功能 —— 扫码登录助手**，后续功能陆续加进功能菜单。

```
┌─ 小助手（首页 · 功能菜单）──────────┐
│  扫码登录助手                    ›  │  ← 扫 PC 登录二维码，自动打开对应 App
│  （更多功能陆续添加）                │
└─────────────────────────────────────┘
```

## 功能列表

| 功能 | 说明 | 状态 |
|---|---|---|
| **扫码登录助手** | 扫 PC 登录页二维码 → 识别 **11 个平台** → **自动**打开对应官方 App（其中 7 个可直接进扫一扫）→ 用户在官方 App 内手动确认登录 | ✅ 可用 |
| **随机事件** | 掷骰子 / 抛硬币 / 抽数字，顶部标签切换；硬币是离线渲染的银色浮雕贴图（一面字、一面标志） | ✅ 可用 |

## 随机事件包含什么

首页 →「随机事件」，三个工具用顶部胶囊标签切换，界面参考系统随机工具的形态：
**中间就是大号的骰子 / 硬币，点它一下就摇/抛**。

| 工具 | 交互 |
|---|---|
| **掷骰子** | 点骰子摇；右下角圆形按钮展开选 1~6 颗（固定 6 面，**骰子尺寸固定**，1 颗不会变大、6 颗不会变小） |
| **抛硬币** | 点硬币抛；**一面是字、一面是标志**（银色浮雕贴图），上抛 + 翻转动画，累计正反面次数 |
| **抽数字** | 指定范围与个数，可切换"不重复" |

### 视觉与动效

骰子和硬币是自绘 View（`DiceView` / `CoinView`），不是图片：

- **骰子**：白色圆角方块 + 红色点数 + 落地阴影。摇动时整体抖动、快速闪点，最后 35% 的时间收敛到最终点数，落地带轻微回弹；
- **硬币**：币面是**离线渲染好的贴图**（`res/drawable-nodpi/coin_heads.png` / `coin_tails.png`），
  由 [`tools/coin-make.py`](tools/coin-make.py) 生成：先在灰度图上画高度图（外圈刻线、印章、环形文字、中央主字/标志），
  再走单光源浮雕渲染成银色 —— Canvas 手绘达不到这种金属质感，所以贴图 + 动画分工；
  翻转时**沿弧线上抛再落下**（投影随高度变小变淡），用 `|cos(角度)|` 做横向压缩模拟 3D，
  落到半圈之间时自动换面 —— 视觉与结果始终一致；
- 结果文字采用淡入 + 轻微上移，比直接跳字舒服。

### 三个实现取舍

1. **随机源用 `SecureRandom`，不是 `java.util.Random`**。Random 是线性同余伪随机、种子可预测，
   在"决定谁请客""抽签""抽奖"这类涉及利益的场景里，可预测就等于可以被操纵。
2. **公平性与展示彻底分离**：点数 / 正反面 / 抽取结果全部由 `RandomOps` 生成，动画里用的随机数
   只是视觉抖动、不参与结果。因此随机算法有 25 个单元测试守着。
3. **骰子尺寸固定而非自适应铺满**：早先的版本按可用空间铺满，结果是"摇 1 颗时骰子巨大、摇 6 颗时缩成小点"，
   视觉重心一直在跳。现在固定 74dp，只在窄屏放不下 3 列时才等比缩小。

> 算法层保留了 `pickOne`（抽签/点名会用到）和 `generatePassword`，只是暂时没有界面入口。

### 还能加什么（按建议优先级）

| 建议 | 场景 | 成本 |
|---|---|---|
| **抽签 / 点名** | 从名单抽 1 个：会议点名、谁请客（复用 `RandomOps.pickOne`） | 低 |
| **随机分组** | 名单随机分 N 组：团建、分工 | 低 |
| **随机颜色** | 生成色值 + 色块，可复制：设计 / 开发 | 低 |
| **洗牌 / 随机排序** | 打乱列表：抽奖顺序、发言顺序（`RandomOps.shuffled` 已写好） | 低 |
| 摇一摇触发 | 更接近系统工具的体感（阈值需真机调） | 中 |
| 转盘（带动画） | 视觉反馈更好，适合投屏抽奖 | 中 |
| 结果历史 / 去重抽奖 | 年会抽奖不重复中奖 | 中 |

## 支持的平台

| 平台 | 识别 | 直达扫一扫 | 入口 |
|---|---|---|---|
| 钉钉 | ✅ | ✅ | `dingtalk://dingtalkclient/page/scan` |
| QQ | ✅ | ✅ | `mqqapi://qrscan/open?source=1` |
| 支付宝 | ✅ | ✅ | `alipays://platformapi/startapp?appId=10000007` |
| 哔哩哔哩 | ✅ | ✅ | `bilibili://qrscan` |
| 百度 | ✅ | ✅ | `baiduboxapp://v16/ucenter/scan`（另有导出的 `CodeScannerActivity` 类名兜底） |
| 小红书 | ✅ | ✅ | `xhsdiscover://qr_scan` |
| 抖音 | ✅ | ✅ | `snssdk1128://aweme/scan` |
| 微信 | ✅ | ❌ | 官方不开放，自动打开主界面 |
| 企业微信 | ✅ | ❌ | 未开放扫一扫，自动打开主界面 |
| 夸克 | ✅ | ❌ | 未开放扫一扫，自动打开主界面 |
| Steam | ✅ | ❌ | 未验证（国内商店无此包），自动打开 App |

## 怎么新增一个功能

两步，**不用改首页**（首页菜单由功能注册表驱动）：

```kotlin
// 1) 在 android/app/src/main/java/com/szpdc/assistant/feature/<功能名>/ 下写自己的 Activity
// 2) 在 feature/ToolRegistry.kt 的 tools 列表里加一行
Tool(id = "todo", titleRes = R.string.tool_todo_title,
     descriptionRes = R.string.tool_todo_desc, entry = TodoActivity::class.java),
// 3) 在 AndroidManifest.xml 注册该 Activity —— 漏了会被 tools/check_consistency.py 直接拦下
```

约定：一个功能一个 `feature/xxx/` 子包，功能之间不引用彼此的内部实现，公共代码往 `common/` 放。

---

# 功能一：扫码登录助手

手机端助手 App：**扫描 PC 登录页上的二维码 → 识别平台 → 自动打开对应官方 App（可用时直接进扫一扫）→ 用户在官方 App 内手动确认登录**。

扫码后**不需要再点按钮**，识别完就自动跳转。

它替代的是"先找图标、再点扫一扫、再对准屏幕"这几步，**不替代**登录确认本身。

---

## 这个助手做什么 / 不做什么

| 做 | 不做 |
|---|---|
| 解析二维码，识别是微信 / 钉钉 / QQ / 支付宝的登录码 | 不代扫、不代上报二维码票据 |
| 检测本机是否装了对应 App，装了就直接唤起 | 不注入、不拼接、不转发二维码内容 |
| 没装就引导去应用商店，并给出账号密码登录兜底 | 不读取、不缓存、不上传 `ticket` / `uuid` |
| 识别不出或内容可疑时拒绝并提示手动处理 | 不联网、不模拟点击、不碰账号登录态 |

技术边界的原因见 [`docs/protocol-notes.md`](docs/protocol-notes.md)：网页扫码登录的一次性票据只能由官方客户端扫描后上报，第三方 App 无法（也不应）代为完成。所以助手把"人找 App、再找扫一扫"变成"扫码后直接落进扫一扫界面"，把"确认"完整留给用户。

## 扫码后的决策表

| 识别结果 | 本机安装情况 | 动作 | 用户接下来做什么 |
|---|---|---|---|
| 7 个平台（钉钉 / QQ / 支付宝 / B站 / 百度 / 小红书 / 抖音） | 已安装 | `open_app` | **自动进入扫一扫** → 扫 PC 屏幕 → 在 App 内确认登录 |
| 微信 / 企业微信 / 夸克 / Steam | 已安装 | `open_app` | 自动打开 App → 自己点「扫一扫」→ 确认登录 |
| 四个平台 | 未安装 | `prompt_download` | 跳应用商店下载官方 App；钉钉 / QQ / 支付宝还可改用账号密码登录（微信无） |
| 四个平台 | 无法确认（系统限制） | `manual_scan` | 提示手动打开 App，不自动跳转 |
| 只有文字线索，没有链接 | — | `manual_scan` | 只提示"可能是某平台"，不跳转 |
| 域名不在白名单 / 自定义 scheme | — | `reject` | 拒绝处理，提示警惕钓鱼 |

## 目录结构

```
assistant/
├── core/                    # 平台无关核心库（Python，零第三方依赖）
│   ├── platform_rules.json  # ◀ 平台识别规则表，Python 与 Android 共用同一份
│   ├── qrlogin/
│   │   ├── models.py        # Platform / InstallStatus / Action / Decision
│   │   ├── rules.py         # 规则表加载 + 最长后缀域名匹配 + denylist
│   │   ├── parser.py        # 二维码内容解析（不联网、不留原文）
│   │   ├── installer.py     # 本机 App 安装检测适配器（桌面 / Android）
│   │   ├── router.py        # 决策引擎：识别结果 + 安装状态 → Decision
│   │   └── cli.py           # 命令行演示
│   ├── tests/               # 36 个单元测试（unittest，标准库）
│   └── demo/run_demo.py     # 场景化演示，输出"用户看到的样子"
├── android/                 # 小助手 App（Kotlin）；扫码功能在 feature/scan/
├── pc/login-qr.html         # PC 端登录二维码页面（演示 + 调试用）
├── tools/check_consistency.py  # 跨端一致性检查（规则表 ↔ 清单 ↔ 枚举）
├── docs/                    # 架构 / 安全 / 平台技术边界
└── run_tests.sh             # 一键跑单元测试 + 一致性检查
```

Python 核心库是**决策逻辑的参考实现**：规则表、解析策略、决策表两端一致，Android 端可以直接读同一份 `platform_rules.json`，改规则不用改代码、也不用两端同步。

## 快速开始

```bash
# 一键检查：36 个单元测试 + 跨端一致性检查（无需安装任何依赖）
./run_tests.sh

# 看一遍完整决策链路
cd core
PYTHONPATH=. python3 -m qrlogin demo

# 单条二维码内容
PYTHONPATH=. python3 -m qrlogin scan "https://login.weixin.qq.com/qrcode/abc?uuid=xxx" --installed wechat

# 查看规则表与本机 App 探测结果
PYTHONPATH=. python3 -m qrlogin status

# PC 演示页（浏览器打开 pc/login-qr.html 即可，或用 http.server）
python3 -m http.server 8080 --directory pc
```

## 工具怎么防止事故

`tools/check_consistency.py` 把几条“只能靠人记得”的约定变成了硬检查：

| 检查 | 防的是什么事故 |
|---|---|
| 规则表的每个 `android_packages` 都在 `<queries>` 里 | 漏声明 → Android 11+ 把“已安装”误判成“未安装” |
| 每个 `deep_link` 的 scheme 都在 `<queries>` 里 | 漏声明 → 唤起按钮按了没反应 |
| `deep_link` 必须是纯 scheme、不含 `{`/`%s`/`$` | 有人为了“直达扫一扫”改成拼接，变成开放重定向 |
| Python 与 Kotlin 枚举逐项一致 | 两端行为错位（PC 侧 reject、手机侧照跳） |
| denylist 不命中任何平台的登录域名 | 把某平台的登录码反手拉黑了 |
| Kotlin 关键安全分支仍在 | 重构时把 scheme 闸门 / loose 闸门删掉 |

该脚本已做负向验证：删掉一个包名声明、把 `deep_link` 改成拼接，两次都能被拦下。

Android APK 已在本机构建通过：`dist/assistant-1.6.0-release.apk`（12 MB，已签名）、`dist/assistant-1.6.0-debug.apk`（16 MB）。安装与重新构建见 [`android/README.md`](android/README.md)。

## 规则表怎么改

`core/platform_rules.json` 是唯一事实来源：

- 新增平台：加一个对象，填 `domains` / `url_keywords` / `text_aliases` / `deep_link` / `android_packages` / `store_urls` 等字段；
- 误判了某个站点：加进该平台的 `host_denylist`（例如腾讯视频 `v.qq.com` 不能当 QQ 登录码）；
- `deep_link` 只允许填**常量**（如 `weixin://`），代码里禁止把它和二维码内容拼接。

## 装到手机上

```bash
# USB 方式（手机需开启 USB 调试）
adb install -r dist/assistant-1.6.0-release.apk
```

不想用数据线：把 `dist/assistant-1.6.0-release.apk` 通过微信/钉钉/网盘发给自己，
在手机上点开安装即可（首次需允许「安装未知来源应用」）。校验完整性可对照 `dist/SHA256SUMS.txt`。

装好后：打开 App → 允许相机权限 → 对准 PC 登录页的二维码 → 按提示点「打开官方 App」→
在官方 App 里扫一扫并确认登录。

两个包的区别：

| 包 | 签名 | 体积 | 用途 |
|---|---|---|---|
| `-release.apk` | 本地自测密钥（`~/keystores/qrlogin.jks`） | 11 MB | 日常安装使用，已 R8 混淆 + 资源压缩 |
| `-debug.apk` | Android debug 密钥 | 15 MB | 调试排错，日志更全 |

`app-release.apk` 用的是本机生成的测试密钥，**只适合自己和同事内测**；要正式对外分发，
请换成公司统一的发布密钥（步骤见 `android/README.md` 的「正式签名」）。

## 打包分发

```bash
cd <项目上级目录>
zip -r assistant-1.6.0.zip assistant \
  -x "*/build/*" "*/.gradle/*" "*/__pycache__/*" "*.zip" "*.idsig"
```

zip 内含：全部源码（`core` / `android` / `tools` / `docs` / `pc`）、已签名 APK（`dist/`）、
签名密钥（`keystore/` + `android/keystore.properties`）、硬币渲染素材（`tools/assets/`）。
不含构建产物、`.gradle` 缓存与 `local.properties`（本机 SDK 路径）。

拿到 zip 的人解压后：

1. 把 `android/local.properties.example` 复制成 `android/local.properties`，填入本机 SDK 路径；
2. `cd android && ./gradlew :app:assembleRelease` —— 自带 wrapper，不用另装 Gradle。

> 密钥随包分发是为了让接手的人能直接出包；**不要提交到公开仓库**（`.gitignore` 已忽略 `*.jks`），
> 对外发布前请换成自己的密钥，详见 [`keystore/README.md`](keystore/README.md)。

## 已知限制

1. **不能代扫登录**：第三方无法上报票据，用户必须在官方 App 内亲自扫一次。
   助手省掉的是"找 App / 判断是哪个平台的码 / 在 App 里找扫一扫"这三步。
2. **扫一扫直达：钉钉 / QQ / 支付宝可以，微信不行**。入口分别是
   钉钉 `dingtalk://dingtalkclient/page/scan`、QQ `mqqapi://qrscan/open?source=1`、
   支付宝 `alipays://platformapi/startapp?appId=10000007`。
   微信官方没有给第三方开放这个能力 —— 反编译它唯一的对外 scheme 入口
   `WXCustomSchemeEntryActivity` 可见，它只处理 `cardpackage` / `connectToFreeWifi` / `wap` / `pay` /
   `yuanbao` / `wxpay` 六类 host，`weixin://dl/scan` 只是内部 webview 路由。
   系统扫一扫（如 Flyme）能直达，是因为系统应用持有 `START_ANY_ACTIVITY` 特权，可拉起微信
   `exported=false` 的扫一扫界面；普通 App 做不到，详见 [`docs/protocol-notes.md`](docs/protocol-notes.md)。
3. **启动 App 用包名，不靠裸 scheme**：裸 scheme 是否注册由各 App 自己决定（钉钉所有 filter 都带 host，
   裸 `dingtalk://` 匹配不到任何组件）。助手按「扫一扫深链 → 包名 → scheme → 商店 → 手动提示」逐级兜底。
4. **微信没有账号密码兜底**：微信网页版仅支持扫码登录，规则表里 `password_login_url` 为 `null`。
5. **App 刻意没有网络权限**：ML Kit 依赖库会合并声明 `INTERNET`，项目里已用 `tools:node="remove"` 移除，
   所以"不上传二维码"是系统级保证；升级依赖后请用 `aapt2 dump permissions` 复核（一致性脚本已覆盖）。
6. **Android 安装检测受包可见性限制**：必须在 `AndroidManifest.xml` 的 `<queries>` 里声明目标包名，
   否则无法可靠判断"未安装"。
7. **PC 端演示页的二维码是假数据**：真实项目由服务端生成并轮询状态。

## 后续可做

- 把规则表做成服务端下发（带签名 + 版本号），客户端本地缓存的兜底版本不变；
- 记录匿名指标（识别成功率、各平台占比、reject 原因分布），用于发现钓鱼二维码的传播；
- iOS 端实现（`canOpenURL` 白名单 + AVFoundation 扫码）。
