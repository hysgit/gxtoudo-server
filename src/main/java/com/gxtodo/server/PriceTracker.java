package com.gxtodo.server;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;
import static com.gxtodo.server.Accounts.token;

@Service
final class PriceTracker {
    private static final Pattern JD = Pattern.compile("/(?:product/)?([0-9]{3,24})\\.html");
    private static final Set<String> TAOBAO = Set.of("item.taobao.com", "detail.tmall.com");
    private static final Set<String> JD_HOSTS = Set.of("item.jd.com", "item.m.jd.com");

    Map<String, Object> list(Connection db, long user) throws SQLException {
        var watches = query(db, """
                SELECT w.id,w.platform,w.url,w.name,w.variant,w.sku_id,w.quantity,w.region,w.state,w.next_check,
                       p.amount::text,p.previous::text,p.details,p.observed_at
                FROM price_watches w LEFT JOIN LATERAL (
                    SELECT amount,previous,details,observed_at FROM price_observations
                    WHERE watch_id=w.id ORDER BY id DESC LIMIT 1
                ) p ON true WHERE w.user_id=? AND NOT w.deleted ORDER BY w.id DESC
                """, user);
        var collector = one(db, "SELECT last_seen FROM price_collectors WHERE user_id=?", user);
        return map("data", watches, "collector_paired", collector != null,
                "collector_seen", collector == null ? 0 : collector.get("last_seen"));
    }

    Map<String, Object> history(Connection db, long user, long watch) throws SQLException {
        return map("data", query(db, """
                SELECT p.amount::text,p.previous::text,p.details,p.observed_at FROM price_observations p
                JOIN price_watches w ON w.id=p.watch_id
                WHERE w.user_id=? AND w.id=? AND NOT w.deleted ORDER BY p.id DESC LIMIT 100
                """, user, watch));
    }

    Map<String, Object> create(Connection db, long user, Map<String, Object> params) throws SQLException {
        String raw = string(params.get("url"), 2048, true).strip();
        URI url;
        try { url = URI.create(raw); } catch (IllegalArgumentException error) { throw new ApiException(1, "商品链接格式错误"); }
        String host = url.getHost();
        if (host == null || !"https".equals(url.getScheme()) || url.getUserInfo() != null || url.getPort() != -1) {
            throw new ApiException(1, "请输入京东或淘宝的 HTTPS 商品详情链接");
        }
        String platform, canonical, sku;
        var match = JD.matcher(url.getPath() == null ? "" : url.getPath());
        if (JD_HOSTS.contains(host) && match.matches()) {
            platform = "jd";
            sku = match.group(1);
            canonical = "https://item.jd.com/" + sku + ".html";
        } else if (TAOBAO.contains(host) && ("/item.htm".equals(url.getPath()) || "/item.html".equals(url.getPath()))) {
            platform = "taobao";
            String item = numericQuery(url, "id", true);
            sku = numericQuery(url, "skuId", false);
            canonical = "https://" + host + "/item.htm?id=" + item + (sku.isEmpty() ? "" : "&skuId=" + sku);
        } else throw new ApiException(1, "请在浏览器打开分享链接后复制商品详情页地址");
        if (params.containsKey("sku_id") && (sku.isBlank() || !sku.equals(text(params, "sku_id")))) {
            throw new ApiException(1, "无法确认购物车选中的 SKU，请重新加载购物车");
        }
        String name = string(params.get("name"), 120, true).strip();
        String variant = string(params.get("variant"), 150, true).strip();
        String region = string(params.get("region"), 120, true).strip();
        int qty = (int) number(params.get("quantity"), 1, 100);
        if (id(one(db, "SELECT COUNT(*) AS n FROM price_watches WHERE user_id=? AND NOT deleted", user), "n") >= 30)
            throw new ApiException(1, "最多追踪 30 件商品");
        var duplicate = one(db, """
                SELECT id FROM price_watches WHERE user_id=? AND url=?
                  AND (?<>'' OR variant=?) AND quantity=? AND region=? AND NOT deleted
                """, user, canonical, sku, variant, qty, region);
        if (duplicate != null) return duplicate;
        return map("id", insert(db, """
                INSERT INTO price_watches(user_id,platform,url,name,variant,quantity,region,sku_id) VALUES(?,?,?,?,?,?,?,?)
                """, user, platform, canonical, name, variant, qty, region, sku));
    }

    private static String numericQuery(URI uri, String key, boolean required) {
        for (String pair : Objects.toString(uri.getRawQuery(), "").split("&")) {
            if (pair.startsWith(key + "=") && pair.substring(key.length()+1).matches("[0-9]{1,24}"))
                return pair.substring(key.length()+1);
        }
        if (required) throw new ApiException(1, "商品链接缺少商品编号");
        return "";
    }

    Map<String, Object> pair(Connection db, long user) throws SQLException {
        String token = token();
        execute(db, """
                INSERT INTO price_collectors(user_id,token_hash) VALUES(?,?)
                ON CONFLICT(user_id) DO UPDATE SET token_hash=excluded.token_hash,last_seen=0
                """, user, digest(token));
        execute(db, "UPDATE price_watches SET lease='',lease_until=0 WHERE user_id=?", user);
        return map("collector_token", token);
    }

    void remove(Connection db, long user, long watch) throws SQLException {
        execute(db, "UPDATE price_watches SET deleted=true,lease='',lease_until=0 WHERE user_id=? AND id=?", user, watch);
    }

    Map<String, Object> collector(Connection db, String route, Map<String, Object> params) throws SQLException {
        String credential = string(params.getOrDefault("collector_token", ""), 128, true);
        var collector = one(db, """
                SELECT c.user_id FROM price_collectors c JOIN users u ON c.user_id=u.id AND u.inactive=0
                WHERE c.token_hash=?
                """, digest(credential));
        if (collector == null) throw new ApiException(5, "采集端连接已解除，请重新配对");
        long user = id(collector, "user_id"), now = Instant.now().getEpochSecond();
        execute(db, "UPDATE price_collectors SET last_seen=? WHERE user_id=?", now, user);
        return switch (route) {
            case "/price/inspect" -> list(db, user);
            case "/price/collect" -> claim(db, user, now, params);
            default -> observe(db, user, now, params);
        };
    }

    private Map<String, Object> claim(Connection db, long user, long now, Map<String, Object> params) throws SQLException {
        var watched = objects(params.getOrDefault("watches", List.of()));
        if (watched.size() > 30) throw new ApiException(1, "采集商品数量超出限制");
        Set<Long> ids = new HashSet<>();
        for (var row : watched) ids.add(number(row.get("id"), 1, Long.MAX_VALUE));
        for (var row : query(db, """
                SELECT id,platform,url,variant,sku_id,quantity,region,name FROM price_watches
                WHERE user_id=? AND NOT deleted AND next_check<=? AND lease_until<=? ORDER BY next_check,id
                """, user, now, now)) {
            long watch = id(row, "id");
            if (!ids.contains(watch)) continue;
            String lease = token();
            execute(db, "UPDATE price_watches SET lease=?,lease_until=? WHERE id=?", lease, now + 180, watch);
            row.put("lease", lease);
            return map("data", row);
        }
        return map("data", map());
    }

    private Map<String, Object> observe(Connection db, long user, long now, Map<String, Object> params) throws SQLException {
        long watch = number(params.get("watch_id"), 1, Long.MAX_VALUE);
        String lease = string(params.get("lease"), 128, true);
        var row = one(db, "SELECT * FROM price_watches WHERE id=? AND user_id=? AND NOT deleted", watch, user);
        if (row == null) throw new ApiException(3, "没有此追踪项");
        if (one(db, "SELECT id FROM price_observations WHERE watch_id=? AND request_id=?", watch, lease) != null)
            return map();
        if (!lease.equals(text(row, "lease")) || id(row, "lease_until") < now)
            throw new ApiException(1, "采集请求已过期");
        String state = text(params, "state");
        if (!Set.of("verified", "login_required", "unavailable", "needs_verification").contains(state))
            throw new ApiException(1, "采集状态无效");
        if (state.equals("verified")) {
            String money = string(params.get("amount"), 24, true);
            if (!money.matches("[0-9]{1,16}(\\.[0-9]{1,4})?")) throw new ApiException(1, "个人应付价格式错误");
            var amount = new BigDecimal(money);
            if (amount.signum() <= 0) throw new ApiException(1, "应付价必须大于零");
            String details = string(params.get("details"), 500, true);
            var last = one(db, "SELECT amount FROM price_observations WHERE watch_id=? ORDER BY id DESC LIMIT 1", watch);
            Object previous = last == null ? null : last.get("amount");
            execute(db, """
                    INSERT INTO price_observations(watch_id,request_id,amount,previous,details,observed_at)
                    VALUES(?,?,?,?,?,?)
                    """, watch, lease, amount, previous, details, now);
        }
        execute(db, "UPDATE price_watches SET state=?,next_check=?,lease='',lease_until=0 WHERE id=?", state, now + 1800, watch);
        return map();
    }
}
