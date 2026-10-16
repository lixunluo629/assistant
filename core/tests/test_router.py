import unittest

from qrlogin import (Action, InstallStatus, Platform, RuleSet,
                     decide_for_password_login, decide_for_target, parse, route)  # noqa: F401
from qrlogin.installer import AndroidProbe, StaticProbe

RULES = RuleSet.default()


class RouterTest(unittest.TestCase):
    def test_installed_opens_official_app(self):
        d = route("https://login.weixin.qq.com/qrcode/abc",
                  StaticProbe(installed=[Platform.WECHAT]), RULES, environment="android")
        self.assertIs(d.action, Action.OPEN_APP)
        self.assertEqual(d.deep_link, "weixin://")
        self.assertTrue(d.is_safe_to_launch)
        self.assertIn("手动", "".join(d.steps))

    def test_not_installed_prompts_download_with_fallback(self):
        d = route("https://login.dingtalk.com/qr/abc",
                  StaticProbe(), RULES, environment="android")
        self.assertIs(d.action, Action.PROMPT_DOWNLOAD)
        self.assertTrue(d.store_url.startswith("market://"))
        self.assertEqual(d.fallback_url, "https://login.dingtalk.com/")

    def test_ios_gets_appstore_link(self):
        d = route("https://login.dingtalk.com/qr/abc", StaticProbe(), RULES, environment="ios")
        self.assertIn("apps.apple.com", d.store_url)

    def test_wechat_has_no_password_fallback(self):
        d = route("https://login.weixin.qq.com/qrcode/abc", StaticProbe(), RULES)
        self.assertIsNone(d.fallback_url)

    def test_unknown_install_status_never_autolaunches(self):
        d = route("https://login.dingtalk.com/qr/abc",
                  StaticProbe(unknown=[Platform.DINGTALK]), RULES, environment="android")
        self.assertIs(d.action, Action.MANUAL_SCAN)
        self.assertFalse(d.is_safe_to_launch)

    def test_loose_match_never_autolaunches_even_if_installed(self):
        d = route("请用钉钉扫码登录", StaticProbe(installed=[Platform.DINGTALK]), RULES)
        self.assertIs(d.action, Action.MANUAL_SCAN)
        self.assertIsNone(d.deep_link)

    def test_unknown_qr_degrades_to_manual(self):
        d = route("https://random.example.com/login", StaticProbe(), RULES)
        self.assertIs(d.action, Action.MANUAL_SCAN)
        self.assertIsNone(d.deep_link)

    def test_unsafe_scheme_is_rejected(self):
        d = route("mqq://login", StaticProbe(installed=[Platform.QQ]), RULES)
        self.assertIs(d.action, Action.REJECT)
        self.assertIsNotNone(d.rejection_reason)

    def test_deep_link_never_contains_qr_content(self):
        """回归测试：二维码里的恶意域名不得出现在深链里。"""
        raw = "https://login.weixin.qq.com/qrcode/abc?redirect=https://evil.example.com/pwn"
        d = route(raw, StaticProbe(installed=[Platform.WECHAT]), RULES)
        self.assertEqual(d.deep_link, "weixin://")
        self.assertNotIn("evil.example.com", str(d))

    def test_decision_carries_official_package_names(self):
        """Android 端要用包名启动 App：Decision 必须带上规则表里的包名常量。"""
        d = route("https://login.dingtalk.com/qr/abc",
                  StaticProbe(installed=[Platform.DINGTALK]), RULES)
        self.assertEqual(d.android_packages, ("com.alibaba.android.rimet",))

    def test_packages_come_from_rules_not_qr_content(self):
        """回归测试：二维码里塞包名也不能影响启动目标。"""
        raw = "https://login.weixin.qq.com/qrcode/a?pkg=com.evil.app"
        d = route(raw, StaticProbe(installed=[Platform.WECHAT]), RULES)
        self.assertEqual(d.android_packages, ("com.tencent.mm",))
        self.assertNotIn("com.evil.app", str(d))

    def test_dingtalk_has_direct_scan_entry(self):
        """实测：只有钉钉对外暴露了扫一扫入口（BokuiActivity, exported=true）。"""
        d = route("https://login.dingtalk.com/qr/abc",
                  StaticProbe(installed=[Platform.DINGTALK]), RULES)
        self.assertTrue(d.scan_deep_links)
        for link in d.scan_deep_links:
            self.assertTrue(link.startswith("dingtalk://"), link)
            self.assertIn("/page/scan", link)
        self.assertIn("扫一扫", d.steps[0])
        self.assertIn("扫一扫", d.detail)

    def test_three_platforms_can_open_scan_directly(self):
        """实测：钉钉 / QQ / 支付宝可以直达扫一扫。

        注意 QQ 的路由藏在 dex 里（外部深链统一进 JumpActivity 再内部分发），
        只看 manifest 会误判成"没有入口"——这是本项目踩过的坑。
        """
        cases = [
            ("https://login.dingtalk.com/qr/abc", Platform.DINGTALK, "dingtalk://dingtalkclient/page/scan"),
            ("https://xui.ptlogin2.qq.com/cgi-bin/xlogin", Platform.QQ, "mqqapi://qrscan/open?source=1"),
            ("https://qr.alipay.com/2b0f9c1a", Platform.ALIPAY, "alipays://platformapi/startapp?appId=10000007"),
        ]
        for raw, platform, expected in cases:
            d = route(raw, StaticProbe(installed=[platform]), RULES)
            self.assertIs(d.platform, platform)
            self.assertIn(expected, d.scan_deep_links, expected)
            self.assertIn("扫一扫", d.detail)

    def test_new_platforms_are_recognized(self):
        """新增的 7 个热门平台都要能识别，扫一扫入口（有就直达、没有就降级）来自规则表。"""
        cases = [
            ("https://passport.bilibili.com/login/QRLogin", Platform.BILIBILI, "bilibili://qrscan"),
            ("https://work.weixin.qq.com/wework_admin/loginpage_wx", Platform.WEWORK, None),
            ("https://passport.baidu.com/v2/?login", Platform.BAIDU, "baiduboxapp://v16/ucenter/scan"),
            ("https://pan.quark.cn/list", Platform.QUARK, None),
            ("https://www.xiaohongshu.com/login", Platform.XIAOHONGSHU, "xhsdiscover://qr_scan"),
            ("https://www.douyin.com/?modal_id=1", Platform.DOUYIN, "snssdk1128://aweme/scan"),
            ("https://s.team/q/1/abc", Platform.STEAM, None),
        ]
        for raw, platform, scan_link in cases:
            d = route(raw, StaticProbe(installed=[platform]), RULES)
            self.assertIs(d.platform, platform, raw)
            if scan_link:
                self.assertIn(scan_link, d.scan_deep_links, raw)
                self.assertIn("扫一扫", d.detail, raw)
            else:
                self.assertEqual(d.scan_deep_links, (), raw)
                self.assertTrue(d.auto_launch, raw)  # 仍会自动打开 App

    def test_wework_is_not_swallowed_by_wechat(self):
        """work.weixin.qq.com 属于企业微信：域名互相包含时必须最长后缀优先。"""
        self.assertIs(route("https://work.weixin.qq.com/", StaticProbe(), RULES).platform, Platform.WEWORK)
        self.assertIs(route("https://open.work.weixin.qq.com/", StaticProbe(), RULES).platform, Platform.WEWORK)
        self.assertIs(route("https://login.weixin.qq.com/qrcode/a", StaticProbe(), RULES).platform, Platform.WECHAT)
        self.assertIs(route("https://wx.qq.com/", StaticProbe(), RULES).platform, Platform.WECHAT)

    def test_baidu_uses_activity_class_fallback(self):
        """百度把扫码 Activity 声明为 exported=true 却没有 scheme，需要类名兜底。"""
        d = route("https://passport.baidu.com/v2/?login",
                  StaticProbe(installed=[Platform.BAIDU]), RULES)
        self.assertTrue(d.scan_activity_classes)
        self.assertTrue(any("CodeScannerActivity" in c for c in d.scan_activity_classes))
        self.assertIn("扫一扫", d.detail)  # 有类名兜底同样算"可直达"

    def test_scan_activity_classes_are_constant_class_names(self):
        for rule in RULES.rules:
            for cls in rule.scan_activity_classes:
                self.assertRegex(cls, r"^[a-z][\w]*(\.[\w]+)+$", cls)

    def test_wechat_has_no_public_scan_entry(self):
        """微信官方不开放扫一扫（实测）：只能打开主界面，文案要如实说明。"""
        d = route("https://login.weixin.qq.com/qrcode/a",
                  StaticProbe(installed=[Platform.WECHAT]), RULES)
        self.assertIs(d.action, Action.OPEN_APP)
        self.assertEqual(d.scan_deep_links, ())
        self.assertIn("没有对外提供扫一扫快捷入口", d.detail)
        self.assertTrue(d.auto_launch)  # 仍会自动打开微信主界面

    def test_auto_launch_only_for_actionable_decisions(self):
        """自动跳转只给有明确目标的动作；拒绝与不确定的情况必须停下来让用户看提示。"""
        installed = StaticProbe(installed=[Platform.WECHAT])
        self.assertTrue(route("https://login.weixin.qq.com/qrcode/a", installed, RULES).auto_launch)
        self.assertTrue(route("https://login.dingtalk.com/qr/a", StaticProbe(), RULES).auto_launch)  # 未装 → 自动跳商店
        self.assertFalse(route("请用微信扫码登录", installed, RULES).auto_launch)  # loose → 手动
        self.assertFalse(route("https://evil.example.com/login", StaticProbe(), RULES).auto_launch)  # 未知域名 → 手动
        self.assertFalse(route("weixin://dl/scan", installed, RULES).auto_launch)  # scheme 注入 → 拒绝

    def test_scan_links_never_carry_qr_payload(self):
        """所有平台的扫一扫深链都必须是"干净"的：不带能代传二维码内容的参数。"""
        banned = ("qrcodetext", "qrcode=", "handlescanresult", "scanresult",
                  "result=", "data=", "url=", "content=", "ticket=", "code=")
        for rule in RULES.rules:
            for link in rule.scan_deep_links:
                for word in banned:
                    self.assertNotIn(word, link.lower(), f"{rule.display_name}: {link}")

    def test_degrades_gracefully_when_platform_has_no_scan_entry(self):
        """平台没有扫一扫入口时（微信就是这种情况），文案退化为"请自己点扫一扫"。"""
        import dataclasses
        rule = dataclasses.replace(RULES.rule_for(Platform.DINGTALK), scan_deep_links=())
        target = parse("https://login.dingtalk.com/qr/a")
        d = decide_for_target(target, InstallStatus.INSTALLED, rule)
        self.assertEqual(d.scan_deep_links, ())
        self.assertIn("没有对外提供扫一扫快捷入口", d.detail)

    def test_alipay_is_recognized_and_can_open_scan(self):
        """支付宝：qr.alipay.com 识别为支付宝，且能直达扫一扫。"""
        d = route("https://qr.alipay.com/2b0f9c1a?ticket=xxx",
                  StaticProbe(installed=[Platform.ALIPAY]), RULES)
        self.assertIs(d.platform, Platform.ALIPAY)
        self.assertIs(d.action, Action.OPEN_APP)
        self.assertEqual(d.scan_deep_links,
                         ("alipays://platformapi/startapp?appId=10000007",))
        self.assertIn("com.eg.android.AlipayGphone", d.android_packages)
        self.assertEqual(d.deep_link, "alipays://")

    def test_alipay_auth_domain_and_password_fallback(self):
        d = route("https://auth.alipay.com/login/index.htm", StaticProbe(), RULES)
        self.assertIs(d.platform, Platform.ALIPAY)
        self.assertIs(d.action, Action.PROMPT_DOWNLOAD)
        self.assertTrue(d.fallback_url.startswith("https://auth.alipay.com/"))

    def test_alipay_scan_link_has_no_credential_params(self):
        """扫一扫深链必须干干净净：不能带 qrcode/url 这类能代传二维码内容的参数。"""
        rule = RULES.rule_for(Platform.ALIPAY)
        for link in rule.scan_deep_links:
            for forbidden in ("qrcode=", "url=", "ticket=", "code=", "content="):
                self.assertNotIn(forbidden, link)

    def test_scan_entry_links_are_constants(self):
        """回归：扫一扫深链同样不能拼接二维码内容。"""
        raw = "https://login.dingtalk.com/qr/a?redirect=https://evil.example.com"
        d = route(raw, StaticProbe(installed=[Platform.DINGTALK]), RULES)
        for link in d.scan_deep_links:
            self.assertNotIn("evil.example.com", link)
            self.assertNotIn("redirect", link)

    def test_scan_entry_is_dropped_when_app_missing(self):
        """没装 App 时不应再给出扫一扫深链。"""
        d = route("https://login.dingtalk.com/qr/abc", StaticProbe(), RULES)
        self.assertIs(d.action, Action.PROMPT_DOWNLOAD)
        self.assertEqual(d.scan_deep_links, ())

    def test_every_platform_action_exposes_a_launchable_package(self):
        for raw, platform in [
            ("https://login.weixin.qq.com/qrcode/a", Platform.WECHAT),
            ("https://login.dingtalk.com/qr/a", Platform.DINGTALK),
            ("https://xui.ptlogin2.qq.com/cgi-bin/xlogin", Platform.QQ),
        ]:
            d = route(raw, StaticProbe(installed=[platform]), RULES)
            self.assertEqual(d.android_packages,
                             RULES.rule_for(platform).android_packages)

    def test_password_login_fallback(self):
        d = decide_for_password_login(Platform.DINGTALK, RULES)
        self.assertIs(d.action, Action.PASSWORD_LOGIN)
        self.assertTrue(d.fallback_url)

    def test_password_login_unavailable_for_wechat(self):
        d = decide_for_password_login(Platform.WECHAT, RULES)
        self.assertIs(d.action, Action.MANUAL_SCAN)

    def test_decision_steps_present_for_every_action(self):
        for raw, probe in [
            ("https://login.weixin.qq.com/qrcode/a", StaticProbe(installed=[Platform.WECHAT])),
            ("https://login.dingtalk.com/a", StaticProbe()),
            ("https://xui.ptlogin2.qq.com/a", StaticProbe(unknown=[Platform.QQ])),
        ]:
            d = route(raw, probe, RULES, environment="android")
            if d.action is not Action.REJECT:
                self.assertTrue(d.steps, d.title)


class AndroidProbeTest(unittest.TestCase):
    def test_unknown_without_lookup(self):
        rule = RULES.rule_for(Platform.WECHAT)
        self.assertIs(AndroidProbe().is_installed(rule), InstallStatus.UNKNOWN)

    def test_lookup_result_mapping(self):
        rule = RULES.rule_for(Platform.WECHAT)
        self.assertIs(AndroidProbe(lambda pkgs: True).is_installed(rule), InstallStatus.INSTALLED)
        self.assertIs(AndroidProbe(lambda pkgs: False).is_installed(rule), InstallStatus.NOT_INSTALLED)

    def test_platform_exception_is_unknown(self):
        def boom(_pkgs):
            raise RuntimeError("NameNotFoundException")
        self.assertIs(AndroidProbe(boom).is_installed(RULES.rule_for(Platform.WECHAT)),
                      InstallStatus.UNKNOWN)


class DesktopProbeTest(unittest.TestCase):
    def test_detects_known_program(self):
        from qrlogin.installer import DesktopProbe
        probe = DesktopProbe(os_name="linux")
        rule = RULES.rule_for(Platform.WECHAT)
        self.assertIn(probe.is_installed(rule),
                      (InstallStatus.INSTALLED, InstallStatus.NOT_INSTALLED))

    def test_missing_program_list_is_unknown(self):
        from qrlogin.installer import DesktopProbe
        rule = RULES.rule_for(Platform.WECHAT)
        probe = DesktopProbe(os_name="plan9")
        self.assertIs(probe.is_installed(rule), InstallStatus.UNKNOWN)


if __name__ == "__main__":
    unittest.main()
