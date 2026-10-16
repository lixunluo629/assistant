import unittest

from qrlogin import Platform, QrRejected, RejectionReason, parse
from qrlogin.rules import RuleSet, normalize_host


class HostMatchTest(unittest.TestCase):
    def test_wechat_beats_qq_by_longest_suffix(self):
        """weixin.qq.com 同时匹配 qq.com 和 weixin.qq.com，必须判为微信。"""
        target = parse("https://login.weixin.qq.com/qrcode/abc")
        self.assertIs(target.platform, Platform.WECHAT)
        self.assertEqual(target.host, "weixin.qq.com")
        self.assertEqual(target.matched_by, "host")
        self.assertFalse(target.loose)

    def test_qq_login(self):
        target = parse("https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609")
        self.assertIs(target.platform, Platform.QQ)

    def test_dingtalk_login(self):
        target = parse("https://login.dingtalk.com/qr/9f2c?scene=qr_login")
        self.assertIs(target.platform, Platform.DINGTALK)

    def test_wx_qq_com(self):
        self.assertIs(parse("https://wx.qq.com/?lang=zh_CN").platform, Platform.WECHAT)

    def test_case_and_port_and_trailing_dot(self):
        target = parse("https://Login.WeiXin.QQ.com:443./qrcode/x")
        self.assertIs(target.platform, Platform.WECHAT)

    def test_denylist_blocks_tencent_video(self):
        with self.assertRaises(QrRejected) as ctx:
            parse("https://v.qq.com/x/cover/abc.html")
        self.assertIs(ctx.exception.reason, RejectionReason.UNKNOWN_PLATFORM)

    def test_normalize_host(self):
        self.assertEqual(normalize_host("Login.WeiXin.Qq.com:443."), "login.weixin.qq.com")
        self.assertEqual(normalize_host("[::1]:8080"), "")


class CredentialTest(unittest.TestCase):
    def test_credential_flag_without_value(self):
        raw = "https://login.weixin.qq.com/qrcode/abc?uuid=SECRET-UUID&ticket=SECRET-TICKET"
        target = parse(raw)
        self.assertTrue(target.has_credential)
        # 关键：任何字段都不能泄漏票据值
        for value in vars(target).values():
            self.assertNotIn("SECRET", str(value))

    def test_plain_url_without_credential(self):
        self.assertFalse(parse("https://login.dingtalk.com/").has_credential)

    def test_fingerprint_is_not_reversible(self):
        raw = "https://login.weixin.qq.com/qrcode/abc"
        self.assertNotIn("weixin", parse(raw).fingerprint)
        self.assertEqual(parse(raw).fingerprint, parse(raw).fingerprint)


class RejectionTest(unittest.TestCase):
    def test_empty(self):
        with self.assertRaises(QrRejected) as ctx:
            parse("   ")
        self.assertIs(ctx.exception.reason, RejectionReason.EMPTY)

    def test_custom_scheme_rejected(self):
        with self.assertRaises(QrRejected) as ctx:
            parse("weixin://dl/scan?url=https://evil.example.com")
        self.assertIs(ctx.exception.reason, RejectionReason.UNSAFE_SCHEME)

    def test_unknown_domain_rejected(self):
        with self.assertRaises(QrRejected) as ctx:
            parse("https://evil.example.com/login?next=wx.qq.com")
        self.assertIs(ctx.exception.reason, RejectionReason.UNKNOWN_PLATFORM)

    def test_lookalike_domain_rejected(self):
        with self.assertRaises(QrRejected):
            parse("https://weixin.qq.com.evil.example/qrcode")

    def test_plain_text_keyword_is_loose(self):
        target = parse("扫码登录 请使用 微信 打开")
        self.assertIs(target.platform, Platform.WECHAT)
        self.assertTrue(target.loose)


class CleanTest(unittest.TestCase):
    def test_removes_zero_width_and_normalizes(self):
        raw = "\u200bhttps://login.weixin.qq.com/qrcode/a\ufeff"
        self.assertIs(parse(raw).platform, Platform.WECHAT)

    def test_trailing_chinese_punctuation(self):
        self.assertIs(parse("https://wx.qq.com/。").platform, Platform.WECHAT)


class RuleSetTest(unittest.TestCase):
    def test_version_loaded(self):
        self.assertTrue(RuleSet.default().version)

    def test_rule_for_unknown_platform(self):
        self.assertIsNone(RuleSet.default().rule_for(Platform.UNKNOWN))


if __name__ == "__main__":
    unittest.main()
