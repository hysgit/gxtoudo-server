"""Read displayed cart lines and keep the exact SKU picked in the merchant cart."""
import re
from decimal import Decimal
from urllib.parse import parse_qs, urlsplit

CART = {
    "jd": "https://cart.jd.com/cart.action",
    "taobao": "https://cart.taobao.com/cart.htm",
}

# Limit extraction to the displayed product row. Do not read page-wide text or checkout details.
CART_ROWS = r"""() => {
  const product = a => {
    try {
      const u = new URL(a.href);
      return ((u.hostname === 'item.jd.com' || u.hostname === 'item.m.jd.com') &&
        /^\/(?:product\/)?[0-9]+\.html$/.test(u.pathname)) ||
        (['item.taobao.com','detail.tmall.com','item.m.taobao.com','detail.m.tmall.com'].includes(u.hostname)
          && /^[0-9]+$/.test(u.searchParams.get('id') || ''));
    } catch { return false; }
  };
  const clean = v => (v || '').replace(/\s+/g, ' ').trim();
  const quantitySelectors = '[data-quantity],[data-count],.quantity-form input,.itxt,.item-amount input,' +
    '[class*="quantity" i] input,[class*="amount" i] input,[class*="number-picker" i] input,' +
    '[class*="quantity" i] [class*="value" i],input[type=number],[role=spinbutton],[aria-label="数量"]';
  const specSelectors = '.trade-cart-item-sku-old,.p-props,.sku-info,.sku_info,.item-props,.item-props-can,[data-sku-text],' +
    '[class*="sku-info" i],[class*="skuInfo"],[class*="skuText"],[class*="sku-prop" i],[class*="prop-item" i]';
  const skuSelectors = '[data-sku-id],[data-skuid],[data-sku],[data-sku_id]';
  const quantity = row => {
    const values = [];
    for (const el of row.querySelectorAll(quantitySelectors)) {
      const amount = clean(el.value || el.getAttribute('data-quantity') ||
        el.getAttribute('data-count') || el.getAttribute('aria-valuenow') || el.textContent);
      if (/^[1-9][0-9]{0,2}$/.test(amount)) values.push({el, amount});
    }
    const concrete = values.filter(({el}) => !values.some(other => el !== other.el && el.contains(other.el)));
    return concrete.length === 1 ? concrete[0].amount : '';
  };
  const variants = row => [...new Set([...row.querySelectorAll(specSelectors)].map(el =>
    clean(el.getAttribute('data-sku-text') || el.title || el.innerText)).filter(Boolean))].join(' / ').slice(0,150);
  const sku = row => {
    const ids = new Set();
    const nodes = [row, ...row.querySelectorAll(skuSelectors)];
    for (const el of nodes) for (const key of ['data-sku-id','data-skuid','data-sku','data-sku_id']) {
      const v = el.getAttribute(key);
      if (/^[0-9]{1,24}$/.test(v || '')) ids.add(v);
    }
    return ids.size === 1 ? [...ids][0] : '';
  };
  const key = a => {
    const u = new URL(a.href);
    return (u.searchParams.get('id') || u.pathname.match(/[0-9]+/)?.[0] || '') +
      ':' + (u.searchParams.get('skuId') || u.searchParams.get('skuid') || '');
  };
  const shownPrice = row => {
    const box = row.querySelector('.trade-cart-item-price');
    if (!box) return {};
    const visible = el => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden';
    const prices = [];
    for (const part of box.querySelectorAll('.trade-price-container')) {
      if (!visible(part) || part.closest('del,s,strike') ||
          getComputedStyle(part).textDecorationLine.includes('line-through')) continue;
      const label = clean(part.querySelector('.trade-price-label')?.innerText);
      if (label && !/^(秒杀价|平台加补后|店铺优惠后|优惠后|券后价|券后|活动价|到手价)$/.test(label)) continue;
      const units = clean(part.querySelector('.trade-price-integer')?.innerText).replace(/,/g, '');
      const cents = clean(part.querySelector('.trade-price-decimal')?.innerText);
      const currency = clean(part.querySelector('.trade-price-symbol')?.innerText);
      if (!/^[0-9]{1,16}$/.test(units) || (cents && !/^[0-9]{1,4}$/.test(cents)) ||
          (currency !== '¥' && currency !== '￥')) continue;
      const primary = !!label || part.classList.contains('type-of-12-14B-16B')
        || !!part.closest('[class^="priceDetail--"],[class*=" priceDetail--"]');
      prices.push({amount: units + (cents ? '.' + cents : ''), label, primary});
    }
    const primary = prices.filter(item => item.primary);
    const eligible = primary.length ? primary : prices;
    if (eligible.length !== 1) return {};
    return {unit_price: eligible[0].amount, price_label: eligible[0].label};
  };
  const rows = [];
  for (const row of document.querySelectorAll('.trade-cart-item-info')) {
    if (!row.getClientRects().length || /已下架|商品失效|已失效/.test(row.innerText)) continue;
    const anchors = [...row.querySelectorAll('a[href]')].filter(product);
    const link = anchors.find(a => new URL(a.href).searchParams.has('skuId') && clean(a.innerText));
    const qtyBox = row.querySelector('.trade-cart-item-quantity');
    const numberFields = qtyBox ? [...qtyBox.querySelectorAll('input')].filter(el => el.getClientRects().length) : [];
    const fixedQuantity = qtyBox?.querySelector('[class*="quantityNumDisabled"]');
    const qty = numberFields.length === 1 ? clean(numberFields[0].value) :
      numberFields.length === 0 && fixedQuantity?.getClientRects().length ? clean(fixedQuantity.innerText) : '';
    if (!link || !/^[1-9][0-9]{0,2}$/.test(qty)) continue;
    const selected = row.querySelector('.trade-cart-item-sku-old');
    rows.push({url: link.href, name: clean(link.innerText).slice(0,120), quantity: qty,
      variant: clean(selected?.innerText).slice(0,150), sku_id: '', ...shownPrice(row)});
  }
  for (const a of document.querySelectorAll('a[href]')) {
    if (a.closest('.trade-cart-item-info')) continue;
    if (!product(a) || !a.getClientRects().length) continue;
    for (let row = a, depth = 0; row && depth < 10; row = row.parentElement, depth++) {
      if ([...row.querySelectorAll('a[href]')].some(other => product(other) && key(other) !== key(a))) break;
      const qty = quantity(row);
      if (!qty) continue;
      const named = [...row.querySelectorAll('a[href]')].find(v => product(v) &&
        (clean(v.innerText) || clean(v.title)));
      const name = clean(named?.innerText || named?.title || a.title || a.getAttribute('aria-label') ||
        a.querySelector('img[alt]')?.alt);
      const selectedSku = sku(row);
      if (!name || /已下架|商品失效|已失效/.test(row.innerText)) break;
      rows.push({url: a.href, name: name.slice(0,120), quantity: qty, variant: variants(row), sku_id: selectedSku});
      break;
    }
    if (rows.length >= 250) break;
  }
  return rows;
}"""


def normalize(platform, candidates):
    accepted = {}
    for source in candidates:
        url = urlsplit(source["url"])
        if url.scheme not in {"http", "https"} or url.username or url.port is not None:
            continue
        if platform == "jd" and url.hostname in {"item.jd.com", "item.m.jd.com"}:
            found = re.fullmatch(r"/(?:product/)?([0-9]{3,24})\.html", url.path)
            if not found:
                continue
            product = sku = found[1]
            canonical = f"https://item.jd.com/{sku}.html"
        elif platform == "taobao" and url.hostname in {
            "item.taobao.com", "detail.tmall.com", "item.m.taobao.com", "detail.m.tmall.com"
        }:
            query = parse_qs(url.query)
            product = query.get("id", [""])[0]
            sku = query.get("skuId", query.get("skuid", [source.get("sku_id", "")]))[0]
            if not all(re.fullmatch(r"[0-9]{1,24}", value) and int(value) > 0 for value in (product, sku)):
                continue
            site = "detail.tmall.com" if url.hostname.endswith("tmall.com") else "item.taobao.com"
            canonical = f"https://{site}/item.htm?id={product}&skuId={sku}"
        else:
            continue
        qty = int(source["quantity"])
        if not 1 <= qty <= 100:
            continue
        identity = (product, sku)
        spec = source.get("variant") or f"SKU {sku}"
        previous = accepted.get(identity)
        if previous and len(previous["variant"]) > len(spec):
            continue
        accepted[identity] = {"name": source["name"], "variant": spec, "quantity": qty,
                              "sku_id": sku, "url": canonical}
        unit = source.get("unit_price", "")
        if re.fullmatch(r"[0-9]{1,16}(\.[0-9]{1,4})?", unit or ""):
            line = Decimal(unit) * qty
            if 0 < line < 10 ** 16:
                accepted[identity]["unit_price"] = unit
                accepted[identity]["price_label"] = source.get("price_label", "")
    return list(accepted.values())[:100]


def matching_prices(rows, targets):
    keys = {(item["url"], item["sku_id"], item["quantity"]) for item in targets}
    result = []
    for row in rows:
        unit = row.get("unit_price")
        key = (row["url"], row["sku_id"], row["quantity"])
        if key not in keys or not unit:
            continue
        amount = format(Decimal(unit) * row["quantity"], "f")
        label = row.get("price_label")
        source = f"购物车显示价（{label}）" if label else "购物车显示价"
        result.append({"url": row["url"], "sku_id": row["sku_id"], "quantity": row["quantity"],
                       "amount": amount,
                       "details": f"{source}，单价 ¥{unit} × {row['quantity']}。未核验结算优惠和运费。"})
    return result
