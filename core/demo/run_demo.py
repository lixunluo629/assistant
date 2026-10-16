#!/usr/bin/env python3
"""把决策链路渲染成"用户看到的样子"，方便给产品/测试同学演示。

    python3 demo/run_demo.py            # 内置场景
    python3 demo/run_demo.py --scenario wechat_installed
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from qrlogin import Platform, route  # noqa: E402
from qrlogin.installer import StaticProbe  # noqa: E402

SCENARIOS = {
    "wechat_installed": ("PC 微信网页版二维码，手机已装微信",
                         "https://login.weixin.qq.com/qrcode/2b0f9c1a?uuid=e3b0c442", [Platform.WECHAT]),
    "dingtalk_missing": ("PC 钉钉登录二维码，手机没装钉钉",
                         "https://login.dingtalk.com/qr/9f2c?scene=qr_login&ticket=deadbeef", []),
    "qq_unknown": ("PC QQ 登录二维码，但系统读不到安装列表",
                         "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609", None),
    "phishing": ("二维码被替换成钓鱼链接",
                 "https://evil.example.com/login?next=wx.qq.com", [Platform.WECHAT]),
    "scheme_injection": ("二维码直接塞自定义 scheme",
                         "weixin://dl/scan?url=https://evil.example.com", [Platform.WECHAT]),
    "plain_text": ("二维码内容只有文字，没有链接",
                   "扫码登录 请使用 钉钉 打开", [Platform.DINGTALK]),
}


def render(title: str, content: str, installed) -> None:
    print("=" * 72)
    print(f"场景：{title}")
    print(f"扫码结果：{content}")
    if installed is None:
        probe = StaticProbe(unknown=[Platform.WECHAT, Platform.DINGTALK, Platform.QQ],
                            environment="android")
        print("本机检测：无法确认（系统限制应用可见性）")
    else:
        probe = StaticProbe(installed=installed, environment="android")
        print(f"本机检测：已安装 {[p.display_name for p in installed] or '无'}")
    d = route(content, probe, environment="android")
    print(f"\n  [{d.action.value}] {d.title}")
    print(f"  {d.detail}")
    if d.deep_link:
        print(f"  唤起：{d.deep_link}")
    if d.store_url:
        print(f"  下载：{d.store_url}")
    if d.fallback_url:
        print(f"  兜底：{d.fallback_url}")
    for i, step in enumerate(d.steps, 1):
        print(f"    {i}. {step}")
    print()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), help="只跑某个场景")
    args = parser.parse_args()
    items = ([(args.scenario, SCENARIOS[args.scenario])] if args.scenario
             else sorted(SCENARIOS.items()))
    for key, (title, content, installed) in items:
        render(title, content, installed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
