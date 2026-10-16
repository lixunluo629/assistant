"""决策引擎：把"解析结论 + 安装状态"翻译成 UI 能直接执行的动作。

硬性安全约束（不要为了"体验更好"打破它）：
1. deep_link 只能取自规则表里的常量，任何情况下都不允许拼接二维码内容；
   否则二维码里塞一个 evil.example 就能把用户带去任意 App 页面。
2. loose 匹配（只靠文字线索猜出来的平台）一律不自动跳转。
3. 未识别平台、非白名单域名，一律拒绝或降级为手动扫码。
4. 本模块不发起任何网络请求，也不接触登录票据。
"""

from __future__ import annotations

from dataclasses import replace
from typing import Optional

from .models import (Action, Decision, InstallStatus, LoginTarget, Platform,
                     QrRejected, RejectionReason)
from .parser import parse
from .rules import PlatformRule, RuleSet

__all__ = ["route", "decide_for_target", "decide_for_rejection", "decide_for_password_login"]


def route(raw: str, probe, rules: Optional[RuleSet] = None,
          environment: Optional[str] = None) -> Decision:
    """主入口：扫码文本 -> Decision。异常也被翻译成 Decision，方便 UI 直接渲染。"""
    rules = rules or RuleSet.default()
    try:
        target = parse(raw, rules)
    except QrRejected as rejected:
        return decide_for_rejection(rejected)

    rule = rules.rule_for(target.platform)
    if rule is None:
        return decide_for_rejection(QrRejected(
            RejectionReason.UNKNOWN_PLATFORM, "缺少该平台的规则配置", target.fingerprint))

    env = environment or getattr(probe, "environment", "unknown")
    status = probe.is_installed(rule)
    return decide_for_target(target, status, rule, env)


def apply_auto_launch(decision: Decision) -> Decision:
    """给"有明确跳转目标"的动作打上自动执行标记。

    OPEN_APP / PROMPT_DOWNLOAD 的下一步是确定的（打开某个 App 或商店），
    用户扫这个码已经表达了意图，因此不必再点一次按钮；
    而 REJECT / MANUAL_SCAN 需要用户先看到风险提示，绝不自动执行。
    """
    if decision.action in (Action.OPEN_APP, Action.PROMPT_DOWNLOAD):
        return replace(decision, auto_launch=True)
    return decision


def decide_for_target(target: LoginTarget, status: InstallStatus,
                      rule: PlatformRule, environment: str = "unknown") -> Decision:
    return apply_auto_launch(_decide_for_target(target, status, rule, environment))


def _decide_for_target(target: LoginTarget, status: InstallStatus,
                       rule: PlatformRule, environment: str = "unknown") -> Decision:
    name = rule.display_name
    hint = rule.scan_target_hint

    # loose 匹配：识别结果本身不可靠，只提示不动手
    if target.loose:
        return Decision(
            action=Action.MANUAL_SCAN,
            platform=target.platform,
            title=f"可能是{name}登录二维码",
            detail=("二维码内容里没有找到官方登录链接，只是出现了与"
                    f"{name}相关的文字线索，因此不做自动跳转。请打开{name}手动确认。"),
            android_packages=rule.android_packages,
            steps=(f"打开{name}", f"进入「{hint}」", "对准 PC 屏幕上的二维码扫描并确认登录"),
            fingerprint=target.fingerprint,
        )

    if status is InstallStatus.INSTALLED:
        # 少数平台（实测目前只有钉钉）对外暴露了扫一扫入口，可以直接把用户送到扫一扫界面；
        # 扫一扫里的内容仍然由用户自己扫、自己确认，助手不接触任何票据。
        if rule.supports_direct_scan:
            return Decision(
                action=Action.OPEN_APP,
                platform=target.platform,
                title=f"已识别为{name}登录二维码",
                detail=(f"本机已安装{name}，正在为你打开{name}的「{hint}」。"
                        f"请对准 PC 屏幕上的二维码扫描，并在{name}内手动确认登录。"),
                deep_link=rule.deep_link,  # 只来自规则表常量
                fallback_url=rule.password_login_url,
                android_packages=rule.android_packages,
                scan_deep_links=rule.scan_deep_links,
                scan_activity_classes=rule.scan_activity_classes,
                steps=(f"自动打开{name}的「{hint}」",
                       "对准 PC 屏幕上的二维码扫描",
                       f"在{name}内手动点击「确认登录」"),
                fingerprint=target.fingerprint,
            )

        return Decision(
            action=Action.OPEN_APP,
            platform=target.platform,
            title=f"已识别为{name}登录二维码",
            detail=(f"本机已安装{name}，正在唤起 App。{name}没有对外提供扫一扫快捷入口，"
                    f"请在{name}内点击{hint}扫描 PC 屏幕，并手动确认登录。"),
            deep_link=rule.deep_link,  # 只来自规则表常量
            fallback_url=rule.password_login_url,
            android_packages=rule.android_packages,
            steps=(f"唤起{name}", f"点击「{hint}」", "扫描 PC 屏幕上的二维码",
                   f"在{name}内手动点击「确认登录」"),
            fingerprint=target.fingerprint,
        )

    if status is InstallStatus.NOT_INSTALLED:
        detail = f"本机未检测到{name}。可以下载官方 App 后重试扫码"
        detail += "，或直接改用账号密码登录。" if rule.password_login_url else "。"
        return Decision(
            action=Action.PROMPT_DOWNLOAD,
            platform=target.platform,
            title=f"未安装{name}",
            detail=detail,
            store_url=rule.store_url_for(environment),
            fallback_url=rule.password_login_url,
            android_packages=rule.android_packages,
            steps=(f"下载并安装官方{name}（仅从应用商店或官网下载）",
                   f"打开{name}并进入「{hint}」",
                   "扫描 PC 屏幕上的二维码并确认登录"),
            fingerprint=target.fingerprint,
        )

    # UNKNOWN：检测能力受限，交给用户自己判断
    return Decision(
        action=Action.MANUAL_SCAN,
        platform=target.platform,
        title=f"已识别为{name}登录二维码",
        detail=(f"无法确认本机是否安装了{name}（系统限制了应用可见性）。"
                f"如果你装过，直接打开{name}扫一扫即可；没有的话请从官方渠道安装。"),
        store_url=rule.store_url_for(environment),
        fallback_url=rule.password_login_url,
        android_packages=rule.android_packages,
        steps=(f"手动打开{name}", f"进入「{hint}」", "扫描 PC 屏幕上的二维码并确认登录"),
        fingerprint=target.fingerprint,
    )


def decide_for_rejection(rejected: QrRejected) -> Decision:
    if rejected.reason is RejectionReason.UNSAFE_SCHEME:
        return Decision(
            action=Action.REJECT,
            platform=Platform.UNKNOWN,
            title="这个二维码不安全，已停止处理",
            detail=rejected.message + "。请不要在来路不明的页面上扫码登录。",
            rejection_reason=rejected.reason,
            fingerprint=rejected.fingerprint,
        )
    if rejected.reason is RejectionReason.EMPTY:
        return Decision(
            action=Action.REJECT, platform=Platform.UNKNOWN,
            title="没有扫到内容", detail="请重新对准 PC 屏幕上的二维码。",
            rejection_reason=rejected.reason, fingerprint=rejected.fingerprint,
        )
    return Decision(
        action=Action.MANUAL_SCAN,
        platform=Platform.UNKNOWN,
        title="暂不支持这个二维码",
        detail=(rejected.message + "。你可以手动打开对应 App 扫码，"
                "或在该网站改用账号密码登录。"),
        steps=("确认 PC 页面上写明的登录平台", "手动打开对应官方 App",
               "使用 App 内的扫一扫完成登录"),
        rejection_reason=rejected.reason,
        fingerprint=rejected.fingerprint,
    )


def decide_for_password_login(platform: Platform, rules: Optional[RuleSet] = None) -> Decision:
    """用户在 UI 上主动选择"改用账号密码登录"。"""
    rules = rules or RuleSet.default()
    rule = rules.rule_for(platform)
    if rule is None or not rule.password_login_url:
        return Decision(
            action=Action.MANUAL_SCAN,
            platform=platform,
            title=f"{platform.display_name}没有账号密码登录入口",
            detail=(rule.password_login_note if rule and rule.password_login_note
                    else "该平台仅支持扫码登录，请回到 PC 页面重新获取二维码。"),
        )
    return Decision(
        action=Action.PASSWORD_LOGIN,
        platform=platform,
        title=f"改用{rule.display_name}账号密码登录",
        detail=rule.password_login_note or "将在浏览器中打开官方登录页。",
        fallback_url=rule.password_login_url,
        android_packages=rule.android_packages,
        steps=("在浏览器中打开官方登录页", "输入账号密码并完成二次验证"),
    )
