# Android 端：小助手 App

## 这个模块做什么

CameraX 取相机流 → ML Kit 解码二维码 → 交给与 Python 核心库同源的决策逻辑 → 按决策渲染结果卡片。
**登录确认永远由用户在官方 App 内手动完成。**

## 关键设计

| 决策 | 原因 |
|---|---|
| **不申请 `INTERNET` 权限** | 没有网络权限，"不上传二维码内容"就成了系统级保证，而不是口头承诺 |
| **bundled 版 ML Kit 模型** | 离线可用，不依赖 Play Services 下载模型 |
| **唤起 App 由用户点击触发，不自动跳转** | 符合平台对后台启动 Activity 的限制，也避免"手滑进了别的 App" |
| **启动用包名而不是裸 scheme** | 裸 scheme 是否注册由各 App 决定（钉钉就不响应 `dingtalk://`），包名则与安装检测同源 |
| **`<queries>` 声明目标包名与 scheme** | Android 11+ 包可见性必需，否则安装检测会把"已安装"误判为"未安装" |
| **规则表从 `core/platform_rules.json` 同步** | Python 核心库与 Android 端共用同一份规则，行为一致 |
| **解析结果只保留平台枚举** | 原文在 `QrParser.parse()` 返回后即丢弃，不进日志、不进 ViewModel |

## 目录

```
android/
├── settings.gradle.kts               # rootProject.name = assistant
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts              # namespace/applicationId = com.szpdc.assistant
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml   # CAMERA 权限 + <queries>（无 INTERNET）
        │   ├── java/com/szpdc/assistant/
        │   │   ├── HomeActivity.kt           # 首页：功能菜单（唯一入口）
        │   │   └── feature/
        │   │       ├── Tool.kt               # 功能项模型
        │   │       ├── ToolRegistry.kt       # ◀ 功能注册表，加功能只改这里
        │   │       ├── random/               # 功能：随机事件（自包含）
        │   │       │   ├── RandomActivity.kt # 标签切换 + 动画编排
        │   │       │   ├── DiceView.kt       # 自绘骰子（摇动动画）
        │   │       │   ├── CoinView.kt       # 硬币贴图 + 上抛翻转动画
        │   │       │   ├── RandomOps.kt      # 随机算法（纯函数，25 个单测）
        │   │       │   └── RandomSource.kt   # SecureRandom 封装，可注入测试替身
        │   │       └── scan/                 # 功能：扫码登录助手（自包含）
        │   │           ├── ScanActivity.kt
        │   │           ├── ScanAnalyzer.kt   # CameraX + ML Kit
        │   │           ├── Models.kt
        │   │           ├── PlatformRules.kt
        │   │           ├── QrParser.kt
        │   │           ├── AppInstallProbe.kt
        │   │           └── LoginRouter.kt
        │   └── res/
        │       ├── drawable-nodpi/          # 硬币贴图（由 tools/coin-make.py 生成，勿手改）
        │       ├── layout/activity_home.xml  # 首页
        │       ├── layout/item_tool.xml      # 功能卡片
        │       ├── layout/activity_scan.xml  # 扫码页
        │       └── values/strings.xml
        └── test/java/com/szpdc/assistant/feature/scan/QrParserTest.kt
```

## 构建（已在本机实测通过）

前置条件：JDK 17 + Android SDK（`platforms;android-34`、`build-tools;34.0.0`、`platform-tools`）。

```bash
# 1. 指向本机 SDK（local.properties 已 gitignore）
cd android
echo "sdk.dir=$HOME/android-sdk" > local.properties

# 2. 出包
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest  # Kotlin 侧 13 个单元测试（与 Python 用例对齐）
```

> 本项目自带 Gradle Wrapper，`distributionUrl` 已指向腾讯云镜像（国内拉取快）。
> 若机器上没有 SDK：`sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"`

### 实测结果

| 项 | 结果 |
|---|---|
| `assembleDebug` | BUILD SUCCESSFUL，APK 15 MB |
| `assembleRelease`（R8 混淆 + 资源压缩） | BUILD SUCCESSFUL，APK 11 MB，zipalign 校验通过 |
| Kotlin 单元测试 | 52 个用例全过（`QrParserTest`，与 `core/tests` 同规范） |
| APK 权限 | 只有 `CAMERA`（+ androidx 内部权限），**无 INTERNET / ACCESS_NETWORK_STATE** |
| ABI | arm64-v8a + armeabi-v7a |

### 关于网络权限（重要）

ML Kit 依赖库会在自己的 manifest 里声明 `INTERNET` 与 `ACCESS_NETWORK_STATE`，manifest merger
会把它们合并进来 —— 也就是说"我没写"并不等于"APK 里没有"。因此本项目在 `AndroidManifest.xml`
里显式移除：

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

改依赖版本或换解码库后，务必重新验证一次：

```bash
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
```

`tools/check_consistency.py` 也会自动检查这一条，漏了 `tools:node="remove"` 会直接报错。

### 唤起 App：扫一扫直达 → 包名 → scheme → 商店

真机验证踩过的两个坑，决定了现在的顺序：

1. 裸 scheme `dingtalk://` 钉钉不注册（它的 filter 全都带 host），直接 `startActivity` 抛
   `ActivityNotFoundException`，用户看到「找不到对应的 App」——而安装检测明明说它装了；
2. 用户希望"点一下就直接进扫一扫"，而不是进去后再找按钮。

所以 `launchOfficialApp()` 的顺序是：

```kotlin
1. decision.scanDeepLinks        // 扫一扫深链（钉钉/QQ/支付宝/B站/百度/小红书/抖音）
2. decision.scanActivityClasses  // 显式 Activity 类名（百度这类 exported=true 但没有 scheme 的）
3. getLaunchIntentForPackage()   // 按包名启动，与安装检测同一份包名，最可靠
4. deepLink（裸 scheme）          // 尽力而为
5. market:// 商店 / 网页登录入口    // 逐级兜底
6. 都失败 → 提示用户手动打开 App
```

入口位置因平台而异：钉钉直接写在 manifest 里；QQ / 支付宝只在 **dex 路由串**里
（manifest 中只有一个统一的路由 Activity）—— 判断有无入口必须两边都看。
**微信官方不开放扫一扫**（详见 `../docs/protocol-notes.md`），`scanDeepLinks` 为空列表，
此时文案自动退化为"打开 App，请用户自己点扫一扫"。

改这块时务必保持顺序与兜底链：扫一扫深链失效时不能让用户卡在报错上。

### 扫码后自动跳转

`Decision.autoLaunch` 为 true 时，`onDecoded` 渲染完结果卡片会延迟 350ms 自动执行主操作，
用户不需要再点按钮。`OPEN_APP` / `PROMPT_DOWNLOAD` 为 true，`REJECT` / `MANUAL_SCAN` / `PASSWORD_LOGIN`
为 false（安全警告必须让用户看到）。

从官方 App 返回时 `onResume` 会恢复扫描，但**不重置** `lastHandledFingerprint` —— 否则手机还对着
同一张码会立刻再次自动跳转，形成死循环。要重扫同一张码就点「重新扫描」。

### 新增一个功能

1. 新建 `feature/<功能名>/` 子包，写自己的 Activity（自包含，别碰别的功能内部实现）；
2. 在 `feature/ToolRegistry.kt` 的 `tools` 列表里加一行（顺序即菜单顺序）；
3. 在 `AndroidManifest.xml` 注册该 Activity。

首页 `HomeActivity` 会自动渲染，**不需要改**。第 3 步最容易漏，所以加进了自动检查：
`tools/check_consistency.py` 会比对 `ToolRegistry` 与清单，漏注册会直接报错（已做负向验证）。

### 返回后回到"重新扫描"状态

从官方 App 返回时 `onResume` 会把界面恢复成可扫描状态（清空结果卡片、清空指纹，允许重扫同一张码）。

但有一个刻意的约束：**跳转发生后，必须先把镜头移开一次（画面里出现"没有二维码"）才允许再次触发**，
由 `readyForNextCode` + `ScanAnalyzer.onNothingDetected` 实现。否则用户从官方 App 返回时手机还对着
同一张码，会立刻又被跳走，来回死循环。

### 正式签名

`android/keystore.properties`（已 gitignore）存在时，release 包用其中的密钥签名；不存在时回退
用 debug 签名，保证本地随时能出包自测。

```bash
keytool -genkeypair -v -keystore ~/keystores/qrlogin.jks -alias qrlogin \
  -keyalg RSA -keysize 2048 -validity 9125
cat > android/keystore.properties <<'EOF'
storeFile=/home/you/keystores/qrlogin.jks
storePassword=...
keyAlias=qrlogin
keyPassword=...
EOF
```

### 装到手机

```bash
# 方式 1：USB 调试
adb install -r app/build/outputs/apk/release/app-release.apk

# 方式 2：直接把 APK 传到手机（微信/钉钉/数据线），点击安装
#   首次需在系统设置里允许「安装未知来源应用」
```

安装后首次打开会请求相机权限；App 体积小、无网络权限，可放心在离线环境使用。

## 与 Python 核心库的一致性约定

两边必须保持同一套语义，改动时要同步：

| 语义 | Python | Kotlin |
|---|---|---|
| 平台枚举 | `models.Platform` | `core.Platform` |
| 动作枚举 | `models.Action` | `core.Action` |
| 最长后缀 + denylist | `rules.RuleSet.match_host` | `PlatformRules.RuleSet.matchHost` |
| 解析流水线（scheme 闸门 → host → loose） | `parser.parse` | `QrParser.parse` |
| 决策表 | `router.decide_for_target` | `LoginRouter.decideForTarget` |
| 安装检测三态 | `installer.AndroidProbe` | `AppInstallProbe` |

验证方式：`core/tests` 里的用例就是行为规范；Kotlin 侧若有对应测试，输入输出必须一致。

## 自测清单

- [ ] 分别扫微信 / 钉钉 / QQ 二维码，确认识别平台正确，且点「打开官方 App」能**真的唤起**对应 App
- [ ] 扫钉钉 / 支付宝二维码时确认**直接进入对应 App 的扫一扫界面**（而不是首页）
- [ ] 卸载其中一个 App，确认提示下载并给出官方商店链接
- [ ] 扫 `https://v.qq.com/` 的二维码，确认不跳到 QQ 登录
- [ ] 扫自定义 scheme 内容（如 `weixin://dl/scan`），确认被拒绝
- [ ] 用抓包工具确认全程无外发请求（应尽为空，因为无 INTERNET 权限）
- [ ] `adb logcat` 确认日志里没有二维码原文或票据
