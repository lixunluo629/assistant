"""二维码内容解析：只做识别，不做任何网络请求，也不保留原文。

解析流水线：
    raw text
      -> 清洗不可见字符 / 全角冒号
      -> 判定是否 http(s)（自定义 scheme 一律拒绝）
      -> 取第一个 URL 的 host，做最长后缀域名匹配
      -> 没有 URL 时退化为全文关键词匹配（loose=True，禁用自动跳转）
      -> 记录 has_credential 布尔值（凭据值本身立即丢弃）
"""

from __future__ import annotations

import re
import unicodedata
from typing import List, Optional, Tuple
from urllib.parse import parse_qsl, urlsplit

from .models import (LoginTarget, Platform, QrRejected, RejectionReason,
                     fingerprint_of)
from .rules import RuleSet

__all__ = ["parse", "extract_urls", "URL_RE"]

URL_RE = re.compile(r"https?://[^\s<>\"'\u200b-\u200d\ufeff]+", re.IGNORECASE)
SCHEME_RE = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.\-]*):")
# 零宽字符 + 方向控制符 + BOM：肉眼看不见，却能让"包含域名"的校验失效
_INVISIBLE_RE = re.compile(r"[\u200b-\u200f\u202a-\u202e\u2060\ufeff]")
_TRAILING_JUNK = "\u3002\uff0c\u3001\uff1b\uff1a.,;:'\"\u201d\u2019)\uff09]>}\uff3d"

# 宽松文本里可能出现的"域名式"线索，例如 wx.qq.com 前面没有 scheme
_BARE_DOMAIN_RE = re.compile(r"\b((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})\b", re.IGNORECASE)

# 宽松匹配的语境闸门：只有看起来确实在说"扫码/登录"时，才允许走文字线索回退
_LOOSE_CONTEXT_RE = re.compile(r"扫码|扫一扫|二维码|登录|登入|sign\s*in|login|scan", re.IGNORECASE)


def clean(raw: str) -> str:
    """清洗二维码文本：去不可见字符、做 NFKC 归一化、去首尾空白。"""
    if not isinstance(raw, str):
        raise QrRejected(RejectionReason.MALFORMED, "二维码内容不是文本")
    text = _INVISIBLE_RE.sub("", raw)
    text = unicodedata.normalize("NFKC", text).strip()
    return text


def extract_urls(text: str) -> List[str]:
    urls = []
    for match in URL_RE.finditer(text):
        url = match.group(0).rstrip(_TRAILING_JUNK)
        if url:
            urls.append(url)
    return urls


def _credential_keys(url: str) -> Tuple[List[str], List[str]]:
    parts = urlsplit(url)
    keys = [k for k, _ in parse_qsl(parts.query, keep_blank_values=True)]
    keys += [k for k, _ in parse_qsl(parts.fragment, keep_blank_values=True)]
    return keys, [parts.scheme, parts.netloc]


def parse(raw: str, rules: Optional[RuleSet] = None) -> LoginTarget:
    """解析二维码内容，返回 LoginTarget。无法安全处理时抛 QrRejected。

    注意：返回值里不含原文，也不含任何票据值。
    """
    rules = rules or RuleSet.default()
    text = clean(raw)
    fp = fingerprint_of(text)

    if not text:
        raise QrRejected(RejectionReason.EMPTY, "二维码内容为空", fp)

    # 1) 顶部就是自定义 scheme：可能是伪造的 weixin://、mqq:// 跳转，直接拒绝
    scheme_match = SCHEME_RE.match(text)
    if scheme_match and scheme_match.group(1).lower() not in ("http", "https"):
        raise QrRejected(
            RejectionReason.UNSAFE_SCHEME,
            f"二维码内容使用了非网页协议 {scheme_match.group(1)}://，登录二维码不应是这种形式，已拒绝处理",
            fp,
        )

    urls = extract_urls(text)

    # 2) 有合法 URL：按 host 做最长后缀匹配
    if urls:
        for url in urls:
            parts = urlsplit(url)
            if parts.scheme.lower() not in ("http", "https"):
                continue
            hit = rules.match_host(parts.netloc)
            if hit is None:
                continue
            rule, domain = hit
            keys, _ = _credential_keys(url)
            return LoginTarget(
                platform=rule.platform,
                host=domain,
                matched_by="host",
                loose=False,
                has_credential=rules.has_credential_param(keys),
                fingerprint=fp,
            )
        # URL 存在但没有命中白名单：直接拒绝，绝不回退到文字线索匹配。
        # 否则 https://evil.example.com/?next=wx.qq.com 这类伪装会被宽松放行。
        raise QrRejected(
            RejectionReason.UNKNOWN_PLATFORM,
            "二维码指向的域名不在支持的平台白名单内，已停止处理",
            fp,
        )

    # 3) 没有 URL：只在全文里找域名式线索或平台别名，标记为 loose。
    #    宽松匹配不自动跳转，但仍要求出现登录语境词，避免把普通文案误认成登录码。
    if _BARE_DOMAIN_RE.search(text) or _LOOSE_CONTEXT_RE.search(text):
        loose_hit = rules.match_text(text)
        if loose_hit is not None:
            rule, keyword, how = loose_hit
            return LoginTarget(
                platform=rule.platform,
                host=keyword,
                matched_by=how,
                loose=True,
                has_credential=False,
                fingerprint=fp,
            )

    raise QrRejected(
        RejectionReason.UNKNOWN_PLATFORM,
        "无法从二维码内容识别出支持的平台",
        fp,
    )
