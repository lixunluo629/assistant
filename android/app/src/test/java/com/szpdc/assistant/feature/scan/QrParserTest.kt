package com.szpdc.assistant.feature.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 core/tests/test_parser.py、test_router.py 对齐的行为规范。
 * 输入输出必须与 Python 侧完全一致，改一处就要两边一起改。
 */
class QrParserTest {

    private val rules: RuleSet = RuleSet.fromJson(
        // 与 core/platform_rules.json 结构一致的最小规则集
        """
        {
          "version": "test",
          "credential_params": ["ticket", "uuid", "scene"],
          "platforms": [
            {
              "id": "wechat", "display_name": "微信",
              "domains": ["weixin.qq.com", "wx.qq.com"],
              "url_keywords": ["wx.qq.com"],
              "text_aliases": ["微信"],
              "host_denylist": [],
              "deep_link": "weixin://",
              "android_packages": ["com.tencent.mm"],
              "store_urls": { "android": "market://details?id=com.tencent.mm", "web": "https://weixin.qq.com/" },
              "password_login_url": null
            },
            {
              "id": "dingtalk", "display_name": "钉钉",
              "domains": ["dingtalk.com"],
              "url_keywords": ["login.dingtalk.com"],
              "text_aliases": ["钉钉"],
              "host_denylist": [],
              "deep_link": "dingtalk://",
              "android_packages": ["com.alibaba.android.rimet"],
              "store_urls": { "android": "market://details?id=com.alibaba.android.rimet" },
              "password_login_url": "https://login.dingtalk.com/",
              "scan_entry": { "deep_links": ["dingtalk://dingtalkclient/page/scan"] }
            },
            {
              "id": "bilibili", "display_name": "哔哩哔哩",
              "domains": ["passport.bilibili.com", "bilibili.com"],
              "url_keywords": ["passport.bilibili.com"],
              "text_aliases": ["哔哩哔哩"],
              "host_denylist": [],
              "deep_link": "bilibili://",
              "android_packages": ["tv.danmaku.bili"],
              "store_urls": { "android": "market://details?id=tv.danmaku.bili" },
              "password_login_url": "https://passport.bilibili.com/login",
              "scan_entry": { "deep_links": ["bilibili://qrscan"] }
            },
            {
              "id": "baidu", "display_name": "百度",
              "domains": ["passport.baidu.com", "baidu.com"],
              "url_keywords": ["passport.baidu.com"],
              "text_aliases": ["百度"],
              "host_denylist": [],
              "deep_link": "baiduboxapp://",
              "android_packages": ["com.baidu.searchbox"],
              "store_urls": { "android": "market://details?id=com.baidu.searchbox" },
              "password_login_url": "https://passport.baidu.com/v2/?login",
              "scan_entry": {
                "deep_links": ["baiduboxapp://v16/ucenter/scan"],
                "activity_classes": ["com.baidu.searchbox.barcode.entry.CodeScannerActivity"]
              }
            },
            {
              "id": "alipay", "display_name": "支付宝",
              "domains": ["qr.alipay.com", "auth.alipay.com", "alipay.com"],
              "url_keywords": ["qr.alipay.com"],
              "text_aliases": ["支付宝"],
              "host_denylist": [],
              "deep_link": "alipays://",
              "android_packages": ["com.eg.android.AlipayGphone"],
              "store_urls": { "android": "market://details?id=com.eg.android.AlipayGphone" },
              "password_login_url": "https://auth.alipay.com/login/index.htm",
              "scan_entry": { "deep_links": ["alipays://platformapi/startapp?appId=10000007"] }
            },
            {
              "id": "qq", "display_name": "QQ",
              "domains": ["qq.com"],
              "url_keywords": ["qun.qq.com"],
              "text_aliases": ["QQ"],
              "host_denylist": ["v.qq.com"],
              "deep_link": "mqq://",
              "android_packages": ["com.tencent.mobileqq"],
              "store_urls": { "android": "market://details?id=com.tencent.mobileqq" },
              "password_login_url": "https://xui.ptlogin2.qq.com/",
              "extra_schemes": ["mqqapi"],
              "scan_entry": { "deep_links": ["mqqapi://qrscan/open?source=1"] }
            }
          ]
        }
        """.trimIndent(),
    )

    private fun success(raw: String): LoginTarget {
        val outcome = QrParser.parse(raw, rules)
        assertTrue("expected success for " + raw, outcome is ParseOutcome.Success)
        return (outcome as ParseOutcome.Success).target
    }

    private fun rejection(raw: String): ParseOutcome.Rejected {
        val outcome = QrParser.parse(raw, rules)
        assertTrue("expected rejection for " + raw, outcome is ParseOutcome.Rejected)
        return outcome as ParseOutcome.Rejected
    }

    @Test
    fun wechatBeatsQqByLongestSuffix() {
        val target = success("https://login.weixin.qq.com/qrcode/abc")
        assertEquals(Platform.WECHAT, target.platform)
        assertEquals("weixin.qq.com", target.host)
        assertEquals("host", target.matchedBy)
        assertFalse(target.loose)
    }

    @Test
    fun caseAndPortAreNormalized() {
        assertEquals(Platform.WECHAT, success("https://Login.WeiXin.QQ.com:443./qrcode/x").platform)
    }

    @Test
    fun denylistBlocksTencentVideo() {
        assertEquals(RejectionReason.UNKNOWN_PLATFORM, rejection("https://v.qq.com/x/cover/abc.html").reason)
    }

    @Test
    fun phishingUrlIsRejected() {
        assertEquals(
            RejectionReason.UNKNOWN_PLATFORM,
            rejection("https://evil.example.com/login?next=wx.qq.com").reason,
        )
    }

    @Test
    fun customSchemeIsRejected() {
        assertEquals(RejectionReason.UNSAFE_SCHEME, rejection("weixin://dl/scan?url=https://evil.example.com").reason)
    }

    @Test
    fun credentialFlagNeverCarriesValue() {
        val target = success("https://login.weixin.qq.com/qrcode/a?uuid=SECRET&ticket=SECRET")
        assertTrue(target.hasCredential)
        assertFalse(target.toString().contains("SECRET"))
    }

    @Test
    fun plainTextAliasIsLoose() {
        val target = success("扫码登录 请使用 微信 打开")
        assertEquals(Platform.WECHAT, target.platform)
        assertTrue(target.loose)
    }

    @Test
    fun routerNeverLaunchesOnLooseMatch() {
        val target = success("扫码登录 请使用 微信 打开")
        val rule = rules.ruleFor(Platform.WECHAT)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(Action.MANUAL_SCAN, decision.action)
        assertNull(decision.deepLink)
    }

    @Test
    fun installedAppGetsConstantDeepLink() {
        val target = success("https://login.weixin.qq.com/qrcode/a?redirect=https://evil.example.com")
        val rule = rules.ruleFor(Platform.WECHAT)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(Action.OPEN_APP, decision.action)
        assertEquals("weixin://", decision.deepLink)
        assertTrue(decision.isSafeToLaunch)
        assertFalse(decision.toString().contains("evil.example.com"))
    }

    @Test
    fun missingAppPromptsDownloadWithFallback() {
        val target = success("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1")
        val rule = rules.ruleFor(Platform.QQ)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.NOT_INSTALLED, rule)
        assertEquals(Action.PROMPT_DOWNLOAD, decision.action)
        assertEquals("https://xui.ptlogin2.qq.com/", decision.fallbackUrl)
    }

    @Test
    fun unknownInstallStatusNeverAutolaunches() {
        val target = success("https://login.weixin.qq.com/qrcode/a")
        val rule = rules.ruleFor(Platform.WECHAT)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.UNKNOWN, rule)
        assertEquals(Action.MANUAL_SCAN, decision.action)
        assertFalse(decision.isSafeToLaunch)
    }

    @Test
    fun decisionCarriesOfficialPackageNameForLaunching() {
        // Android 端靠包名启动 App（裸 scheme 不可靠），Decision 必须带上规则表里的包名
        val target = success("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1")
        val rule = rules.ruleFor(Platform.QQ)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(listOf("com.tencent.mobileqq"), decision.androidPackages)
        assertEquals("mqq://", decision.deepLink)
    }

    @Test
    fun packagesComeFromRulesNotQrContent() {
        val target = success("https://login.weixin.qq.com/qrcode/a?pkg=com.evil.app")
        val rule = rules.ruleFor(Platform.WECHAT)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(listOf("com.tencent.mm"), decision.androidPackages)
        assertFalse(decision.toString().contains("com.evil.app"))
    }

    @Test
    fun dingtalkGetsDirectScanEntry() {
        // 只有钉钉对外暴露了扫一扫入口，Decision 要把它带下去
        val target = success("https://login.dingtalk.com/qr/abc")
        val rule = rules.ruleFor(Platform.DINGTALK)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(listOf("dingtalk://dingtalkclient/page/scan"), decision.scanDeepLinks)
        assertTrue(decision.steps[0].contains("扫一扫"))
    }

    @Test
    fun threePlatformsCanOpenScanDirectly() {
        // 实测：钉钉 / QQ / 支付宝可直达扫一扫。
        // 注意 QQ 的路由藏在 dex 里（外部深链统一进 JumpActivity 再内部分发），
        // 只看 manifest 会误判成"没有入口"。
        val cases = listOf(
            Triple("https://login.dingtalk.com/qr/abc", Platform.DINGTALK, "dingtalk://dingtalkclient/page/scan"),
            Triple("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1", Platform.QQ, "mqqapi://qrscan/open?source=1"),
            Triple("https://qr.alipay.com/2b0f9c1a", Platform.ALIPAY, "alipays://platformapi/startapp?appId=10000007"),
        )
        for ((raw, platform, expected) in cases) {
            val rule = rules.ruleFor(platform)!!
            val decision = LoginRouter.decideForTarget(success(raw), InstallStatus.INSTALLED, rule)
            assertTrue("缺少 " + expected, decision.scanDeepLinks.contains(expected))
            assertTrue(decision.detail.contains("扫一扫"))
        }
    }

    @Test
    fun newPlatformsCanOpenScan() {
        // B站走 scheme，百度走 scheme + 显式类名兜底
        val bili = LoginRouter.decideForTarget(
            success("https://passport.bilibili.com/login/QRLogin"),
            InstallStatus.INSTALLED, rules.ruleFor(Platform.BILIBILI)!!,
        )
        assertEquals(listOf("bilibili://qrscan"), bili.scanDeepLinks)

        val baidu = LoginRouter.decideForTarget(
            success("https://passport.baidu.com/v2/?login"),
            InstallStatus.INSTALLED, rules.ruleFor(Platform.BAIDU)!!,
        )
        assertEquals(listOf("baiduboxapp://v16/ucenter/scan"), baidu.scanDeepLinks)
        assertTrue(baidu.scanActivityClasses.any { it.contains("CodeScannerActivity") })
    }

    @Test
    fun activityClassesAreConstantClassNames() {
        for (rule in rules.rules) {
            for (cls in rule.scanActivityClasses) {
                assertTrue("非法类名：" + cls, Regex("""^[a-z][\w]*(\.[\w]+)+$""").matches(cls))
            }
        }
    }

    @Test
    fun wechatAutoOpensMainUiBecauseScanEntryIsClosed() {
        // 微信官方不开放扫一扫（实测）：仍自动打开微信主界面，但文案要如实说明
        val target = success("https://login.weixin.qq.com/qrcode/a")
        val rule = rules.ruleFor(Platform.WECHAT)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(Action.OPEN_APP, decision.action)
        assertTrue(decision.scanDeepLinks.isEmpty())
        assertTrue(decision.detail.contains("没有对外提供扫一扫快捷入口"))
        assertTrue(decision.autoLaunch)
    }

    @Test
    fun autoLaunchOnlyForActionableDecisions() {
        // 有明确目标的动作才自动跳；拒绝与不确定的情况必须停下来让用户看提示
        val installed = rules.ruleFor(Platform.WECHAT)!!
        val target = success("https://login.weixin.qq.com/qrcode/a")
        assertTrue(LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, installed).autoLaunch)
        assertTrue(LoginRouter.decideForTarget(target, InstallStatus.NOT_INSTALLED, installed).autoLaunch)
        assertFalse(LoginRouter.decideForTarget(target, InstallStatus.UNKNOWN, installed).autoLaunch)
        assertFalse(LoginRouter.rejectDecision(RejectionReason.UNSAFE_SCHEME, "x").autoLaunch)
        assertFalse(LoginRouter.decideForPasswordLogin(Platform.QQ, rules).autoLaunch)
    }

    @Test
    fun scanLinksNeverCarryQrPayload() {
        // 四平台的扫一扫深链都必须是"干净"的：带上 qrcode/result 这类参数就等于代扫
        val banned = listOf("qrcodetext", "qrcode=", "handlescanresult", "result=", "data=", "url=", "ticket=", "code=")
        for (rule in rules.rules) {
            for (link in rule.scanDeepLinks) {
                for (word in banned) {
                    assertFalse(rule.displayName + " 的扫一扫深链含 " + word + "：" + link, link.lowercase().contains(word))
                }
            }
        }
    }

    @Test
    fun degradesGracefullyWhenPlatformHasNoScanEntry() {
        // 平台没有扫一扫入口时（微信就是这种情况），文案退化为"请自己点扫一扫"
        val target = success("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=1")
        val rule = rules.ruleFor(Platform.QQ)!!.copy(scanDeepLinks = emptyList())
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertTrue(decision.scanDeepLinks.isEmpty())
        assertTrue(decision.detail.contains("没有对外提供扫一扫快捷入口"))
    }

    @Test
    fun alipayIsRecognizedAndCanOpenScan() {
        val target = success("https://qr.alipay.com/2b0f9c1a")
        assertEquals(Platform.ALIPAY, target.platform)
        val rule = rules.ruleFor(Platform.ALIPAY)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.INSTALLED, rule)
        assertEquals(listOf("alipays://platformapi/startapp?appId=10000007"), decision.scanDeepLinks)
        assertEquals("alipays://", decision.deepLink)
        assertEquals(listOf("com.eg.android.AlipayGphone"), decision.androidPackages)
    }

    @Test
    fun alipayScanLinkCarriesNoCredentialParams() {
        // 扫一扫深链必须是"干净"的：一旦带上 qrcode/url 参数就等于让支付宝代扫
        val rule = rules.ruleFor(Platform.ALIPAY)!!
        for (link in rule.scanDeepLinks) {
            for (forbidden in listOf("qrcode=", "url=", "ticket=", "code=")) {
                assertFalse("扫一扫深链不得携带 " + forbidden + "：" + link, link.contains(forbidden))
            }
        }
    }

    @Test
    fun scanEntryIsDroppedWhenAppMissing() {
        val target = success("https://login.dingtalk.com/qr/abc")
        val rule = rules.ruleFor(Platform.DINGTALK)!!
        val decision = LoginRouter.decideForTarget(target, InstallStatus.NOT_INSTALLED, rule)
        assertEquals(Action.PROMPT_DOWNLOAD, decision.action)
        assertTrue(decision.scanDeepLinks.isEmpty())
    }

    @Test
    fun wechatHasNoPasswordLoginEntry() {
        assertEquals(Action.MANUAL_SCAN, LoginRouter.decideForPasswordLogin(Platform.WECHAT, rules).action)
        assertEquals(Action.PASSWORD_LOGIN, LoginRouter.decideForPasswordLogin(Platform.QQ, rules).action)
    }

    @Test
    fun ruleSetLoadsVersion() {
        val json = JSONObject("""{"version":"9.9.9","platforms":[{"id":"wechat","display_name":"微信"}]}""")
        assertEquals("9.9.9", json.getString("version"))
        assertTrue(rules.version.isNotEmpty())
    }
}
