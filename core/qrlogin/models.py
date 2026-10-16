"""扫码登录助手 —— 平台无关数据模型。

安全边界（贯穿整个项目，改动前请先读 docs/security.md）：
1. 本模块不保存二维码原文，也不保存其中的任何登录票据（ticket / uuid / scene ...）。
   解析完成后原文立即丢弃，只留下"这是哪个平台的登录二维码"这一结论。
2. LoginTarget 不携带 ticket 值，只带一个 has_credential 布尔标记。
3. Decision 只描述"接下来由用户本人做什么"，不含任何可用于自动授权登录的载荷。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, Tuple

__all__ = [
    "Platform",
    "InstallStatus",
    "Action",
    "RejectionReason",
    "LoginTarget",
    "Decision",
    "QrRejected",
]

# 指纹盐：让排障日志里的 fingerprint 无法被拿去做彩虹表反查（二维码内容熵低）
_FINGERPRINT_SALT = b"qr-login-assistant/v1"


class Platform(str, Enum):
    """助手能识别的平台。未知平台也显式建模，避免调用方用 None 兜底。"""

    WECHAT = "wechat"
    DINGTALK = "dingtalk"
    QQ = "qq"
    ALIPAY = "alipay"
    BILIBILI = "bilibili"
    WEWORK = "wework"
    BAIDU = "baidu"
    QUARK = "quark"
    XIAOHONGSHU = "xiaohongshu"
    DOUYIN = "douyin"
    STEAM = "steam"
    UNKNOWN = "unknown"

    @property
    def display_name(self) -> str:
        return _PLATFORM_NAMES[self]


_PLATFORM_NAMES = {
    Platform.WECHAT: "微信",
    Platform.DINGTALK: "钉钉",
    Platform.QQ: "QQ",
    Platform.ALIPAY: "支付宝",
    Platform.BILIBILI: "哔哩哔哩",
    Platform.WEWORK: "企业微信",
    Platform.BAIDU: "百度",
    Platform.QUARK: "夸克",
    Platform.XIAOHONGSHU: "小红书",
    Platform.DOUYIN: "抖音",
    Platform.STEAM: "Steam",
    Platform.UNKNOWN: "未知平台",
}


class InstallStatus(str, Enum):
    """本机 App 安装检测结果。三态是必需的：
    检测能力受限（Android 11+ 包可见性、权限缺失）时必须如实返回 UNKNOWN，
    不能把"查不到"当成"没安装"，否则会误导用户去装一个已经装了的 App。
    """

    INSTALLED = "installed"
    NOT_INSTALLED = "not_installed"
    UNKNOWN = "unknown"


class Action(str, Enum):
    """助手对一次扫码给出的唯一动作。"""

    OPEN_APP = "open_app"
    """已安装官方 App：唤起 App，由用户自己扫码 / 确认登录。"""

    PROMPT_DOWNLOAD = "prompt_download"
    """未安装：引导去应用商店或官网下载，并给出账号密码登录兜底入口。"""

    MANUAL_SCAN = "manual_scan"
    """无法确定平台或安装状态：只提示用户手动打开 App 扫码，不自动跳转。"""

    PASSWORD_LOGIN = "password_login"
    """用户放弃扫码：直接改用账号密码登录。"""

    REJECT = "reject"
    """内容不在白名单内或存在安全风险：直接拒绝，不做任何跳转。"""


class RejectionReason(str, Enum):
    EMPTY = "empty"
    UNSAFE_SCHEME = "unsafe_scheme"
    UNKNOWN_PLATFORM = "unknown_platform"
    MALFORMED = "malformed"


class QrRejected(Exception):
    """二维码内容无法安全处理时抛出。异常信息里不含原文。"""

    def __init__(self, reason: RejectionReason, message: str, fingerprint: str = "") -> None:
        super().__init__(message)
        self.reason = reason
        self.message = message
        self.fingerprint = fingerprint


def fingerprint_of(raw: str) -> str:
    """对二维码原文算一个不可逆的短指纹，仅用于排障时关联日志。"""
    digest = hashlib.sha256(_FINGERPRINT_SALT + raw.encode("utf-8", "ignore")).hexdigest()
    return digest[:12]


@dataclass(frozen=True)
class LoginTarget:
    """解析结论：这是哪个平台的登录二维码。不含任何凭据。"""

    platform: Platform
    host: str = ""
    """命中规则的域名（或 loose 匹配到的关键词），用于向用户说明依据。"""

    matched_by: str = ""
    """host 后缀匹配 = 'host'；全文关键词回退 = 'url_keyword' / 'domain_keyword'。"""

    loose: bool = False
    """True 表示没有解析出合法 URL、只靠文字线索猜的，禁止自动跳转。"""

    has_credential: bool = False
    """二维码里是否带一次性登录票据。只记布尔，绝不记值。"""

    fingerprint: str = ""
    """原文的加盐哈希前缀，仅用于日志关联。"""

    def log_line(self) -> str:
        return (
            f"platform={self.platform.value} host={self.host or '-'} "
            f"matched_by={self.matched_by or '-'} loose={self.loose} "
            f"has_credential={self.has_credential} fp={self.fingerprint or '-'}"
        )


@dataclass(frozen=True)
class Decision:
    """给 UI 的最终指令。UI 只需照着渲染，不需要再判断任何平台逻辑。"""

    action: Action
    platform: Platform
    title: str
    detail: str
    deep_link: Optional[str] = None
    store_url: Optional[str] = None
    fallback_url: Optional[str] = None
    android_packages: Tuple[str, ...] = field(default_factory=tuple)
    """官方 App 的包名，来自规则表常量。

    Android 端应当**优先用包名启动 App**，而不是 scheme：
    - 包名就是安装检测用的那一份，不会出现"检测到已安装、点按钮却唤不起"的自相矛盾；
    - 裸 scheme（如 dingtalk://）是否注册完全取决于各 App 自己，不受我们控制；
    - deep_link 降级为兜底。
    这里仍然只允许常量，禁止拼接二维码内容。
    """
    scan_deep_links: Tuple[str, ...] = field(default_factory=tuple)
    """直达「扫一扫」的候选深链（规则表常量，按优先级排列）。

    空元组表示该平台没有对外暴露扫一扫入口（实测微信不开放，详见规则表 scan_entry.source），
    此时 UI 应退化为"打开 App，请用户自己点扫一扫"。
    """
    scan_activity_classes: Tuple[str, ...] = field(default_factory=tuple)
    """直达扫一扫的显式 Activity 类名候选（规则表常量，按优先级排列）。

    对应平台把扫码界面声明为 exported=true 却没有 scheme 的情况，用 setClassName 启动。
    """
    auto_launch: bool = False
    """是否由 UI 在展示结果后自动执行主操作，不再要求用户点一下。

    OPEN_APP / PROMPT_DOWNLOAD 这类有明确目标的动作置 True：用户主动扫这个码，
    本身就是明确意图，不该再让他点一次按钮。
    REJECT / MANUAL_SCAN 恒为 False —— 安全警告必须让用户看到并由他自己决策。
    """
    steps: Tuple[str, ...] = field(default_factory=tuple)
    rejection_reason: Optional[RejectionReason] = None
    fingerprint: str = ""

    @property
    def is_safe_to_launch(self) -> bool:
        """只有白名单规则表里写死的深链才允许被系统调起。"""
        return self.action is Action.OPEN_APP and bool(self.deep_link)
