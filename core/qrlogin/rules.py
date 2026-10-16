"""平台识别规则表：域名/关键词 -> 平台 -> 官方入口。

规则数据放在 platform_rules.json，核心库与 Android 端共用同一份文件，
运营同学改规则不用碰代码。本模块只负责加载与匹配。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from .models import Platform

__all__ = ["PlatformRule", "RuleSet", "DEFAULT_RULES_PATH"]

DEFAULT_RULES_PATH = Path(__file__).resolve().parent.parent / "platform_rules.json"


@dataclass(frozen=True)
class PlatformRule:
    platform: Platform
    display_name: str
    domains: Tuple[str, ...] = ()
    url_keywords: Tuple[str, ...] = ()
    text_aliases: Tuple[str, ...] = ()
    host_denylist: Tuple[str, ...] = ()
    deep_link: Optional[str] = None
    deep_link_kind: str = "app_launch_only"
    extra_schemes: Tuple[str, ...] = ()
    """该平台的其它合法 scheme（如 QQ 用 mqqapi:// 调 API、mqq:// 打开主界面）。"""
    scan_deep_links: Tuple[str, ...] = ()
    """直达「扫一扫」的候选深链，按优先级排列。

    来源是对官方 APK 的 AndroidManifest 实测（见规则表里的 scan_entry.source）。
    仍只允许常量，禁止拼接二维码内容 —— 助手只负责把用户带到扫一扫界面，
    扫到什么、确不确认登录，全部由用户本人在官方 App 里完成。
    为空表示该平台未对外暴露扫一扫入口，只能打开主界面。
    """
    scan_activity_classes: Tuple[str, ...] = ()
    """直达扫一扫的显式 Activity 类名候选（规则表常量）。

    有些平台（实测百度）把扫码 Activity 声明为 exported=true，却没有 scheme filter，
    只能靠 setClassName 显式启动。仍然是白名单常量，禁止拼接任何二维码内容。
    """
    scan_entry_note: str = ""
    android_packages: Tuple[str, ...] = ()
    ios_bundles: Tuple[str, ...] = ()
    desktop_programs: Dict[str, Tuple[str, ...]] = field(default_factory=dict)
    store_urls: Dict[str, str] = field(default_factory=dict)
    password_login_url: Optional[str] = None
    password_login_note: str = ""
    scan_target_hint: str = "扫一扫"
    notes: str = ""

    @property
    def supports_direct_scan(self) -> bool:
        """是否可直接把用户送到扫一扫界面（深链或显式类名任一可用即可）。"""
        return bool(self.scan_deep_links or self.scan_activity_classes)

    def store_url_for(self, environment: str) -> Optional[str]:
        """environment: android / ios / web。找不到就退回 web。"""
        return self.store_urls.get(environment) or self.store_urls.get("web")

    def desktop_programs_for(self, os_name: str) -> Tuple[str, ...]:
        return tuple(self.desktop_programs.get(os_name, ()))

    @classmethod
    def from_dict(cls, data: dict) -> "PlatformRule":
        return cls(
            platform=Platform(data["id"]),
            display_name=data.get("display_name", data["id"]),
            domains=tuple(d.lower() for d in data.get("domains", ())),
            url_keywords=tuple(k.lower() for k in data.get("url_keywords", ())),
            text_aliases=tuple(a.lower() for a in data.get("text_aliases", ())),
            host_denylist=tuple(d.lower() for d in data.get("host_denylist", ())),
            deep_link=data.get("deep_link"),
            deep_link_kind=data.get("deep_link_kind", "app_launch_only"),
            extra_schemes=tuple(data.get("extra_schemes", ())),
            scan_deep_links=tuple((data.get("scan_entry") or {}).get("deep_links", ())),
            scan_activity_classes=tuple((data.get("scan_entry") or {}).get("activity_classes", ())),
            scan_entry_note=(data.get("scan_entry") or {}).get("note", ""),
            android_packages=tuple(data.get("android_packages", ())),
            ios_bundles=tuple(data.get("ios_bundles", ())),
            desktop_programs={k: tuple(v) for k, v in data.get("desktop_programs", {}).items()},
            store_urls=dict(data.get("store_urls", {})),
            password_login_url=data.get("password_login_url"),
            password_login_note=data.get("password_login_note", ""),
            scan_target_hint=data.get("scan_target_hint", "扫一扫"),
            notes=data.get("notes", ""),
        )


def normalize_host(host: str) -> str:
    """小写、去端口、去尾部点，避免 'Login.WeiXin.QQ.com:443.' 绕过匹配。"""
    host = (host or "").strip().lower()
    if not host:
        return ""
    if host.startswith("[") and "]" in host:  # IPv6 字面量
        return ""
    host = host.split("/", 1)[0]
    if host.count(":") == 1:
        host = host.split(":", 1)[0]
    return host.rstrip(".").strip()


def _host_matches(host: str, domain: str) -> bool:
    return host == domain or host.endswith("." + domain)


class RuleSet:
    """规则集合。匹配策略：最长后缀优先，denylist 一票否决。"""

    def __init__(self, rules: Sequence[PlatformRule], version: str = "unknown",
                 credential_params: Iterable[str] = ()) -> None:
        if not rules:
            raise ValueError("规则表不能为空")
        self.rules: Tuple[PlatformRule, ...] = tuple(rules)
        self.version = version
        self.credential_params = frozenset(p.lower() for p in credential_params)
        # 预展开 (domain, rule) 并按域名长度降序，保证最长后缀优先
        self._domain_index: Tuple[Tuple[str, PlatformRule], ...] = tuple(sorted(
            ((d, r) for r in self.rules for d in r.domains),
            key=lambda item: len(item[0]), reverse=True,
        ))
        self._denylist: Tuple[str, ...] = tuple(sorted(
            (d for r in self.rules for d in r.host_denylist),
            key=len, reverse=True,
        ))
        self._keyword_index: Tuple[Tuple[str, PlatformRule], ...] = tuple(sorted(
            ((k, r) for r in self.rules for k in r.url_keywords),
            key=lambda item: len(item[0]), reverse=True,
        ))
        # 中文别名（微信 / 钉钉 / QQ）只用于 loose 回退，优先级最低
        self._alias_index: Tuple[Tuple[str, PlatformRule], ...] = tuple(sorted(
            ((a, r) for r in self.rules for a in r.text_aliases),
            key=lambda item: len(item[0]), reverse=True,
        ))

    # ---- 加载 -------------------------------------------------------------
    @classmethod
    def from_json_file(cls, path: Path) -> "RuleSet":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        rules = [PlatformRule.from_dict(item) for item in data["platforms"]]
        return cls(rules, version=data.get("version", "unknown"),
                   credential_params=data.get("credential_params", ()))

    @classmethod
    def default(cls, path: Optional[Path] = None) -> "RuleSet":
        return cls.from_json_file(path or DEFAULT_RULES_PATH)

    # ---- 匹配 -------------------------------------------------------------
    def is_denied(self, host: str) -> bool:
        return any(_host_matches(host, d) for d in self._denylist)

    def match_host(self, host: str) -> Optional[Tuple[PlatformRule, str]]:
        """按最长后缀匹配 host，返回 (规则, 命中的域名)。"""
        host = normalize_host(host)
        if not host or self.is_denied(host):
            return None
        for domain, rule in self._domain_index:
            if _host_matches(host, domain):
                return rule, domain
        return None

    def match_text(self, text: str) -> Optional[Tuple[PlatformRule, str, str]]:
        """全文关键词回退匹配，返回 (规则, 命中关键词, 匹配层级)。

        匹配层级按可靠性递减：url_keyword -> domain_keyword -> text_alias。
        仅在没能解析出合法 URL 时使用。命中的前缀若落在 denylist 上则跳过。
        """
        low = (text or "").lower()
        if not low:
            return None
        for keyword, rule in self._keyword_index:
            if keyword in low and not self.is_denied(keyword):
                return rule, keyword, "url_keyword"
        for domain, rule in self._domain_index:
            if domain in low and not self.is_denied(domain):
                return rule, domain, "domain_keyword"
        for alias, rule in self._alias_index:
            if alias in low and not self.is_denied(alias):
                return rule, alias, "text_alias"
        return None

    def rule_for(self, platform: Platform) -> Optional[PlatformRule]:
        for rule in self.rules:
            if rule.platform is platform:
                return rule
        return None

    def has_credential_param(self, keys: Iterable[str]) -> bool:
        return any(k.lower() in self.credential_params for k in keys)
