"""本机 App 安装检测。

三态语义很重要：查不到 ≠ 没安装。Android 11+ 的包可见性限制、
iOS 的 canOpenURL 白名单、桌面端 which 找不到自定义安装路径，
都会让检测能力受限，此时必须返回 UNKNOWN 而不是 NOT_INSTALLED。
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path
from typing import Dict, Iterable, Optional, Sequence

from .models import InstallStatus, Platform
from .rules import PlatformRule

__all__ = ["AppProbe", "StaticProbe", "DesktopProbe", "AndroidProbe", "default_probe"]

# 桌面端常见的"不在 PATH 里"的安装位置（仅做 best-effort 探测）
_DESKTOP_HINTS: Dict[str, Sequence[str]] = {
    "linux": [
        "/opt/{name}/{name}",
        "/usr/bin/{name}",
        "/usr/local/bin/{name}",
        "/var/lib/flatpak/exports/bin/{name}",
        "/snap/bin/{name}",
    ],
    "darwin": [
        "/Applications/{name}.app",
        "~/Applications/{name}.app",
    ],
    "windows": [
        r"C:\\Program Files\\{name}",
        r"C:\\Program Files (x86)\\{name}",
    ],
}


class AppProbe:
    """安装检测适配器接口。"""

    environment = "unknown"

    def is_installed(self, rule: PlatformRule) -> InstallStatus:  # pragma: no cover - 接口
        raise NotImplementedError

    def check_all(self, rules: Iterable[PlatformRule]) -> Dict[Platform, InstallStatus]:
        return {rule.platform: self.is_installed(rule) for rule in rules}


class StaticProbe(AppProbe):
    """测试与演示用：直接给出预设结果。"""

    environment = "test"

    def __init__(self, installed: Iterable[Platform] = (), environment: str = "test",
                 unknown: Iterable[Platform] = ()) -> None:
        self._installed = set(installed)
        self._unknown = set(unknown)
        self.environment = environment

    def is_installed(self, rule: PlatformRule) -> InstallStatus:
        if rule.platform in self._unknown:
            return InstallStatus.UNKNOWN
        if rule.platform in self._installed:
            return InstallStatus.INSTALLED
        return InstallStatus.NOT_INSTALLED


class DesktopProbe(AppProbe):
    """桌面端 best-effort 探测：PATH + 常见安装目录。

    找不到只能说明"没在常见位置发现"，不能断言未安装，因此返回 NOT_INSTALLED
    仅当我们确实探测过且无发现；探测受限时用 UNKNOWN。
    """

    def __init__(self, os_name: Optional[str] = None, hints: Optional[Dict[str, Sequence[str]]] = None) -> None:
        self.os_name = os_name or _current_os()
        self.hints = hints or _DESKTOP_HINTS
        self.environment = self.os_name

    def is_installed(self, rule: PlatformRule) -> InstallStatus:
        programs = rule.desktop_programs_for(self.os_name)
        if not programs:
            return InstallStatus.UNKNOWN
        for name in programs:
            if shutil.which(name):
                return InstallStatus.INSTALLED
            for pattern in self.hints.get(self.os_name, ()):
                candidate = Path(pattern.format(name=name)).expanduser()
                if candidate.exists():
                    return InstallStatus.INSTALLED
        return InstallStatus.NOT_INSTALLED


class AndroidProbe(AppProbe):
    """Android 端占位实现：真实检测在 Kotlin 层（PackageManager）。

    保留这个类是为了让核心库的调用方拿到一致的接口与语义说明：
    - 必须在 AndroidManifest 的 <queries> 里声明目标包名，否则 Android 11+
      会直接抛 NameNotFoundException，此时应返回 UNKNOWN；
    - 检测到包名不等于 App 可用（可能被禁用、多用户隔离），仍以 UNKNOWN 保守处理。
    """

    environment = "android"

    def __init__(self, package_lookup=None) -> None:
        self._lookup = package_lookup

    def is_installed(self, rule: PlatformRule) -> InstallStatus:
        if self._lookup is None or not rule.android_packages:
            return InstallStatus.UNKNOWN
        try:
            found = self._lookup(list(rule.android_packages))
        except Exception:  # noqa: BLE001 - 平台层异常一律视为检测受限
            return InstallStatus.UNKNOWN
        if found is None:
            return InstallStatus.UNKNOWN
        return InstallStatus.INSTALLED if found else InstallStatus.NOT_INSTALLED


def _current_os() -> str:
    if sys.platform.startswith("linux"):
        return "linux"
    if sys.platform == "darwin":
        return "darwin"
    if sys.platform.startswith("win"):
        return "windows"
    return "unknown"


def default_probe() -> AppProbe:
    return DesktopProbe()
