"""命令行演示：把一段二维码内容跑完整条决策链路。

    python3 -m qrlogin scan "https://login.weixin.qq.com/qrcode/xxx" --installed wechat
    python3 -m qrlogin scan "https://login.dingtalk.com/..." --installed qq
    python3 -m qrlogin demo
"""

from __future__ import annotations

import argparse
import sys

from .installer import DesktopProbe, StaticProbe
from .models import Action, Decision, InstallStatus, Platform
from .router import route
from .rules import RuleSet

SAMPLES = [
    ("微信（已安装）", "https://login.weixin.qq.com/qrcode/2b0f9c?uuid=abc123", [Platform.WECHAT]),
    ("钉钉（未安装）", "https://login.dingtalk.com/qr/9f2c?scene=qr_login&ticket=deadbeef", []),
    ("QQ（已安装）", "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609&s_url=https://qun.qq.com/", [Platform.QQ]),
    ("支付宝（已安装）", "https://qr.alipay.com/2b0f9c1a?ticket=deadbeef", [Platform.ALIPAY]),
    ("伪装成微信的钓鱼链接", "https://evil.example.com/login?next=wx.qq.com&ticket=aaa", []),
    ("自定义 scheme 注入", "weixin://dl/scan?url=https://evil.example.com", []),
    ("腾讯视频页面（应被 denylist 拦住）", "https://v.qq.com/x/cover/abc.html", [Platform.QQ]),
    ("纯文本线索", "扫码登录 请使用 微信 打开", [Platform.WECHAT]),
]


def _probe(installed_names, environment):
    if installed_names is None:
        return DesktopProbe()
    installed = {Platform(n) for n in installed_names}
    return StaticProbe(installed=installed, environment=environment)


def _render(decision: Decision) -> str:
    lines = [
        f"  动作      : {decision.action.value}",
        f"  平台      : {decision.platform.display_name}",
        f"  标题      : {decision.title}",
        f"  说明      : {decision.detail}",
    ]
    if decision.deep_link:
        lines.append(f"  唤起链接  : {decision.deep_link}  (仅打开 App，不携带任何票据)")
    if decision.store_url:
        lines.append(f"  下载地址  : {decision.store_url}")
    if decision.fallback_url:
        lines.append(f"  兜底入口  : {decision.fallback_url}")
    if decision.fingerprint:
        lines.append(f"  指纹      : {decision.fingerprint}  (原文已丢弃，仅留哈希前缀)")
    if decision.steps:
        lines.append("  用户步骤  :")
        lines.extend(f"    {i}. {step}" for i, step in enumerate(decision.steps, 1))
    return "\n".join(lines)


def cmd_scan(args) -> int:
    rules = RuleSet.default()
    probe = _probe(args.installed, args.environment)
    print(f"规则表版本 {rules.version} | 运行环境 {probe.environment}")
    print("-" * 68)
    decision = route(args.content, probe, rules, environment=args.environment)
    print(_render(decision))
    return 0 if decision.action is not Action.REJECT else 2


def cmd_demo(_args) -> int:
    print("扫码登录助手 · 决策链路演示")
    for title, content, installed in SAMPLES:
        print("=" * 68)
        print(f"场景：{title}")
        print(f"二维码内容：{content}")
        probe = StaticProbe(installed=installed, environment="android")
        decision = route(content, probe, environment="android")
        print(_render(decision))
    print("=" * 68)
    print("提示：任何情况下助手都只负责「识别 + 唤起 App」，"
          "登录确认必须由用户在官方 App 内手动完成。")
    return 0


def cmd_status(_args) -> int:
    rules = RuleSet.default()
    probe = DesktopProbe()
    print(f"规则表版本：{rules.version}")
    print(f"检测环境：{probe.environment}")
    print("-" * 68)
    labels = {
        InstallStatus.INSTALLED: "已安装",
        InstallStatus.NOT_INSTALLED: "未检测到",
        InstallStatus.UNKNOWN: "无法确认",
    }
    for rule in rules.rules:
        status = probe.is_installed(rule)
        print(f"{rule.display_name:<6} {labels[status]:<8} 唤起={rule.deep_link or '-':<12} "
              f"包名={','.join(rule.android_packages) or '-'}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="qrlogin", description="扫码登录助手 · 核心库命令行")
    sub = parser.add_subparsers(dest="command", required=True)

    p_scan = sub.add_parser("scan", help="解析一段二维码内容并给出决策")
    p_scan.add_argument("content", help="二维码里的原始文本")
    p_scan.add_argument("--installed", nargs="*", choices=[p.value for p in Platform if p is not Platform.UNKNOWN],
                        help="指定本机已安装的平台（不填则按当前系统探测）")
    p_scan.add_argument("--environment", default="android", choices=["android", "ios", "web", "linux", "windows", "darwin"])
    p_scan.set_defaults(func=cmd_scan)

    sub.add_parser("demo", help="跑一遍内置场景").set_defaults(func=cmd_demo)
    sub.add_parser("status", help="查看规则表与本机 App 探测结果").set_defaults(func=cmd_status)
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
