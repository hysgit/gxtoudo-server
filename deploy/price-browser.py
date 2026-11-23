"""Operate an isolated official merchant browser on behalf of an authenticated Todo session."""
import base64
import fcntl
import hmac
import json
import os
import re
import secrets
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Error, TimeoutError, sync_playwright
from price_cart import CART, CART_ROWS, matching_prices, normalize

LOGIN = {
    "jd": "https://plogin.m.jd.com/login/login?appid=100&returnurl=https%3A%2F%2Fm.jd.com%2F",
    "taobao": "https://login.m.taobao.com/login.htm?redirectURL=https%3A%2F%2Fm.taobao.com%2F",
}
DOMAINS = {
    "jd": ("jd.com", "jd.hk", "360buyimg.com", "jdpay.com", "jcloudcs.com",
           "jdcloud.com", "jddebug.com", "jingxi.com"),
    "taobao": ("taobao.com", "tmall.com", "alicdn.com", "alipay.com",
               "alibaba.com", "aliyun.com", "alibabadns.com", "alibabachengdun.com",
               "alibabausercontent.com", "alipayobjects.com", "mmstat.com"),
}
LOGIN_HOSTS = {
    "jd": {"passport.jd.com", "plogin.m.jd.com", "safe.jd.com"},
    "taobao": {"login.taobao.com", "login.m.taobao.com", "passport.taobao.com"},
}
SIZE = {"width": 390, "height": 760}
IDLE_SECONDS = 90
MAX_SECONDS = 600
DATA = Path(os.environ.get("GXTODO_PRICE_DATA", "/data"))


class Rejected(Exception):
    def __init__(self, message, code="1"):
        self.message, self.code = message, code


def official(url, platform, main_frame=False, resource_type=""):
    parsed = urlsplit(url)
    host = parsed.hostname
    if parsed.scheme != "https" or not host or parsed.username or parsed.port not in (443, None):
        return False
    if (platform == "jd" and not main_frame and host in {"jstatic.3.cn", "jstatic2.3.cn"}
            and parsed.path.startswith("/car/") and resource_type in {"script", "stylesheet", "image", "font"}):
        return True
    roots = ("jd.com", "jd.hk") if platform == "jd" else ("taobao.com", "tmall.com")
    return any(host == domain or host.endswith("." + domain)
               for domain in (roots if main_frame else DOMAINS[platform]))


def cart_location(url, platform):
    parsed = urlsplit(url)
    if not official(url, platform, main_frame=True):
        return False
    if platform == "jd":
        return parsed.hostname in {"p.m.jd.com", "mtrade.jd.hk"} and parsed.path == "/cart/cart.action"
    return parsed.hostname == urlsplit(CART[platform]).hostname


def mounted_profile(user, platform):
    folder = DATA / "profiles" / str(user) / platform
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    return folder


def with_profile(folder):
    lock = open(folder / ".gxtodo.lock", "a+b")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        raise Rejected("该平台正在查价，请稍后再登录")
    return lock


class Browser:
    def __init__(self, chromium, mobile):
        self.chromium = chromium
        self.mobile = {
            "user_agent": mobile["user_agent"], "viewport": SIZE,
            "device_scale_factor": 1, "is_mobile": True, "has_touch": True,
        }
        self.active = None
        self.cart = {}

    def close(self):
        if not self.active:
            return
        session = self.active
        self.active = None
        try:
            session["context"].close()
        finally:
            session["lock"].close()

    def expire(self):
        now = time.monotonic()
        self.cart = {key: value for key, value in self.cart.items() if value[2] > now}
        if self.active:
            if now - self.active["touched"] > IDLE_SECONDS or now - self.active["start"] > MAX_SECONDS:
                self.close()

    def handle(self, data):
        self.expire()
        owner = (data["user"], data["login"])
        action = data["action"]
        platform = data["platform"]
        if action == "cart_prices":
            targets = data["targets"]
            if (platform != "taobao" or type(owner[0]) is not int or owner[0] < 1
                    or owner[1] != 0 or not isinstance(targets, list) or not 1 <= len(targets) <= 30):
                raise Rejected("采价请求无效")
            if not (DATA / "profiles" / str(owner[0]) / platform / ".login-saved").is_file():
                raise Rejected("请先登录平台并保存会话", "411")
            return {"quotes": matching_prices(self.read_cart(owner, platform), targets)}
        if platform not in LOGIN or any(type(value) is not int or value < 1 for value in owner):
            raise Rejected("登录请求无效")
        if action == "status":
            marker = DATA / "profiles" / str(owner[0]) / platform / ".login-saved"
            return {"saved_at": int(marker.stat().st_mtime) if marker.is_file() else 0}
        if action == "start":
            return self.start(owner, platform)
        if action == "cart_list":
            return self.cart_list(owner, platform)
        if action == "cart_resolve":
            selected = self.cart.get(data["selection"])
            if not selected or selected[0] != owner or selected[1] != platform:
                raise Rejected("购物车选择已过期，请重新加载", "410")
            return selected[3]
        current = self.active
        if (not current or current["owner"] != owner or current["platform"] != platform
                or not hmac.compare_digest(current["session"], str(data.get("session", "")))):
            if action == "cancel":
                return {}
            raise Rejected("平台登录窗口已过期，请重新打开", "410")
        current["touched"] = time.monotonic()
        page = current["page"]
        if action not in {"reset", "cancel"} and not official(page.url, platform, main_frame=True):
            raise Rejected("请重开平台登录页，再继续操作")
        if action == "finish":
            host = urlsplit(page.url).hostname
            if not official(page.url, platform, main_frame=True) or host in LOGIN_HOSTS[platform]:
                raise Rejected("请先在平台网页完成登录，再保存会话")
            profile = current["profile"]
            self.close()
            (profile / ".login-saved").touch()
            return {"saved_at": int(time.time())}
        if action == "cancel":
            self.close()
            return {}
        if action == "tap":
            page.touchscreen.tap(data["x"], data["y"])
        elif action == "scroll":
            page.mouse.move(data["x"], data["y"])
            page.mouse.wheel(0, data["dy"])
        elif action == "drag":
            self.touch(page, [
                {"x": data["from_x"], "y": data["from_y"], "ms": 0},
                {"x": data["to_x"], "y": data["to_y"], "ms": 0},
            ])
        elif action == "gesture":
            self.touch(page, data["points"])
        elif action == "type":
            page.keyboard.insert_text(data["input"])
        elif action == "key":
            page.keyboard.press(data["key"])
        elif action == "reset":
            self.open_login(page, platform)
        elif action != "frame":
            raise Rejected("不支持这个登录操作")
        return self.frame(current)

    @staticmethod
    def touch(page, points):
        connection = page.context.new_cdp_session(page)
        def send(kind, point=None):
            coordinates = [] if point is None else [{"x": point["x"], "y": point["y"]}]
            connection.send("Input.dispatchTouchEvent", {"type": kind, "touchPoints": coordinates})
        try:
            send("touchStart", points[0])
            previous = points[0]["ms"]
            for point in points[1:]:
                elapsed = point["ms"] - previous
                if elapsed > 0:
                    page.wait_for_timeout(elapsed)
                send("touchMove", point)
                previous = point["ms"]
            send("touchEnd")
        except Error:
            try:
                send("touchCancel")
            except Error:
                pass
            raise
        finally:
            try:
                connection.detach()
            except Error:
                pass

    @staticmethod
    def open_login(page, platform):
        try:
            page.goto(LOGIN[platform], wait_until="domcontentloaded", timeout=8000)
        except TimeoutError:
            pass

    def start(self, owner, platform):
        if self.active:
            if self.active["owner"] != owner:
                raise Rejected("服务器正在处理另一个登录，请稍后重试", "409")
            if self.active["platform"] == platform:
                self.active["touched"] = time.monotonic()
                return self.frame(self.active)
            self.close()
        profile, lock, context, page = self.open_context(owner, platform, self.mobile)
        # Track an official login popup if the merchant opens one during verification.
        def on_page(child):
            if self.active and self.active["context"] is context:
                child.set_default_timeout(5000)
                self.active["page"] = child
                child.on("close", lambda: self.restore_page(context, child))
        context.on("page", on_page)
        now = time.monotonic()
        self.active = {
            "owner": owner, "platform": platform, "profile": profile, "lock": lock, "context": context,
            "page": page, "session": secrets.token_hex(24), "start": now, "touched": now,
        }
        try:
            if platform == "jd":
                # The desktop cart redirects to a desktop-only sign-in page.
                self.open_login(page, platform)
            else:
                try:
                    page.goto(CART[platform], wait_until="domcontentloaded", timeout=8000)
                except TimeoutError:
                    pass
                if urlsplit(page.url).hostname not in LOGIN_HOSTS[platform] and not self.cart_authenticated(page, platform):
                    self.open_login(page, platform)
            return self.frame(self.active)
        except (Error, Rejected):
            self.close()
            raise

    def open_context(self, owner, platform, options):
        profile = mounted_profile(owner[0], platform)
        lock = with_profile(profile)
        try:
            context = self.chromium.launch_persistent_context(
                str(profile), executable_path=os.environ.get("CHROMIUM"), headless=True,
                **options, accept_downloads=False,
                service_workers="block", locale="zh-CN", timezone_id="Asia/Shanghai",
                chromium_sandbox=os.environ.get("GXTODO_CHROME_SANDBOX") == "1",
                timeout=8000,
            )
        except (Error, TypeError):
            lock.close()
            raise

        def allow(route):
            req = route.request
            try:
                navigation = req.is_navigation_request() and req.frame.parent_frame is None
            except Error:
                navigation = False
            if official(req.url, platform, navigation, req.resource_type):
                route.continue_()
            else:
                route.abort()

        context.route("**/*", allow)
        page = context.pages[0] if context.pages else context.new_page()
        page.set_default_timeout(5000)
        return profile, lock, context, page

    def read_cart(self, owner, platform):
        if self.active:
            raise Rejected("请先退出当前平台登录窗口再加载购物车", "409")
        options = self.mobile if platform == "jd" else {"viewport": {"width": 1280, "height": 900}}
        _, lock, context, page = self.open_context(owner, platform, options)
        try:
            try:
                page.goto(CART[platform], wait_until="domcontentloaded", timeout=10000)
            except TimeoutError:
                pass
            page.wait_for_timeout(1600)
            row_selector = (".trade-cart-item-info" if platform == "taobao" else
                            ".item-form, .cart-item, #app :text-is('页面开小差，再试试')")
            try:
                page.locator(row_selector).first.wait_for(state="visible", timeout=6500)
            except TimeoutError:
                pass
            if urlsplit(page.url).hostname in LOGIN_HOSTS[platform]:
                raise Rejected("购物车会话未登录或已过期，请重新登录平台", "411")
            if not cart_location(page.url, platform):
                raise Rejected("未能打开官方购物车，请稍后重试")
            if not self.cart_authenticated(page, platform):
                raise Rejected("无法确认购物车已登录，请重新登录平台", "411")
            if platform == "jd" and page.get_by_text("页面开小差，再试试", exact=True).first.is_visible():
                raise Rejected("京东手机购物车暂时未返回商品，请稍后重试")
            rows = normalize(platform, page.evaluate(CART_ROWS))
        finally:
            try:
                context.close()
            finally:
                lock.close()
        return rows

    @staticmethod
    def cart_authenticated(page, platform):
        if not cart_location(page.url, platform):
            return False
        cookie_names = {cookie["name"] for cookie in page.context.cookies([page.url]) if cookie["value"]}
        if platform == "jd":
            return {"pt_pin", "pt_key"} <= cookie_names and not page.get_by_text("去登录", exact=True).first.is_visible()
        return {"unb", "cookie2"} <= cookie_names and not page.locator(".mini-login").first.is_visible()

    def cart_list(self, owner, platform):
        rows = self.read_cart(owner, platform)
        if not rows:
            raise Rejected("购物车暂未读到可确认 SKU 和数量的商品，请检查购物车后重试")
        self.cart = {key: value for key, value in self.cart.items() if value[:2] != (owner, platform)}
        expires = time.monotonic() + 300
        for row in rows:
            row.pop("unit_price", None)
            row.pop("price_label", None)
            selection = secrets.token_hex(24)
            self.cart[selection] = (owner, platform, expires, row)
            row["selection"] = selection
        return {"items": rows}

    def restore_page(self, context, child):
        active = self.active
        if active and active["context"] is context and active["page"] is child and context.pages:
            active["page"] = context.pages[-1]

    @staticmethod
    def frame(current):
        page = current["page"]
        page.wait_for_timeout(120)
        host = urlsplit(page.url).hostname or ""
        if not official(page.url, current["platform"], main_frame=True):
            raise Rejected("平台网页尚未打开，请重新加载")
        image = page.screenshot(type="jpeg", quality=82, timeout=6000)
        return {"session": current["session"], "image": base64.b64encode(image).decode(),
                "width": SIZE["width"], "height": SIZE["height"], "host": host,
                "can_save": host not in LOGIN_HOSTS[current["platform"]]}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _template, *_args):
        pass

    def do_GET(self):
        if self.path == "/health":
            self.respond({"status": "ok"})
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != "/login":
            self.send_error(404)
            return
        if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + self.server.token):
            self.send_error(403)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 8192:
                raise Rejected("登录请求过长")
            data = json.loads(self.rfile.read(length))
            result = self.server.browser.handle(data)
            self.respond({"status": "0", "data": result})
        except Rejected as error:
            self.respond({"status": error.code, "message": error.message})
        except (ValueError, KeyError, TypeError, Error, OSError):
            self.respond({"status": "1", "message": "暂时无法操作平台网页，请刷新或重新打开"})

    def respond(self, value):
        content = json.dumps(value, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(200)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except OSError:
            pass


class Server(HTTPServer):
    def service_actions(self):
        try:
            self.browser.expire()
        except (Error, OSError):
            pass


def main():
    os.umask(0o077)
    with sync_playwright() as playwright:
        server = Server(("0.0.0.0", 8790), Handler)
        server.token = Path(os.environ["GXTODO_PRICE_BROWSER_TOKEN_FILE"]).read_text().strip()
        if not re.fullmatch(r"[a-f0-9]{64}", server.token):
            raise SystemExit("Price browser secret is invalid")
        server.browser = Browser(playwright.chromium, playwright.devices["Pixel 7"])
        try:
            server.serve_forever()
        finally:
            server.browser.close()


if __name__ == "__main__":
    main()
