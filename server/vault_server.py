#!/usr/bin/env python3
"""小助手云端：加密令牌库、后台两步登录、应用版本发布。

手机接口：
  POST /v1/register   {username, authKey}
  POST /v1/login      {username, authKey} -> {ok, token}
  GET  /v1/vault      Authorization: Bearer
  POST /v1/vault      {iv, ciphertext}
  GET  /v1/app/update?versionCode=12

后台：/admin  先密码，再动态令牌。服务器解不开用户令牌。
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = "0.0.0.0"
PORT = int(os.environ.get("PORT", "8765"))
DATA_DIR = Path(os.environ.get("DATA_DIR", Path(__file__).resolve().parent))
DB_PATH = DATA_DIR / "vault.db"
APK_DIR = DATA_DIR / "apks"
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "http://8.138.34.69:8007").rstrip("/")
APP_PACKAGE = os.environ.get("APP_PACKAGE", "com.szpdc.assistant")
SESSION_TTL = 30 * 24 * 3600
ADMIN_SESSION_TTL = 12 * 3600
PENDING_TTL = 5 * 60
MAX_JSON = 1024 * 1024
MAX_APK = 80 * 1024 * 1024
PBKDF2_ROUNDS = 120_000
LOGIN_WINDOW = 10 * 60
LOGIN_LIMIT = 8

db_lock = threading.Lock()
rate_lock = threading.Lock()
failures: dict[str, list[float]] = {}


def connect() -> sqlite3.Connection:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    APK_DIR.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS users (
            username TEXT PRIMARY KEY,
            auth_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions (
            token TEXT PRIMARY KEY,
            username TEXT NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS vaults (
            username TEXT PRIMARY KEY,
            ciphertext TEXT NOT NULL,
            iv TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS admin (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            username TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            password_salt TEXT NOT NULL,
            totp_secret TEXT NOT NULL,
            totp_confirmed INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS admin_sessions (
            token TEXT PRIMARY KEY,
            csrf TEXT NOT NULL,
            scope TEXT NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS pending_logins (
            token TEXT PRIMARY KEY,
            expires_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS releases (
            version_code INTEGER PRIMARY KEY,
            version_name TEXT NOT NULL,
            changelog TEXT NOT NULL,
            sha256 TEXT NOT NULL DEFAULT '',
            force_update INTEGER NOT NULL DEFAULT 0,
            published INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL
        );
        """
    )
    return db


def ensure_admin(db: sqlite3.Connection) -> None:
    row = db.execute("SELECT id FROM admin WHERE id = 1").fetchone()
    if row is not None:
        return
    username = os.environ.get("ADMIN_USER", "admin").strip() or "admin"
    password = os.environ.get("ADMIN_PASSWORD", "")
    generated = False
    if len(password) < 8:
        password = secrets.token_urlsafe(18)
        generated = True
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ROUNDS)
    secret = base64_b32(secrets.token_bytes(20))
    db.execute(
        """INSERT INTO admin (id, username, password_hash, password_salt, totp_secret, totp_confirmed)
           VALUES (1, ?, ?, ?, ?, 0)""",
        (username, digest.hex(), salt.hex(), secret),
    )
    db.commit()
    print("后台账号已创建：%s" % username, flush=True)
    if generated:
        print("后台密码（只出现这一次）：%s" % password, flush=True)
    print("动态令牌密钥：%s" % secret, flush=True)
    print("otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=6&period=30" % (
        urllib.parse.quote("小助手后台"),
        urllib.parse.quote(username),
        secret,
        urllib.parse.quote("小助手后台"),
    ), flush=True)


def auth_hash(auth_key: str) -> str:
    return hashlib.sha256(auth_key.encode("utf-8")).hexdigest()


def base64_b32(raw: bytes) -> str:
    import base64
    return base64.b32encode(raw).decode("ascii").rstrip("=")


def b32_decode(secret: str) -> bytes:
    import base64
    clean = "".join(ch for ch in secret.upper() if ch.isalnum())
    pad = "=" * ((8 - len(clean) % 8) % 8)
    return base64.b32decode(clean + pad, casefold=True)


def totp_code(secret: str, when: float = -1, digits: int = 6, period: int = 30) -> str:
    moment = time.time() if when < 0 else when
    counter = int(moment) // period
    msg = counter.to_bytes(8, "big")
    digest = hmac.new(b32_decode(secret), msg, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    binary = int.from_bytes(digest[offset:offset + 4], "big") & 0x7FFFFFFF
    return str(binary % (10 ** digits)).zfill(digits)


def totp_ok(secret: str, code: str) -> bool:
    cleaned = "".join(ch for ch in code if ch.isdigit())
    if len(cleaned) != 6:
        return False
    now = time.time()
    for skew in (-30, 0, 30):
        if hmac.compare_digest(totp_code(secret, now + skew), cleaned):
            return True
    return False


def password_ok(password: str, salt_hex: str, digest_hex: str) -> bool:
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), PBKDF2_ROUNDS)
    return hmac.compare_digest(digest.hex(), digest_hex)


def client_ip(handler: BaseHTTPRequestHandler) -> str:
    forwarded = handler.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return handler.client_address[0]


def limited(ip: str) -> bool:
    now = time.time()
    with rate_lock:
        recent = [stamp for stamp in failures.get(ip, []) if now - stamp < LOGIN_WINDOW]
        failures[ip] = recent
        return len(recent) >= LOGIN_LIMIT


def mark_failure(ip: str) -> None:
    with rate_lock:
        failures.setdefault(ip, []).append(time.time())


def clear_failures(ip: str) -> None:
    with rate_lock:
        failures.pop(ip, None)


def valid_username(name: str) -> bool:
    return bool(name) and len(name) <= 64 and all(ch.isalnum() or ch in "._-" for ch in name)


def qr_svg(data: str) -> str:
    try:
        import io
        import qrcode
        import qrcode.image.svg
        image = qrcode.make(data, image_factory=qrcode.image.svg.SvgPathImage, border=1)
        buffer = io.BytesIO()
        image.save(buffer)
        svg = buffer.getvalue().decode("utf-8")
        return svg.split("?>", 1)[-1]
    except Exception:
        return ""


def format_time(stamp: object) -> str:
    try:
        value = int(stamp)
    except (TypeError, ValueError):
        return "—"
    if value <= 0:
        return "—"
    return time.strftime("%Y-%m-%d %H:%M", time.gmtime(value + 8 * 3600))


def page(title: str, body: str) -> bytes:
    html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin: 0; background: #f6f7f8; color: #1a1a1a;
    font: 14px/1.5 -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif; }
  a { color: #576b95; text-decoration: none; }
  h1 { font-size: 20px; font-weight: 600; margin: 0; }
  button, .btn { background: #07c160; color: #fff; border: 0; border-radius: 4px; height: 36px;
    padding: 0 18px; font: inherit; cursor: pointer; }
  button:hover { background: #06ae56; }
  button.link { background: none; color: #576b95; height: auto; padding: 0; }
  button.link:hover { background: none; color: #07c160; }
  button.link.danger { color: #fa5151; }
  button.link.danger:hover { color: #d84040; background: none; }
  input, textarea { width: 100%; border: 1px solid #e3e4e5; border-radius: 4px; background: #fff;
    color: #1a1a1a; padding: 8px 12px; font: inherit; outline: none; }
  input:focus, textarea:focus { border-color: #07c160; }
  input[type="checkbox"] { width: 16px; height: 16px; padding: 0; accent-color: #07c160; }
  textarea { min-height: 96px; resize: vertical; }
  label { display: block; margin: 0 0 6px; color: #353535; }
  .muted { color: #9a9a9a; font-size: 13px; }
  .error { margin: 0 0 16px; background: #fff2f0; color: #fa5151; border: 1px solid #ffccc7;
    border-radius: 4px; padding: 10px 12px; }
  .secret { font-family: ui-monospace, Consolas, monospace; letter-spacing: 1px; word-break: break-all; color: #353535; }
  svg { background: #fff; width: 196px; height: 196px; border: 1px solid #f0f0f0; border-radius: 4px; }

  .auth { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
  .auth-card { width: 420px; max-width: 100%; background: #fff; border-radius: 8px; padding: 40px 36px 32px;
    box-shadow: 0 12px 32px rgba(0,0,0,.06); }
  .auth-card .logo { margin-bottom: 16px; }
  .auth-card h1 { font-size: 22px; }
  .auth-card .muted { margin: 8px 0 22px; }
  .auth-card .field { margin-bottom: 16px; }
  .auth-card button { width: 100%; margin-top: 8px; }
  .auth-card.wide { width: 480px; }
  .qr { display: flex; justify-content: center; margin: 8px 0 12px; }

  .console { display: flex; min-height: 100vh; }
  aside { width: 208px; flex-shrink: 0; background: #fff; border-right: 1px solid #e7e7e7;
    display: flex; flex-direction: column; }
  .brand { display: flex; align-items: center; gap: 10px; padding: 22px 16px 14px; }
  .logo { width: 32px; height: 32px; border-radius: 6px; background: #07c160; color: #fff;
    display: grid; place-items: center; font-weight: 700; flex-shrink: 0; }
  .brand strong { display: block; font-size: 15px; }
  .brand em { display: block; font-style: normal; color: #9a9a9a; font-size: 12px; }
  aside nav { padding: 8px 12px; }
  aside nav a { display: block; padding: 9px 12px; margin-bottom: 4px; border-radius: 4px; color: #353535; }
  aside nav a.on, aside nav a:hover { background: #e8f8ef; color: #07c160; }
  .aside-user { margin-top: auto; padding: 16px; border-top: 1px solid #f0f0f0; display: flex;
    align-items: center; justify-content: space-between; gap: 8px; color: #666; }
  .workspace { flex: 1; min-width: 0; padding: 20px 28px 48px; }
  .page-h { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin: 4px 0 16px; }
  .panel { background: #fff; border-radius: 4px; margin-bottom: 16px; }
  .panel-h { padding: 16px 24px; font-size: 16px; font-weight: 600; border-bottom: 1px solid #f0f0f0; }
  .panel-b { padding: 20px 24px 24px; }
  .online { display: flex; gap: 40px; align-items: flex-start; }
  .ver-name { font-size: 28px; font-weight: 600; line-height: 1.2; }
  .ver-side { min-width: 148px; }
  .tag { display: inline-block; margin-top: 10px; padding: 0 8px; height: 22px; line-height: 22px;
    border-radius: 2px; font-size: 12px; }
  .tag.on { background: #e8f8ef; color: #07c160; }
  .tag.off { background: #f5f5f5; color: #9a9a9a; }
  .tag.warn { background: #fff7e6; color: #fa8c16; margin-left: 6px; }
  .meta { flex: 1; display: grid; grid-template-columns: 72px 1fr; gap: 10px 12px; margin: 4px 0 0; }
  .meta dt { color: #9a9a9a; }
  .meta dd { margin: 0; white-space: pre-wrap; word-break: break-word; }
  .empty { color: #9a9a9a; padding: 12px 0; }
  .table-wrap { overflow-x: auto; }
  table { width: 100%; border-collapse: collapse; background: #fff; }
  th, td { text-align: left; padding: 14px 16px; border-bottom: 1px solid #f0f0f0; vertical-align: top; }
  th { background: #fafafa; color: #888; font-weight: 500; font-size: 13px; }
  td { color: #353535; }
  tr:last-child td { border-bottom: 0; }
  td form { margin: 0; }
  .clip { max-width: 320px; white-space: pre-wrap; word-break: break-word; color: #666; }
  .fields { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 24px; }
  .span { grid-column: 1 / -1; }
  .choices { display: flex; gap: 28px; flex-wrap: wrap; }
  .choice { display: flex; align-items: center; gap: 8px; margin: 0; }
  .upload { position: relative; border: 1px dashed #d9d9d9; border-radius: 4px; background: #fafafa;
    padding: 28px 16px; text-align: center; }
  .upload input { position: absolute; inset: 0; opacity: 0; cursor: pointer; height: 100%; }
  .upload strong { color: #07c160; font-weight: 500; }
  .actions { display: flex; justify-content: flex-end; margin-top: 4px; }
  @media (max-width: 860px) {
    .console { display: block; }
    aside { width: auto; border-right: 0; border-bottom: 1px solid #e7e7e7; }
    aside nav { display: flex; gap: 4px; }
    .aside-user { margin-top: 0; }
    .workspace { padding: 16px; }
    .online, .fields { display: block; }
    .field { margin-bottom: 16px; }
    .meta { margin-top: 16px; }
  }
</style>
</head>
<body>
__BODY__
<script>
document.querySelectorAll(".upload input").forEach(function (input) {
  input.addEventListener("change", function () {
    var file = input.files && input.files[0];
    var label = input.parentNode.querySelector("strong");
    if (label) label.textContent = file ? file.name : "选择安装包";
  });
});
</script>
</body></html>"""
    return html.replace("__TITLE__", html_escape(title)).replace("__BODY__", body).encode("utf-8")


def html_escape(value: object) -> str:
    text = str(value)
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def parse_multipart(body: bytes, content_type: str) -> tuple[dict[str, list[str]], dict[str, tuple[str, bytes]]]:
    marker = ""
    for piece in content_type.split(";"):
        piece = piece.strip()
        if piece.lower().startswith("boundary="):
            marker = piece.split("=", 1)[1].strip().strip('"')
    fields: dict[str, list[str]] = {}
    files: dict[str, tuple[str, bytes]] = {}
    if not marker:
        return fields, files
    for chunk in body.split(("--" + marker).encode("utf-8")):
        if chunk.startswith(b"\r\n"):
            chunk = chunk[2:]
        if chunk.endswith(b"\r\n"):
            chunk = chunk[:-2]
        if not chunk or chunk == b"--":
            continue
        header_blob, sep, data = chunk.partition(b"\r\n\r\n")
        if not sep:
            continue
        disposition = ""
        for line in header_blob.decode("utf-8", "replace").split("\r\n"):
            if line.lower().startswith("content-disposition:"):
                disposition = line
        name = ""
        filename = ""
        for item in disposition.split(";"):
            item = item.strip()
            if item.startswith("name="):
                name = item.split("=", 1)[1].strip().strip('"')
            elif item.startswith("filename="):
                filename = item.split("=", 1)[1].strip().strip('"')
        if not name:
            continue
        if filename:
            files[name] = (filename, data)
        else:
            fields.setdefault(name, []).append(data.decode("utf-8", "replace"))
    return fields, files


class Handler(BaseHTTPRequestHandler):
    server_version = "assistant-vault/1"

    def do_GET(self) -> None:  # noqa: N802
        path, query = self._split()
        if path == "/v1/health":
            self._send_json(200, {"ok": True})
        elif path == "/v1/vault":
            self._get_vault()
        elif path == "/v1/app/update":
            self._app_update(query)
        elif path.startswith("/v1/app/download/"):
            self._download(path.rsplit("/", 1)[-1])
        elif path == "/":
            self._redirect("/admin")
        elif path in ("/admin", "/admin/"):
            self._admin_home()
        elif path == "/admin/login":
            self._login_form()
        elif path == "/admin/setup":
            self._setup_form()
        else:
            self._send_json(404, {"ok": False, "error": "没有这个接口"})

    def do_POST(self) -> None:  # noqa: N802
        path, _query = self._split()
        length = int(self.headers.get("Content-Length", "0") or "0")
        limit = MAX_APK if path == "/admin/releases" else MAX_JSON
        if length < 0 or length > limit:
            self._send_json(413, {"ok": False, "error": "内容过大"})
            return
        raw = self.rfile.read(length) if length else b""
        content_type = self.headers.get("Content-Type", "")
        if path == "/admin/releases":
            fields, files = parse_multipart(raw, content_type)
            self._save_release(fields, files)
            return
        if path.startswith("/admin"):
            fields = urllib.parse.parse_qs(raw.decode("utf-8", "replace"))
            self._admin_post(path, fields)
            return
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._send_json(400, {"ok": False, "error": "请求不是 JSON"})
            return
        if path == "/v1/register":
            self._register(body)
        elif path == "/v1/login":
            self._login(body)
        elif path == "/v1/vault":
            self._put_vault(body)
        else:
            self._send_json(404, {"ok": False, "error": "没有这个接口"})

    def log_message(self, fmt: str, *args) -> None:
        print("%s %s" % (self.command, self.path.split("?", 1)[0]), flush=True)

    def _split(self) -> tuple[str, dict[str, list[str]]]:
        parsed = urllib.parse.urlparse(self.path)
        return parsed.path.rstrip("/") or "/", urllib.parse.parse_qs(parsed.query)

    def _register(self, body: dict) -> None:
        username = str(body.get("username") or "").strip()
        auth_key = str(body.get("authKey") or "")
        if not valid_username(username) or len(auth_key) < 32:
            self._send_json(400, {"ok": False, "error": "用户名或密钥无效"})
            return
        with db_lock:
            db = connect()
            try:
                exists = db.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone()
                if exists:
                    self._send_json(409, {"ok": False, "error": "这个用户名已经注册过"})
                    return
                db.execute(
                    "INSERT INTO users (username, auth_hash, created_at) VALUES (?, ?, ?)",
                    (username, auth_hash(auth_key), int(time.time())),
                )
                db.commit()
            finally:
                db.close()
        self._send_json(200, {"ok": True})

    def _login(self, body: dict) -> None:
        ip = client_ip(self)
        if limited(ip):
            self._send_json(429, {"ok": False, "error": "尝试次数太多，请稍后再试"})
            return
        username = str(body.get("username") or "").strip()
        auth_key = str(body.get("authKey") or "")
        with db_lock:
            db = connect()
            try:
                row = db.execute("SELECT auth_hash FROM users WHERE username = ?", (username,)).fetchone()
                if row is None or not hmac.compare_digest(row["auth_hash"], auth_hash(auth_key)):
                    mark_failure(ip)
                    self._send_json(401, {"ok": False, "error": "用户名或密码不正确"})
                    return
                token = secrets.token_hex(32)
                db.execute(
                    "INSERT INTO sessions (token, username, expires_at) VALUES (?, ?, ?)",
                    (token, username, int(time.time()) + SESSION_TTL),
                )
                db.commit()
            finally:
                db.close()
        clear_failures(ip)
        self._send_json(200, {"ok": True, "token": token})

    def _put_vault(self, body: dict) -> None:
        username = self._vault_user()
        if username is None:
            return
        ciphertext = str(body.get("ciphertext") or "")
        iv = str(body.get("iv") or "")
        if not ciphertext or not iv or len(ciphertext) > MAX_JSON:
            self._send_json(400, {"ok": False, "error": "密文无效"})
            return
        with db_lock:
            db = connect()
            try:
                db.execute(
                    """INSERT INTO vaults (username, ciphertext, iv, updated_at)
                       VALUES (?, ?, ?, ?)
                       ON CONFLICT(username) DO UPDATE SET
                         ciphertext = excluded.ciphertext,
                         iv = excluded.iv,
                         updated_at = excluded.updated_at""",
                    (username, ciphertext, iv, int(time.time())),
                )
                db.commit()
            finally:
                db.close()
        self._send_json(200, {"ok": True})

    def _get_vault(self) -> None:
        username = self._vault_user()
        if username is None:
            return
        with db_lock:
            db = connect()
            try:
                row = db.execute(
                    "SELECT ciphertext, iv FROM vaults WHERE username = ?",
                    (username,),
                ).fetchone()
            finally:
                db.close()
        if row is None:
            self._send_json(200, {"ok": True, "ciphertext": "", "iv": ""})
        else:
            self._send_json(200, {"ok": True, "ciphertext": row["ciphertext"], "iv": row["iv"]})

    def _vault_user(self) -> str | None:
        header = self.headers.get("Authorization", "")
        token = header[7:].strip() if header.startswith("Bearer ") else ""
        if not token:
            self._send_json(401, {"ok": False, "error": "请先登录"})
            return None
        with db_lock:
            db = connect()
            try:
                row = db.execute(
                    "SELECT username, expires_at FROM sessions WHERE token = ?",
                    (token,),
                ).fetchone()
            finally:
                db.close()
        if row is None or row["expires_at"] < time.time():
            self._send_json(401, {"ok": False, "error": "登录已过期"})
            return None
        return row["username"]

    def _app_update(self, query: dict[str, list[str]]) -> None:
        package = (query.get("package") or [""])[0]
        if package and package != APP_PACKAGE:
            self._send_json(200, {"ok": True, "hasUpdate": False})
            return
        try:
            current = int((query.get("versionCode") or ["0"])[0])
        except ValueError:
            current = 0
        with db_lock:
            db = connect()
            try:
                row = db.execute(
                    """SELECT version_code, version_name, changelog, sha256, force_update
                       FROM releases WHERE published = 1 ORDER BY version_code DESC LIMIT 1"""
                ).fetchone()
            finally:
                db.close()
        if row is None or row["version_code"] <= current:
            self._send_json(200, {"ok": True, "hasUpdate": False})
            return
        code = row["version_code"]
        self._send_json(200, {
            "ok": True,
            "hasUpdate": True,
            "versionCode": code,
            "versionName": row["version_name"],
            "changelog": row["changelog"],
            "url": "%s/v1/app/download/%s" % (PUBLIC_BASE_URL, code),
            "sha256": row["sha256"],
            "force": bool(row["force_update"]),
        })

    def _download(self, raw_code: str) -> None:
        if not raw_code.isdigit():
            self._send_json(404, {"ok": False, "error": "没有这个版本"})
            return
        code = int(raw_code)
        path = APK_DIR / ("%d.apk" % code)
        with db_lock:
            db = connect()
            try:
                row = db.execute(
                    "SELECT version_name, published FROM releases WHERE version_code = ?",
                    (code,),
                ).fetchone()
            finally:
                db.close()
        if row is None or not row["published"] or not path.is_file():
            self._send_json(404, {"ok": False, "error": "没有这个版本"})
            return
        safe_name = re.sub(r"[^A-Za-z0-9._-]", "_", row["version_name"]) or "app"
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", 'attachment; filename="assistant-%s.apk"' % safe_name)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _admin_post(self, path: str, fields: dict[str, list[str]]) -> None:
        if path == "/admin/login":
            self._login_password(fields)
        elif path == "/admin/totp":
            self._login_totp(fields)
        elif path == "/admin/setup":
            self._confirm_setup(fields)
        elif path == "/admin/logout":
            self._logout(fields)
        elif path == "/admin/releases/delete":
            self._delete_release(fields)
        else:
            self._send_json(404, {"ok": False, "error": "没有这个接口"})

    def _login_form(self, error: str = "") -> None:
        message = '<p class="error">%s</p>' % html_escape(error) if error else ""
        body = """
        <div class="auth">
          <form class="auth-card" method="post" action="/admin/login">
            <div class="logo">助</div>
            <h1>小助手后台</h1>
            <p class="muted">先验证密码，下一步输入动态令牌</p>
            %s
            <div class="field"><label>账号</label><input name="username" autocomplete="username" required></div>
            <div class="field"><label>密码</label><input name="password" type="password" autocomplete="current-password" required></div>
            <button type="submit">下一步</button>
          </form>
        </div>""" % message
        self._send_html(page("后台登录", body))

    def _login_password(self, fields: dict[str, list[str]]) -> None:
        ip = client_ip(self)
        if limited(ip):
            self._login_form("尝试次数太多，请稍后再试")
            return
        username = (fields.get("username") or [""])[0].strip()
        password = (fields.get("password") or [""])[0]
        with db_lock:
            db = connect()
            try:
                admin = db.execute("SELECT * FROM admin WHERE id = 1").fetchone()
                if admin is None or username != admin["username"] or not password_ok(password, admin["password_salt"], admin["password_hash"]):
                    mark_failure(ip)
                    self._login_form("账号或密码不正确")
                    return
                clear_failures(ip)
                if not admin["totp_confirmed"]:
                    self._start_admin_session(db, "setup")
                    self._redirect("/admin/setup")
                    return
                token = secrets.token_urlsafe(24)
                db.execute(
                    "INSERT INTO pending_logins (token, expires_at) VALUES (?, ?)",
                    (token, int(time.time()) + PENDING_TTL),
                )
                db.commit()
            finally:
                db.close()
        self._send_html(page("动态令牌", self._totp_body(token, "")))

    def _totp_body(self, token: str, error: str) -> str:
        message = '<p class="error">%s</p>' % html_escape(error) if error else ""
        return """
        <div class="auth">
          <form class="auth-card" method="post" action="/admin/totp">
            <div class="logo">助</div>
            <h1>动态令牌</h1>
            <p class="muted">打开验证器，输入 6 位验证码</p>
            %s
            <input type="hidden" name="pending" value="%s">
            <div class="field"><label>验证码</label><input name="code" inputmode="numeric" autocomplete="one-time-code" required></div>
            <button type="submit">登录</button>
          </form>
        </div>""" % (message, html_escape(token))

    def _login_totp(self, fields: dict[str, list[str]]) -> None:
        token = (fields.get("pending") or [""])[0]
        code = (fields.get("code") or [""])[0]
        with db_lock:
            db = connect()
            try:
                pending = db.execute("SELECT expires_at FROM pending_logins WHERE token = ?", (token,)).fetchone()
                admin = db.execute("SELECT totp_secret FROM admin WHERE id = 1").fetchone()
                if pending is None or pending["expires_at"] < time.time() or admin is None:
                    self._login_form("登录已过期，请重新输入密码")
                    return
                if not totp_ok(admin["totp_secret"], code):
                    self._send_html(page("动态令牌", self._totp_body(token, "验证码不正确")))
                    return
                db.execute("DELETE FROM pending_logins WHERE token = ?", (token,))
                self._start_admin_session(db, "full")
            finally:
                db.close()
        self._redirect("/admin")

    def _setup_form(self, error: str = "") -> None:
        session = self._admin_session()
        if session is None:
            self._redirect("/admin/login")
            return
        with db_lock:
            db = connect()
            try:
                admin = db.execute("SELECT username, totp_secret, totp_confirmed FROM admin WHERE id = 1").fetchone()
            finally:
                db.close()
        if admin is None:
            self._redirect("/admin/login")
            return
        if admin["totp_confirmed"] and session["scope"] == "full":
            self._redirect("/admin")
            return
        if admin["totp_confirmed"]:
            self._redirect("/admin/login")
            return
        issuer = "小助手后台"
        uri = "otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=6&period=30" % (
            urllib.parse.quote(issuer),
            urllib.parse.quote(admin["username"]),
            admin["totp_secret"],
            urllib.parse.quote(issuer),
        )
        message = '<p class="error">%s</p>' % html_escape(error) if error else ""
        body = """
        <div class="auth">
          <form class="auth-card wide" method="post" action="/admin/setup">
            <div class="logo">助</div>
            <h1>绑定动态令牌</h1>
            <p class="muted">用验证器扫码，或手动输入密钥。确认一次后，以后登录都要两步。</p>
            <div class="qr">%s</div>
            <p class="secret">%s</p>
            %s
            <input type="hidden" name="csrf" value="%s">
            <div class="field"><label>验证码</label><input name="code" inputmode="numeric" autocomplete="one-time-code" required></div>
            <button type="submit">确认并进入后台</button>
          </form>
        </div>""" % (qr_svg(uri), html_escape(admin["totp_secret"]), message, html_escape(session["csrf"]))
        self._send_html(page("绑定动态令牌", body))

    def _confirm_setup(self, fields: dict[str, list[str]]) -> None:
        session = self._admin_session()
        if session is None or not hmac.compare_digest(session["csrf"], (fields.get("csrf") or [""])[0]):
            self._redirect("/admin/login")
            return
        code = (fields.get("code") or [""])[0]
        with db_lock:
            db = connect()
            try:
                admin = db.execute("SELECT totp_secret FROM admin WHERE id = 1").fetchone()
                secret = "" if admin is None else admin["totp_secret"]
                if not secret or not totp_ok(secret, code):
                    accepted = False
                else:
                    db.execute("UPDATE admin SET totp_confirmed = 1 WHERE id = 1")
                    db.execute("UPDATE admin_sessions SET scope = 'full' WHERE token = ?", (session["token"],))
                    db.commit()
                    accepted = True
            finally:
                db.close()
        if not accepted:
            self._setup_form("验证码不正确")
            return
        self._redirect("/admin")

    def _admin_home(self, error: str = "") -> None:
        session = self._require_full_admin()
        if session is None:
            return
        with db_lock:
            db = connect()
            try:
                admin = db.execute("SELECT username, totp_secret FROM admin WHERE id = 1").fetchone()
                users = db.execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
                vaults = db.execute("SELECT COUNT(*) AS n FROM vaults").fetchone()["n"]
                releases = db.execute(
                    "SELECT version_code, version_name, changelog, force_update, published, created_at FROM releases ORDER BY version_code DESC"
                ).fetchall()
                latest_code = releases[0]["version_code"] if releases else 0
            finally:
                db.close()
        online = next((item for item in releases if item["published"]), None)
        if online is None:
            online_html = '<div class="empty">暂无线上版本。在下方开发版本里上传安装包，并勾选立即发布。</div>'
        else:
            force = '<span class="tag warn">强制更新</span>' if online["force_update"] else ""
            online_html = """
            <div class="online">
              <div class="ver-side">
                <div class="ver-name">%s</div>
                <span class="tag on">已发布</span>%s
              </div>
              <dl class="meta">
                <dt>版本号</dt><dd>%s</dd>
                <dt>发布时间</dt><dd>%s</dd>
                <dt>更新说明</dt><dd>%s</dd>
              </dl>
            </div>""" % (
                html_escape(online["version_name"]),
                force,
                online["version_code"],
                html_escape(format_time(online["created_at"])),
                html_escape(online["changelog"]),
            )
        rows = []
        for item in releases:
            if item["published"]:
                state = '<span class="tag on">已发布</span>'
            else:
                state = '<span class="tag off">未发布</span>'
            if item["force_update"]:
                state += '<span class="tag warn">强制</span>'
            message = "删除版本 " + str(item["version_name"]) + " ？删除后手机将不再能下载这个包。"
            rows.append(
                "<tr><td>%s</td><td>%s</td><td>%s</td><td><div class='clip'>%s</div></td><td>%s</td>"
                "<td><form method='post' action='/admin/releases/delete' onsubmit=\"return confirm(%s)\">"
                "<input type='hidden' name='csrf' value='%s'><input type='hidden' name='version_code' value='%s'>"
                "<button class='link danger' type='submit'>删除</button></form></td></tr>"
                % (
                    html_escape(item["version_name"]),
                    item["version_code"],
                    state,
                    html_escape(item["changelog"]),
                    html_escape(format_time(item["created_at"])),
                    html_escape(json.dumps(message, ensure_ascii=False)),
                    html_escape(session["csrf"]),
                    item["version_code"],
                )
            )
        if rows:
            table = (
                "<div class='table-wrap'><table><tr><th>版本</th><th>版本号</th><th>状态</th>"
                "<th>更新说明</th><th>发布时间</th><th>操作</th></tr>%s</table></div>" % "".join(rows)
            )
        else:
            table = '<div class="empty">还没有版本记录</div>'
        error_html = '<p class="error">%s</p>' % html_escape(error) if error else ""
        next_code = (releases[0]["version_code"] if releases else 0) + 1
        body = """
        <div class="console">
          <aside>
            <div class="brand"><div class="logo">助</div><div><strong>小助手</strong><em>管理后台</em></div></div>
            <nav>
              <a class="on" href="/admin">版本管理</a>
              <a href="#secret">登录令牌</a>
            </nav>
            <div class="aside-user">
              <span>%s</span>
              <form method="post" action="/admin/logout">
                <input type="hidden" name="csrf" value="%s">
                <button class="link" type="submit">退出</button>
              </form>
            </div>
          </aside>
          <div class="workspace">
            <div class="page-h">
              <h1>版本管理</h1>
              <p class="muted">注册账号 %s 个 · 已同步令牌库 %s 个</p>
            </div>
            %s
            <section class="panel">
              <div class="panel-h">线上版本</div>
              <div class="panel-b">%s</div>
            </section>
            <section class="panel">
              <div class="panel-h">版本记录</div>
              <div class="panel-b">%s</div>
            </section>
            <section class="panel">
              <div class="panel-h">开发版本</div>
              <form class="panel-b" method="post" action="/admin/releases" enctype="multipart/form-data">
                <input type="hidden" name="csrf" value="%s">
                <p class="muted" style="margin:0 0 16px">上传安装包。版本号只填整数，当前最大是 %s，1.8.0 这种写在版本名里。</p>
                <div class="fields">
                  <div class="field">
                    <label>版本号</label>
                    <input name="version_code" inputmode="numeric" placeholder="%s" required>
                  </div>
                  <div class="field">
                    <label>版本名</label>
                    <input name="version_name" placeholder="1.8.0" required>
                  </div>
                  <div class="field span">
                    <label>更新说明</label>
                    <textarea name="changelog" placeholder="这一版修了什么" required></textarea>
                  </div>
                  <div class="field span">
                    <label>安装包</label>
                    <div class="upload">
                      <input name="apk" type="file" accept=".apk,application/vnd.android.package-archive" required>
                      <strong>选择安装包</strong>
                      <div class="muted">仅支持 .apk，不超过 80MB</div>
                    </div>
                  </div>
                </div>
                <div class="choices">
                  <label class="choice"><input type="checkbox" name="published" value="1" checked> 立即发布为线上版本</label>
                  <label class="choice"><input type="checkbox" name="force" value="1"> 强制更新</label>
                </div>
                <div class="actions"><button type="submit">提交发布</button></div>
              </form>
            </section>
            <section class="panel" id="secret">
              <div class="panel-h">登录令牌</div>
              <div class="panel-b">
                <p class="muted" style="margin-top:0">换手机时，把这个密钥加进验证器。不要发给别人。</p>
                <p class="secret">%s</p>
              </div>
            </section>
          </div>
        </div>
        """ % (
            html_escape(admin["username"] if admin else ""),
            html_escape(session["csrf"]),
            users,
            vaults,
            error_html,
            online_html,
            table,
            html_escape(session["csrf"]),
            latest_code,
            next_code,
            html_escape(admin["totp_secret"] if admin else ""),
        )
        self._send_html(page("版本管理", body))

    def _save_release(self, fields: dict[str, list[str]], files: dict[str, tuple[str, bytes]]) -> None:
        session = self._require_full_admin()
        if session is None:
            return
        if not hmac.compare_digest(session["csrf"], (fields.get("csrf") or [""])[0]):
            self._redirect("/admin/login")
            return
        raw_code = (fields.get("version_code") or [""])[0].strip()
        version_name = (fields.get("version_name") or [""])[0].strip()
        changelog = (fields.get("changelog") or [""])[0].strip()
        if not raw_code or not version_name or not changelog:
            self._admin_home("版本号、版本名和更新说明都要填")
            return
        if not raw_code.isdigit():
            self._admin_home("版本号要填整数，例如 16。1.8.0 这种请填在版本名里。")
            return
        uploaded = files.get("apk")
        if uploaded is None or not uploaded[1]:
            self._admin_home("请选择安装包")
            return
        filename, blob = uploaded
        if not filename.lower().endswith(".apk") or len(blob) > MAX_APK:
            self._admin_home("请上传不超过 80MB 的 apk")
            return
        code = int(raw_code)
        digest = hashlib.sha256(blob).hexdigest()
        target = APK_DIR / ("%d.apk" % code)
        target.write_bytes(blob)
        with db_lock:
            db = connect()
            try:
                db.execute(
                    """INSERT INTO releases (version_code, version_name, changelog, sha256, force_update, published, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT(version_code) DO UPDATE SET
                         version_name = excluded.version_name,
                         changelog = excluded.changelog,
                         sha256 = excluded.sha256,
                         force_update = excluded.force_update,
                         published = excluded.published,
                         created_at = excluded.created_at""",
                    (
                        code,
                        version_name[:40],
                        changelog[:2000],
                        digest,
                        1 if (fields.get("force") or ["0"])[0] == "1" else 0,
                        1 if (fields.get("published") or ["0"])[0] == "1" else 0,
                        int(time.time()),
                    ),
                )
                db.commit()
            finally:
                db.close()
        self._redirect("/admin")

    def _delete_release(self, fields: dict[str, list[str]]) -> None:
        session = self._require_full_admin()
        if session is None:
            return
        if not hmac.compare_digest(session["csrf"], (fields.get("csrf") or [""])[0]):
            self._redirect("/admin/login")
            return
        raw_code = (fields.get("version_code") or [""])[0]
        if raw_code.isdigit():
            code = int(raw_code)
            with db_lock:
                db = connect()
                try:
                    db.execute("DELETE FROM releases WHERE version_code = ?", (code,))
                    db.commit()
                finally:
                    db.close()
            path = APK_DIR / ("%d.apk" % code)
            if path.is_file():
                path.unlink()
        self._redirect("/admin")

    def _logout(self, fields: dict[str, list[str]]) -> None:
        session = self._admin_session()
        if session is not None and hmac.compare_digest(session["csrf"], (fields.get("csrf") or [""])[0]):
            with db_lock:
                db = connect()
                try:
                    db.execute("DELETE FROM admin_sessions WHERE token = ?", (session["token"],))
                    db.commit()
                finally:
                    db.close()
        self._redirect("/admin/login", clear_cookie=True)

    def _require_full_admin(self) -> sqlite3.Row | None:
        session = self._admin_session()
        if session is None:
            self._redirect("/admin/login")
            return None
        if session["scope"] != "full":
            self._redirect("/admin/setup")
            return None
        return session

    def _admin_session(self) -> sqlite3.Row | None:
        token = self._cookie("admin_session")
        if not token:
            return None
        with db_lock:
            db = connect()
            try:
                row = db.execute(
                    "SELECT token, csrf, scope, expires_at FROM admin_sessions WHERE token = ?",
                    (token,),
                ).fetchone()
                if row is not None and row["expires_at"] < time.time():
                    db.execute("DELETE FROM admin_sessions WHERE token = ?", (token,))
                    db.commit()
                    row = None
            finally:
                db.close()
        return row

    def _start_admin_session(self, db: sqlite3.Connection, scope: str) -> None:
        token = secrets.token_urlsafe(32)
        csrf = secrets.token_urlsafe(24)
        db.execute("DELETE FROM admin_sessions")
        db.execute(
            "INSERT INTO admin_sessions (token, csrf, scope, expires_at) VALUES (?, ?, ?, ?)",
            (token, csrf, scope, int(time.time()) + ADMIN_SESSION_TTL),
        )
        db.commit()
        self._pending_cookie = token

    def _cookie(self, name: str) -> str:
        header = self.headers.get("Cookie", "")
        for piece in header.split(";"):
            key, _, value = piece.strip().partition("=")
            if key == name:
                return urllib.parse.unquote(value)
        return ""

    def _redirect(self, location: str, clear_cookie: bool = False) -> None:
        self.send_response(303)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        cookie = getattr(self, "_pending_cookie", "")
        if clear_cookie:
            self.send_header("Set-Cookie", "admin_session=; HttpOnly; Path=/; Max-Age=0; SameSite=Lax")
        elif cookie:
            self.send_header(
                "Set-Cookie",
                "admin_session=%s; HttpOnly; Path=/; Max-Age=%d; SameSite=Lax" % (cookie, ADMIN_SESSION_TTL),
            )
        self.end_headers()

    def _send_html(self, payload: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        cookie = getattr(self, "_pending_cookie", "")
        if cookie:
            self.send_header(
                "Set-Cookie",
                "admin_session=%s; HttpOnly; Path=/; Max-Age=%d; SameSite=Lax" % (cookie, ADMIN_SESSION_TTL),
            )
        self.end_headers()
        self.wfile.write(payload)

    def _send_json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _text(self, status: int, message: str) -> None:
        data = message.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def self_test() -> None:
    # RFC 6238 附录 B，T=59，8 位 SHA1。
    secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    code = totp_code(secret, 59, digits=8, period=30)
    if code != "94287082":
        raise SystemExit("totp self-test failed: %s" % code)
    print("totp self-test ok")


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        return
    with db_lock:
        db = connect()
        try:
            ensure_admin(db)
        finally:
            db.close()
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print("服务已启动：%s" % PUBLIC_BASE_URL, flush=True)
    print("后台：%s/admin" % PUBLIC_BASE_URL, flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
