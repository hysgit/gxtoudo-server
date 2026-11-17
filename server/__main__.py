"""Run the recovered 高效Todo v7 API."""

import argparse
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import smtplib
import sqlite3
import ssl
import time
from email.message import EmailMessage
from urllib.parse import parse_qsl, urlsplit

from .accounts import authenticate, issue_reset, profile, reset_password, sign_in, update_profile
from .collaboration import comments, list_teams, manage_message, message_detail, prepare_team, save_team, team_members
from .protocol import ApiError, compact, number
from .storage import Storage
from .sync import synchronize


MAX_BODY = 16 * 1024 * 1024
PREFIX = "/api/v7"
DISABLED = {
    "/user/open", "/user/upToken", "/open/move", "/vip", "/order/base", "/order/prepare",
    "/order/pay", "/order/check", "/order/code",
}
PAGES = {
    "/agreement": ("使用协议", "这是高效Todo自建服务。请使用你获授权的实例，并妥善保管账号。"),
    "/privacy": ("隐私政策", "实例会保存你的账号、任务、便签和团队数据用于同步。团队成员按空间权限访问共享内容。"
                 "可在账户设置里注销账号。备份和数据保留由实例管理员负责。"),
    "/minor_privacy": ("未成年人隐私说明", "未成年人应当在监护人同意和指导下使用本服务。"),
    "/intro/m-help.php": ("使用帮助", "在左侧菜单管理个人空间或团队。登录后点击同步按钮，"
                          "并在其他设备登录同一账号即可查看已同步的数据。"),
}
DOCUMENTS = {
    "ServiceAgreement": "/agreement", "PrivacyPolicy": "/privacy", "ChildrenPrivacyStatement": "/minor_privacy",
}


def deliver_reset(address, token):
    host = os.environ.get("GXTODO_SMTP_HOST")
    origin = os.environ.get("GXTODO_PUBLIC_ORIGIN", "").rstrip("/")
    sender = os.environ.get("GXTODO_SMTP_FROM")
    if not host or urlsplit(origin).scheme != "https" or not sender:
        raise ApiError(1, "实例管理员尚未配置邮件找回密码")
    message = EmailMessage()
    message["Subject"] = "高效Todo 密码重置"
    message["From"] = sender
    message["To"] = address
    message.set_content(f"请在 20 分钟内打开链接重置高效Todo密码。\n\n{origin}/reset?token={token}\n")
    port = int(os.environ.get("GXTODO_SMTP_PORT", "465"))
    context = ssl.create_default_context()
    smtp_class = smtplib.SMTP_SSL if port == 465 else smtplib.SMTP
    options = {"context": context} if port == 465 else {}
    with smtp_class(host, port, timeout=10, **options) as smtp:
        if port != 465:
            smtp.starttls(context=context)
        username = os.environ.get("GXTODO_SMTP_USER")
        if username:
            smtp.login(username, os.environ["GXTODO_SMTP_PASSWORD"])
        smtp.send_message(message)


def dispatch(storage, route, method, params):
    if route == "/user/login" and method == "POST":
        return sign_in(storage, params)
    if route == "/user/base" and method == "POST":
        return sign_in(storage, params, register=True)
    if route == "/user/mail" and method == "POST":
        if not all(os.environ.get(key) for key in ("GXTODO_SMTP_HOST", "GXTODO_SMTP_FROM", "GXTODO_PUBLIC_ORIGIN")):
            raise ApiError(1, "实例管理员尚未配置邮件找回密码")
        return issue_reset(storage, params, deliver_reset)
    if route in DISABLED:
        raise ApiError(1, "该功能需要在自建实例配置第三方服务")
    with storage.transaction(write=method != "GET") as db:
        user = authenticate(db, params)
        if route == "/user/base":
            if method == "PUT":
                return update_profile(db, user, params)
            if method == "GET":
                return {"data": profile(user)}
        if route == "/user/vipInfo" and method == "GET":
            return {"data": {"is_vip": 0, "remaining_days": 0, "vip_expired_at": ""}}
        if route == "/user/deactivate" and method == "PUT":
            db.execute("UPDATE users SET inactive=1 WHERE id=?", (user["id"],))
            db.execute("UPDATE sessions SET expires=0 WHERE user_id=?", (user["id"],))
            db.execute("DELETE FROM reset_codes WHERE user_id=?", (user["id"],))
            return {}
        if route == "/sync/do" and method == "POST":
            return synchronize(db, user, params)
        if route == "/team/base" and method in {"GET", "POST", "PUT"}:
            return save_team(db, user, params, method)
        if route == "/team/prepare" and method == "GET":
            return prepare_team(db, user, params)
        if route == "/team/list" and method in {"GET", "POST"}:
            return list_teams(db, user, params)
        if route == "/team/user" and method == "GET":
            return team_members(db, user, params)
        if route == "/message/base" and method in {"GET", "POST", "PUT"}:
            return manage_message(db, user, params, method)
        if route == "/message/detail" and method in {"GET", "POST"}:
            return message_detail(db, user, params)
        if route in {"/comment/list", "/comment/create"} and method == "POST":
            return comments(db, user, params, create=route.endswith("/create"))
    raise ApiError(1, "接口或请求方法不支持")


class Handler(BaseHTTPRequestHandler):
    server_version = "GXTodo/7"

    def setup(self):
        self.request.settimeout(20)
        super().setup()

    def log_request(self, code="-", size="-"):
        # Legacy GET requests carry credentials in their query string.
        path = urlsplit(self.path).path
        self.log_message("%s %s %s", self.command, path, code)

    def log_error(self, format, *args):
        self.log_message("HTTP request error")

    def params(self, query):
        values = dict(parse_qsl(query, keep_blank_values=True, max_num_fields=128))
        if self.command in {"POST", "PUT"}:
            if self.headers.get("Transfer-Encoding"):
                raise ApiError(1, "请求编码不支持")
            length = number(self.headers.get("Content-Length", "0"), 0, MAX_BODY)
            content = self.rfile.read(length).decode("utf-8")
            if self.headers.get_content_type() == "application/json":
                decoded = json.loads(content)
                if not isinstance(decoded, dict):
                    raise ApiError(1, "请求参数格式错误")
                values.update(decoded)
            else:
                values.update(parse_qsl(content, keep_blank_values=True, max_num_fields=128))
        return values

    def send_content(self, value, content_type, code=200):
        body = value.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, value, code=200):
        self.send_content(compact(value), "application/json", code)

    def page(self, title, content, code=200):
        self.send_content(
            '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{title}</title><main style="max-width:40em;margin:2em auto;padding:0 1em;font-family:sans-serif">'
            f'<h1>{title}</h1>{content}</main></html>', "text/html", code,
        )

    def route_request(self, path, params):
        if path == "/health" and self.command == "GET":
            self.send_json({"status": "ok"})
        elif path == f"{PREFIX}/time.php" and self.command == "GET":
            self.send_content(str(int(time.time() * 1000)), "text/plain")
        elif path == "/intro/v.txt" and self.command == "GET":
            self.send_json({"status": "0", "version_code": 111, "version_name": "7.1.3"})
        elif path == "/reset" and self.command == "GET":
            token = html.escape(str(params.get("token", "")), quote=True)
            self.page("重置密码", '<form action="/reset" method="post">'
                      f'<input type="hidden" name="token" value="{token}">'
                      '<label>新密码 <input type="password" name="password" autocomplete="new-password" '
                      'minlength="6" maxlength="128" required></label> <button>保存</button></form>')
        elif path == "/reset" and self.command == "POST":
            reset_password(self.server.storage, params.get("token"), params.get("password"))
            self.page("密码已重置", "<p>请在高效Todo里重新登录。</p>")
        elif path in PAGES or path == "/intro/m-detail.php":
            page = DOCUMENTS.get(params.get("type"), "/intro/m-help.php") if path.endswith("/m-detail.php") else path
            title, description = PAGES[page]
            self.page(title, f"<p>{description}</p>")
        elif path.startswith(f"{PREFIX}/"):
            method = params.get("_method", self.command)
            if not isinstance(method, str) or method not in {"GET", "POST", "PUT", "DELETE"}:
                raise ApiError(1, "请求方法不支持")
            if self.command == "GET" and method != "GET":
                raise ApiError(1, "请求方法不支持")
            result = dispatch(self.server.storage, path[len(PREFIX):], method, params)
            self.send_json({"status": "0", **result})
        else:
            self.send_json({"status": "1", "message": "页面或接口不存在"}, 404)

    def handle_request(self):
        path = ""
        try:
            url = urlsplit(self.path)
            path = url.path
            self.route_request(path, self.params(url.query))
        except ApiError as error:
            if path == "/reset":
                self.page("重置失败", f"<p>{html.escape(str(error))}</p>", 400)
            else:
                self.send_json({"status": error.status, "message": str(error)})
        except (ValueError, UnicodeError, RecursionError):
            self.send_json({"status": "1", "message": "请求参数格式错误"}, 400)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            return
        except (sqlite3.Error, smtplib.SMTPException, OSError) as error:
            self.log_message("服务错误 (%s)", type(error).__name__)
            self.send_json({"status": "1", "message": "服务暂时不可用"}, 503)

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request

    def do_DELETE(self):
        self.handle_request()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--database", default="data/gxtodo.sqlite3")
    args = parser.parse_args()
    storage = Storage(args.database)
    with ThreadingHTTPServer((args.bind, args.port), Handler) as server:
        server.storage = storage
        print(f"高效Todo API {args.bind}:{args.port}  {storage.path}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
