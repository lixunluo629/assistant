#!/usr/bin/env python3
"""跨端一致性检查：规则表 ↔ Android 清单 ↔ 两侧枚举。

这些检查对应 docs/security.md 里的威胁项，任何一条失败都可能造成线上事故：

  A. 平台包名是否都在 AndroidManifest 的 <queries> 里声明
     —— 漏一条，Android 11+ 就会把"已安装"误判成"未安装"（T7）
  B. 唤醒用的 scheme 是否都在 <queries> 里声明
     —— 漏一条，queryIntentActivities 永远返回空
  C. deep_link 是否都是常量（不含 { } % $ 等模板占位）
     —— 一旦允许拼接，就等于把跳转目标交给二维码作者（T4）
  D. Python 与 Kotlin 的枚举是否一一对应
     —— 不对应会出现"PC 上判 reject、手机上照跳"的错位
  E. 规则表自身矛盾检查：denylist 不应命中任何平台的 domains

用法：
    python3 tools/check_consistency.py
退出码 0 表示全部通过。
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "core" / "platform_rules.json"
MANIFEST = ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
PY_MODELS = ROOT / "core" / "qrlogin" / "models.py"
KT_MODELS = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "szpdc" / "assistant" / "feature" / "scan" / "Models.kt"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"
# 这些参数一旦出现在扫一扫深链里，就等于让官方 App 代替用户完成"扫码上报"，
# 属于红线上自动化授权，必须永久禁止（实测微信/支付宝都提供了这类路由，我们刻意不用）。
FORBIDDEN_QR_PAYLOAD_PARAMS = (
    "qrcodetext", "qrcode=", "handlescanresult", "scanresult",
    "result=", "data=", "url=", "content=", "ticket=", "code=",
)

FORBIDDEN_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}
TEMPLATE_MARKERS = ("{", "}", "%s", "%d", "$", "+")

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def load_rules() -> dict:
    return json.loads(RULES.read_text(encoding="utf-8"))


def check_queries(rules: dict) -> None:
    """A + B：清单里的包名与 scheme 是否覆盖规则表。"""
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    declared_packages: set[str] = set()
    declared_schemes: set[str] = set()
    for queries in root.findall("queries"):
        for pkg in queries.findall("package"):
            name = pkg.get(ANDROID_NS + "name")
            if name:
                declared_packages.add(name)
        for intent in queries.findall("intent"):
            for data in intent.findall("data"):
                scheme = data.get(ANDROID_NS + "scheme")
                if scheme:
                    declared_schemes.add(scheme.lower())

    if not declared_packages:
        fail("AndroidManifest.xml 的 <queries> 里没有声明任何 <package>，Android 11+ 安装检测会全部失效")

    for platform in rules["platforms"]:
        pid = platform["id"]
        for package in platform.get("android_packages", []):
            if package not in declared_packages:
                fail(f"[{pid}] 规则表声明了包名 {package}，但 AndroidManifest 的 <queries> 里没有它"
                     "（会导致安装检测误判为未安装）")

        links = [platform.get("deep_link") or ""]
        links += [s + "://" for s in platform.get("extra_schemes", [])]
        links += (platform.get("scan_entry") or {}).get("deep_links", [])
        for link in links:
            if not link or ":" not in link:
                continue
            scheme = link.split(":", 1)[0].lower()
            if scheme not in declared_schemes:
                fail(f"[{pid}] 规则表使用 scheme {scheme}://（{link}），"
                     "但 AndroidManifest 的 <queries> 里没有声明该 scheme")

    notes.append(f"清单声明：{len(declared_packages)} 个包名、{len(declared_schemes)} 个 scheme")


def check_deep_links_are_constants(rules: dict) -> None:
    """C：深链必须是常量，不允许任何模板占位。"""
    for platform in rules["platforms"]:
        deep_link = platform.get("deep_link")
        if deep_link is None:
            continue
        if not deep_link.endswith("://"):
            fail(f"[{platform['id']}] deep_link 应为纯 scheme（如 weixin://），当前值：{deep_link}")
        if any(marker in deep_link for marker in TEMPLATE_MARKERS):
            fail(f"[{platform['id']}] deep_link 含模板占位，疑似允许拼接二维码内容：{deep_link}")
        if platform.get("deep_link_kind") != "app_launch_only":
            fail(f"[{platform['id']}] deep_link_kind 必须是 app_launch_only，"
                 "任何'带参数直达业务页'的用法都可能被用于自动授权")

        # 扫一扫直达也必须是常量：一旦允许把二维码内容拼进扫一扫深链，
        # 就等于让官方 App 代替用户完成"扫码上报"，越过了自动化授权的红线。
        # 平台自己的合法 scheme 集合：deep_link 的 scheme 加上 extra_schemes
        own_schemes = {(platform.get("deep_link") or "").split(":", 1)[0].lower()}
        own_schemes |= {s.lower() for s in platform.get("extra_schemes", [])}
        own_schemes.discard("")
        for link in (platform.get("scan_entry") or {}).get("deep_links", []):
            if any(marker in link for marker in TEMPLATE_MARKERS):
                fail(f"[{platform['id']}] scan_entry.deep_links 含模板占位：{link}")
            # 扫一扫深链必须落在本平台自己的 scheme 上，否则可能把用户送到别的 App
            link_scheme = link.split(":", 1)[0].lower()
            if own_schemes and link_scheme not in own_schemes:
                fail(f"[{platform['id']}] scan_entry.deep_links 的 scheme（{link_scheme}）"
                     f"不在本平台合法 scheme 里（{sorted(own_schemes)}）：{link}")
            if "://" not in link or len(link.split("://", 1)[1]) == 0:
                fail(f"[{platform['id']}] scan_entry.deep_links 只写了裸 scheme，无法直达扫一扫：{link}")
            lowered = link.lower()
            for param in FORBIDDEN_QR_PAYLOAD_PARAMS:
                if param in lowered:
                    fail(f"[{platform['id']}] scan_entry.deep_links 含「代扫」类参数 {param}：{link}"
                         "（会把二维码内容交给官方 App 直接处理，越过自动化授权红线）")

        # 显式 Activity 类名同样必须是写死的常量
        for cls in (platform.get("scan_entry") or {}).get("activity_classes", []):
            if not re.fullmatch(r"[a-z][\w]*(\.[\w]+)+", cls):
                fail(f"[{platform['id']}] scan_entry.activity_classes 不是合法类名：{cls}")
            if any(marker in cls for marker in TEMPLATE_MARKERS):
                fail(f"[{platform['id']}] scan_entry.activity_classes 含模板占位：{cls}")

    for url_field in ("store_urls", "password_login_url"):
        for platform in rules["platforms"]:
            value = platform.get(url_field)
            values = value.values() if isinstance(value, dict) else [value]
            for item in values:
                if item and not str(item).startswith(("http://", "https://", "market://")):
                    fail(f"[{platform['id']}] {url_field} 出现非白名单协议：{item}")


def _enum_from_python(path: Path, name: str) -> list[str]:
    """只取 class X(str, Enum): 到第一个回代之前的成员名，避免跨类误采。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    members: list[str] = []
    inside = False
    for line in lines:
        if not inside:
            if re.match(rf"class {name}\(str, Enum\):", line):
                inside = True
            continue
        if not line.strip():
            continue
        if not line.startswith("    "):  # 回代到顶层：类结束
            break
        match = re.match(r"    ([A-Z][A-Z0-9_]*) = ", line)
        if match:
            members.append(match.group(1))
    return members


def _enum_from_kotlin(path: Path, name: str) -> list[str]:
    """按逗号切分枚举体，只取每段开头的常量名（带构造参数也不会误采字符串字面量）。"""
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"enum class {name}\b[^{{]*\{{(.*?)\}}", text, re.S)
    if not match:
        return []
    body = match.group(1).split(";")[0]
    members: list[str] = []
    for chunk in body.split(","):
        found = re.match(r"\s*([A-Z][A-Z0-9_]*)\b", chunk)
        if found:
            members.append(found.group(1))
    return members


def check_enums() -> None:
    """D：两端枚举必须一一对应。"""
    for enum_name in ("Platform", "InstallStatus", "Action", "RejectionReason"):
        py_values = _enum_from_python(PY_MODELS, enum_name)
        kt_values = _enum_from_kotlin(KT_MODELS, enum_name)
        if not py_values:
            fail(f"无法从 models.py 解析枚举 {enum_name}")
            continue
        if not kt_values:
            fail(f"无法从 Models.kt 解析枚举 {enum_name}")
            continue
        if py_values != kt_values:
            fail(f"枚举 {enum_name} 两端不一致：\n    python: {py_values}\n    kotlin: {kt_values}")
        else:
            notes.append(f"枚举 {enum_name} 一致（{len(py_values)} 项）")


def check_denylist_self_consistency(rules: dict) -> None:
    """E：denylist 不应把平台自己的域名干掉，否则该平台永远识别不出来。"""
    for platform in rules["platforms"]:
        domains = set(platform.get("domains", []))
        keywords = set(platform.get("url_keywords", []))
        for denied in platform.get("host_denylist", []):
            if denied in domains or denied in keywords:
                fail(f"[{platform['id']}] denylist 里的 {denied} 同时出现在 domains/url_keywords，自相矛盾")

    # 交叉检查：A 平台的 denylist 不应是 B 平台的登录域名（会误伤）
    all_login_domains = {
        d: p["id"] for p in rules["platforms"] for d in p.get("url_keywords", [])
    }
    for platform in rules["platforms"]:
        for denied in platform.get("host_denylist", []):
            owner = all_login_domains.get(denied)
            if owner and owner != platform["id"]:
                fail(f"[{platform['id']}] denylist 里的 {denied} 是 {owner} 的登录域名，会误伤该平台")


def _camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def _decision_fields_python() -> set[str]:
    inside = False
    fields: set[str] = set()
    for line in PY_MODELS.read_text(encoding="utf-8").splitlines():
        if not inside:
            if line.startswith("class Decision:"):
                inside = True
            continue
        if line and not line.startswith("    "):
            break
        found = re.match(r"    ([a-z_][a-z0-9_]*): ", line)
        if found:
            fields.add(found.group(1))
    return fields


def _decision_fields_kotlin() -> set[str]:
    text = KT_MODELS.read_text(encoding="utf-8")
    body = re.search(r"data class Decision\((.*?)\n\)", text, re.S)
    if not body:
        return set()
    return {_camel_to_snake(name) for name in re.findall(r"\bval ([a-zA-Z][a-zA-Z0-9_]*):", body.group(1))}


def check_decision_fields_alignment() -> None:
    """G：Decision 的字段两端必须一致。

    两端字段错位是最阴的 bug：比如 Android 侧没有 android_packages，
    就只能继续用不可靠的裸 scheme 启动，而 Python 侧测试看不出任何问题。
    """
    py_fields = _decision_fields_python()
    kt_fields = _decision_fields_kotlin()
    if not py_fields:
        fail("无法从 models.py 解析 Decision 字段")
    if not kt_fields:
        fail("无法从 Models.kt 解析 Decision 字段")
    if py_fields != kt_fields:
        fail("Decision 字段两端不一致：\n    python: " + str(sorted(py_fields)) +
             "\n    kotlin: " + str(sorted(kt_fields)))
    else:
        notes.append(f"Decision 字段一致（{len(py_fields)} 项）")


def check_kotlin_parser_semantics() -> None:
    """弱检查：确认 Kotlin 侧仍保留关键安全分支（防止被重构掉）。"""
    core_dir = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "szpdc" / "assistant" / "feature" / "scan")
    parser = (core_dir / "QrParser.kt").read_text(encoding="utf-8").replace(" ", "")
    rules = (core_dir / "PlatformRules.kt").read_text(encoding="utf-8").replace(" ", "")
    router = (core_dir / "LoginRouter.kt").read_text(encoding="utf-8").replace(" ", "")

    for content, needle, why in [
        (parser, "UNSAFE_SCHEME", "非 http(s) 协议闸门（防 scheme 注入）"),
        (parser, "loose=true", "宽松匹配标记（loose 结果禁止自动跳转）"),
        (parser, "BARE_DOMAIN_RE.containsMatchIn(text)||LOOSE_CONTEXT_RE.containsMatchIn(text)",
         "宽松匹配的语境闸门"),
        (rules, 'host==domain||host.endsWith("."+domain)',
         "最长后缀匹配（防 weixin.qq.com.evil.example）"),
        (rules, "isDenied(host)", "denylist 一票否决"),
        (router, "deepLink=rule.deepLink", "deepLink 直接取规则表常量，无拼接"),
        (router, "target.loose", "loose 分支存在"),
        (router, "androidPackages=rule.androidPackages", "Decision 带上官方包名（用于可靠启动 App）"),
        (router, "scanDeepLinks=rule.scanDeepLinks", "Decision 带上扫一扫直达深链"),
    ]:
        if needle.replace(" ", "") not in content:
            fail(f"Kotlin 侧缺少关键分支：{why}")


def check_network_permission_is_removed() -> None:
    """F：网络权限必须显式移除。

    ML Kit 依赖库会声明 INTERNET / ACCESS_NETWORK_STATE，manifest merger 会合并进来，
    所以"我没写"不等于"APK 里没有"。一旦被合并，"绝不上传二维码"的系统级保证就没了。
    """
    root = ET.parse(MANIFEST).getroot()
    seen: set[str] = set()
    for permission in root.findall("uses-permission"):
        name = permission.get(ANDROID_NS + "name")
        if name not in FORBIDDEN_PERMISSIONS:
            continue
        seen.add(name)
        if permission.get(TOOLS_NS + "node") != "remove":
            fail(f"清单声明了 {name} 却没有 tools:node=\"remove\"："
                 "依赖库合并后会真的获得网络权限，破坏\u201c绝不上传二维码\u201d的承诺")

    if seen:
        notes.append(f"已显式移除网络权限：{'、'.join(sorted(seen))}")
    else:
        notes.append("清单未出现网络权限声明")

    # ElementTree 不把 xmlns:tools 当作普通属性，因此用文本方式校验命名空间声明
    manifest_text = MANIFEST.read_text(encoding="utf-8")
    if "tools:node" in manifest_text and "xmlns:tools=" not in manifest_text:
        fail("AndroidManifest 使用了 tools:node 但未声明 xmlns:tools 命名空间")


    # 启动顺序：扫一扫深链必须排在包名启动之前，否则"直达扫一扫"会被主界面顶掉
    main_activity = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "szpdc" / "assistant" / "feature" / "scan" / "ScanActivity.kt").read_text(encoding="utf-8").replace(" ", "")
    pos_scan = main_activity.find("decision.scanDeepLinks")
    pos_pkg = main_activity.find("getLaunchIntentForPackage")
    if pos_scan < 0:
        fail("MainActivity 丢失了扫一扫直达逻辑（decision.scanDeepLinks）")
    elif pos_pkg >= 0 and pos_scan > pos_pkg:
        fail("MainActivity 的启动顺序被改动：扫一扫深链必须先于包名启动尝试")


def check_tool_registry_consistency() -> None:
    """H：功能注册表里的每个功能都必须在 AndroidManifest 注册，否则点击即崩。

    这是"加新功能"时最容易漏的一步：只在 ToolRegistry 加一行、忘了写 <activity>，
    编译能过、装上也看不出来，直到用户点进去才崩。
    """
    registry_path = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "szpdc"
                     / "assistant" / "feature" / "ToolRegistry.kt")
    if not registry_path.exists():
        fail(f"找不到功能注册表：{registry_path.relative_to(ROOT)}")
        return

    registry = registry_path.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    # 先把注释去掉，否则文件里的"示例代码"会被当成真功能
    code = re.sub(r"/\*.*?\*/", "", registry, flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)

    imports = {name.rsplit(".", 1)[-1]: name
               for name in re.findall(r"^\s*import (com\.szpdc\.assistant[\w.]*)", code, re.M)}
    entries = re.findall(r"entry\s*=\s*(\w+)::class\.java", code)
    if not entries:
        fail("ToolRegistry 里没有解析到任何功能项（是不是全被注释掉了？）")

    for cls in entries:
        fqn = imports.get(cls)
        if fqn is None:
            fail(f"ToolRegistry 里的 {cls} 没有对应 import，无法确认全限定名")
            continue
        relative = "." + fqn[len("com.szpdc.assistant"):].lstrip(".")
        if f'android:name="{relative}"' not in manifest:
            fail(f"功能 {cls}（{relative}）已在 ToolRegistry 注册，但 AndroidManifest 没声明 —— 点进去会崩")

    if 'android:name=".HomeActivity"' not in manifest:
        fail("AndroidManifest 缺少首页 .HomeActivity")

    notes.append(f"功能注册表与清单一致（{len(entries)} 个功能）")


def main() -> int:
    rules = load_rules()
    check_queries(rules)
    check_network_permission_is_removed()
    check_deep_links_are_constants(rules)
    check_enums()
    check_decision_fields_alignment()
    check_denylist_self_consistency(rules)
    check_kotlin_parser_semantics()
    check_tool_registry_consistency()

    print(f"规则表版本：{rules['version']}")
    for note in notes:
        print(f"  ✓ {note}")
    if failures:
        print("\n发现不一致：")
        for item in failures:
            print(f"  ✗ {item}")
        return 1
    print("\n跨端一致性检查全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
