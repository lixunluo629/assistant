"""扫码登录助手核心库。

用法：
    from qrlogin import route, DesktopProbe
    decision = route("https://login.weixin.qq.com/qrcode/abc", DesktopProbe())
    print(decision.action, decision.title)
"""

from .models import (Action, Decision, InstallStatus, LoginTarget, Platform,
                     QrRejected, RejectionReason)
from .parser import parse
from .rules import PlatformRule, RuleSet
from .router import (decide_for_password_login, decide_for_rejection,
                     decide_for_target, route)

__version__ = "1.0.0"

__all__ = [
    "Action", "Decision", "InstallStatus", "LoginTarget", "Platform",
    "QrRejected", "RejectionReason", "parse", "PlatformRule", "RuleSet",
    "route", "decide_for_target", "decide_for_rejection",
    "decide_for_password_login", "__version__",
]
