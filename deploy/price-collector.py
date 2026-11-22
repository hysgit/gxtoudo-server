"""Collect calibrated personal prices from isolated, manually signed-in browser profiles."""
import argparse
import getpass
import json
import os
import re
import time
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from urllib.error import URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

LOGIN = {"jd": "https://passport.jd.com/new/login.aspx",
         "taobao": "https://login.taobao.com/member/login.jhtml"}
HOSTS = {"jd": {"item.jd.com", "item.m.jd.com"},
         "taobao": {"item.taobao.com", "detail.tmall.com"}}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def api(origin, token, route, **values):
    request = Request(origin + "/api/v7/price/" + route,
                      data=json.dumps({"collector_token": token, **values}).encode(),
                      headers={"Content-Type": "application/json"})
    with build_opener(NoRedirect).open(request, timeout=20) as response:
        result = json.load(response)
    if result.get("status") != "0":
        raise RuntimeError(result.get("message", "服务端拒绝了请求"))
    return result.get("data")


def product(url, platform):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in HOSTS[platform] or parsed.username:
        return None
    if platform == "jd":
        match = re.fullmatch(r"/(?:product/)?([0-9]{3,24})\.html", parsed.path)
        return match.group(1) if match else None
    query = parse_qs(parsed.query)
    value = query.get("id", [""])[0]
    return value if re.fullmatch(r"[0-9]{1,24}", value) else None


def amount_of(value):
    raw = value.strip()
    if not re.fullmatch(r"(?:[¥￥]\s*)?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]{1,4})?", raw):
        return None
    amount = Decimal(raw.lstrip("¥￥").strip().replace(",", ""))
    return format(amount, "f") if 0 < amount < 10**16 else None


def read_offer(page, watch, rule):
    candidate = product(watch["url"], watch["platform"])
    required = ("account", "item", "variant", "quantity", "region", "total_label")
    markers = rule.get("markers", {})
    if not candidate or any(not markers.get(key) for key in required) or not rule.get("amount"):
        return {"state": "needs_verification"}
    response = page.goto(watch["url"], wait_until="domcontentloaded", timeout=25000)
    location = urlsplit(page.url)
    if location.hostname in {"passport.jd.com", "plogin.m.jd.com", "login.taobao.com", "login.m.taobao.com"}:
        return {"state": "login_required"}
    if not response or response.status >= 400 or product(page.url, watch["platform"]) != candidate:
        return {"state": "needs_verification"}
    selected_sku = parse_qs(urlsplit(watch["url"]).query).get("skuId")
    if selected_sku and parse_qs(location.query).get("skuId") != selected_sku:
        return {"state": "needs_verification"}
    for key in required:
        locator = page.locator(markers[key]["selector"])
        locator.first.wait_for(state="visible", timeout=4000)
        if locator.count() != 1:
            return {"state": "needs_verification"}
        if not locator.is_visible():
            return {"state": "needs_verification"}
        if locator.inner_text(timeout=3000).strip() != markers[key]["text"].strip():
            return {"state": "needs_verification"}
    price = page.locator(rule["amount"])
    if price.count() != 1 or not price.is_visible():
        return {"state": "needs_verification"}
    value = amount_of(price.inner_text(timeout=3000))
    if value is None:
        return {"state": "needs_verification"}
    details = rule.get("details", "").strip()
    if not details:
        return {"state": "needs_verification"}
    if rule.get("discounts"):
        discount = page.locator(rule["discounts"])
        if discount.count() == 1 and discount.is_visible():
            details += "；页面优惠：" + discount.inner_text(timeout=3000).strip()
    return {"state": "verified", "amount": value, "details": details[:500]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["login", "inspect", "run"])
    parser.add_argument("--platform", choices=LOGIN)
    parser.add_argument("--server", default="https://tudosrv.woslx.com")
    parser.add_argument("--profiles", type=Path, required=True,
                        help="此账号专用的新目录，勿使用日常 Chrome 用户目录")
    parser.add_argument("--rules", type=Path)
    parser.add_argument("--once", action="store_true", help="仅领取和核验一次")
    args = parser.parse_args()
    origin = args.server.rstrip("/")
    target = urlsplit(origin)
    if target.scheme != "https" or not target.hostname or target.username or target.path or target.fragment or target.query:
        parser.error("采集端必须连接明确的 HTTPS Todo 地址")
    profiles = args.profiles.expanduser().resolve()
    profiles.mkdir(mode=0o700, parents=True, exist_ok=True)
    if args.command == "login":
        if not args.platform:
            parser.error("登录时请选择 --platform jd 或 taobao")
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            context = p.chromium.launch_persistent_context(str(profiles / args.platform), headless=False)
            page = context.pages[0] if context.pages else context.new_page()
            page.goto(LOGIN[args.platform], wait_until="domcontentloaded")
            input("请在平台页面扫码登录并确认成功，再回到这里按 Enter：")
            context.close()
        return
    token = os.environ.get("GXTODO_COLLECTOR_TOKEN") or getpass.getpass("Todo 追踪配对码：")
    if args.command == "inspect":
        for row in api(origin, token, "inspect") or []:
            print(f"{row['id']} {row['name']} | {row['variant']} × {row['quantity']} | {row['region']} | {row['url']}")
        return
    if not args.rules:
        parser.error("请通过 --rules 指定已与你手机实付价核对的规则文件")
    rules = json.loads(args.rules.read_text(encoding="utf-8"))
    ids = [{"id": int(key)} for key in rules]
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        while True:
            try:
                watch = api(origin, token, "collect", watches=ids)
            except (URLError, TimeoutError):
                if args.once:
                    raise
                time.sleep(60)
                continue
            if not watch:
                if args.once:
                    return
                time.sleep(60)
                continue
            rule = rules[str(watch["id"])]
            if any(rule.get(key) != watch[key] for key in ("url", "variant", "quantity", "region")):
                result = {"state": "needs_verification"}
            else:
                context = None
                try:
                    context = p.chromium.launch_persistent_context(str(profiles / watch["platform"]), headless=True)
                    page = context.pages[0] if context.pages else context.new_page()
                    result = read_offer(page, watch, rule)
                except Exception:
                    result = {"state": "unavailable"}
                finally:
                    if context:
                        context.close()
            for attempt in range(3):
                try:
                    api(origin, token, "observe", watch_id=watch["id"], lease=watch["lease"], **result)
                    break
                except (URLError, TimeoutError):
                    if attempt == 2:
                        if args.once:
                            raise
                        print("暂时无法上报，服务端将在租约到期后重新安排", flush=True)
                    else:
                        time.sleep(5)
            else:
                time.sleep(60)
                continue
            print(f"{watch['id']} {result['state']}", flush=True)
            if args.once:
                return
            time.sleep(60)


if __name__ == "__main__":
    main()
