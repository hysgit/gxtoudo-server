package com.gxtodo.server;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

import static com.gxtodo.server.Database.execute;
import static com.gxtodo.server.Protocol.*;

@Service
final class ApiService {
    private static final Set<String> UNCONFIGURED = Set.of("/user/open", "/user/upToken", "/open/move", "/vip",
            "/order/base", "/order/prepare", "/order/pay", "/order/check", "/order/code");
    private final Database storage;
    private final Accounts accounts;
    private final SyncService sync;
    private final Collaboration collaboration;
    private final ResetMail mail;
    private final RemotePush push;
    private final AiAssistant assistant;
    private final PriceTracker prices;
    private final PriceLogin priceLogin;

    ApiService(Database storage, Accounts accounts, SyncService sync, Collaboration collaboration, ResetMail mail,
               RemotePush push, AiAssistant assistant, PriceTracker prices, PriceLogin priceLogin) {
        this.storage = storage;
        this.accounts = accounts;
        this.sync = sync;
        this.collaboration = collaboration;
        this.mail = mail;
        this.push = push;
        this.assistant = assistant;
        this.prices = prices;
        this.priceLogin = priceLogin;
    }

    Map<String, Object> dispatch(String route, String method, Map<String, Object> params) {
        if (route.equals("/user/login") && method.equals("POST")) return accounts.login(params, false);
        if (route.equals("/user/base") && method.equals("POST")) return accounts.login(params, true);
        if (route.equals("/user/mail") && method.equals("POST")) {
            mail.validate();
            mail.send(accounts.issueReset(params));
            return map();
        }
        if (UNCONFIGURED.contains(route)) throw new ApiException(1, "该功能需要在自建实例配置第三方服务");
        if (method.equals("POST") && route.equals("/price/login")) {
            var user = storage.transaction(false, db -> Accounts.authenticate(db, params));
            return priceLogin.call(user, params);
        }
        if (method.equals("POST") && Set.of("/price/collect", "/price/observe", "/price/inspect").contains(route))
            return storage.transaction(true, db -> prices.collector(db, route, params));
        return storage.transaction(!method.equals("GET"), db -> {
            Map<String, Object> user = Accounts.authenticate(db, params);
            switch (route + " " + method) {
                case "/user/base GET": return map("data", Accounts.profile(user));
                case "/user/base PUT": return Accounts.updateProfile(db, user, params);
                case "/user/vipInfo GET": return map("data", map("is_vip", 0, "remaining_days", 0, "vip_expired_at", ""));
                case "/user/deactivate PUT":
                    execute(db, "UPDATE users SET inactive=1 WHERE id=?", user.get("id"));
                    execute(db, "UPDATE sessions SET expires=0 WHERE user_id=?", user.get("id"));
                    execute(db, "DELETE FROM reset_codes WHERE user_id=?", user.get("id"));
                    return map();
                case "/sync/do POST": return sync.synchronize(db, user, params);
                case "/ai/chat GET": return assistant.list(db, id(user, "id"), params);
                case "/ai/chat POST": return assistant.submit(db, id(user, "id"), params);
                case "/price/watch GET": return prices.list(db, id(user, "id"));
                case "/price/watch POST": return prices.create(db, id(user, "id"), params);
                case "/price/watch DELETE":
                    prices.remove(db, id(user, "id"), number(params.get("id"), 1, Long.MAX_VALUE));
                    return map();
                case "/price/history GET": return prices.history(db, id(user, "id"), number(params.get("id"), 1, Long.MAX_VALUE));
                case "/price/collector PUT": return prices.pair(db, id(user, "id"));
                case "/price/collector DELETE":
                    execute(db, "DELETE FROM price_collectors WHERE user_id=?", user.get("id"));
                    execute(db, "UPDATE price_watches SET lease='',lease_until=0 WHERE user_id=?", user.get("id"));
                    return map();
                case "/push/device PUT": return push.register(db, user, params);
                case "/push/device DELETE":
                    execute(db, "DELETE FROM push_devices WHERE login_id=?", user.get("login_id"));
                    return map();
                case "/team/base GET", "/team/base POST", "/team/base PUT":
                    return collaboration.saveTeam(db, user, params, method);
                case "/team/prepare GET": return collaboration.prepare(db, user, params);
                case "/team/list GET", "/team/list POST": return collaboration.teams(db, user, params);
                case "/team/user GET": return collaboration.members(db, user, params);
                case "/message/base GET", "/message/base POST", "/message/base PUT":
                    return collaboration.messages(db, user, params, method);
                case "/message/detail GET", "/message/detail POST": return collaboration.messageDetail(db, user, params);
                case "/comment/list POST": return collaboration.comments(db, user, params, false);
                case "/comment/create POST": return collaboration.comments(db, user, params, true);
                default: throw new ApiException(1, "接口或请求方法不支持");
            }
        });
    }
}
