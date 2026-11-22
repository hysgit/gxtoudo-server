package com.gxtodo.server;

import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class PriceAlerts {
    private final Database storage;
    private final PushProviders providers;
    private final boolean enabled;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private Future<?> sending;

    PriceAlerts(Database storage, PushProviders providers, Environment env) {
        this.storage = storage;
        this.providers = providers;
        enabled = env.getProperty("GXTODO_PUSH_WORKER", Boolean.class, true);
    }

    @Scheduled(fixedDelay = 12000)
    synchronized void schedule() {
        if (!enabled || !providers.available("apns") && !providers.available("fcm")
                || sending != null && !sending.isDone()) return;
        sending = sender.submit(this::tick);
    }

    void tick() {
        long now = Instant.now().getEpochSecond();
        var outstanding = storage.transaction(false, db -> query(db, """
                SELECT w.name,w.platform,o.id,o.amount,o.previous,o.observed_at,d.provider,d.token,d.login_id
                FROM price_observations o JOIN price_watches w ON o.watch_id=w.id AND NOT w.deleted
                JOIN users u ON u.id=w.user_id AND u.inactive=0
                JOIN sessions s ON s.user_id=w.user_id AND s.expires>?
                JOIN push_devices d ON d.login_id=s.id
                LEFT JOIN price_notifications n ON n.observation_id=o.id AND n.login_id=d.login_id
                WHERE o.amount<o.previous AND o.observed_at>?
                  AND (n.observation_id IS NULL OR NOT n.finished AND n.attempts<3 AND n.retry_at<=?)
                  AND (d.provider='fcm' AND ? OR d.provider IN ('apns','apns_sandbox') AND ?)
                ORDER BY o.id LIMIT 40
                """, now, now - 86400, now, providers.available("fcm"), providers.available("apns")));
        for (var row : outstanding) {
            if (Thread.currentThread().isInterrupted()) return;
            deliver(row, Instant.now().getEpochSecond());
        }
    }

    private void deliver(Map<String, Object> row, long now) {
        var observation = row.get("id");
        var login = row.get("login_id");
        if (!storage.transaction(true, db -> execute(db, """
                INSERT INTO price_notifications(observation_id,login_id,retry_at)
                SELECT o.id,p.login_id,? FROM price_observations o
                JOIN price_watches w ON w.id=o.watch_id AND NOT w.deleted
                JOIN users u ON u.id=w.user_id AND u.inactive=0
                JOIN sessions s ON s.user_id=w.user_id AND s.expires>?
                JOIN push_devices p ON p.login_id=s.id
                WHERE o.id=? AND p.login_id=? AND p.token=?
                ON CONFLICT(observation_id,login_id) DO UPDATE
                SET attempts=price_notifications.attempts+1,retry_at=excluded.retry_at
                WHERE NOT price_notifications.finished AND price_notifications.attempts<3 AND price_notifications.retry_at<=?
                """, now + 90, now, observation, login, row.get("token"), now) == 1)) return;
        PushProviders.Result outcome;
        String platform = text(row, "platform").equals("jd") ? "京东" : "淘宝";
        String title = text(row, "name") + " 降价了";
        String body = platform + "个人应付价 ¥" + money(row.get("previous")) + " → ¥" + money(row.get("amount"));
        try {
            outcome = providers.sendEvent(text(row, "provider"), text(row, "token"),
                    digest("price:" + observation), title, body, now, Math.min(id(row, "observed_at") + 86400, now + 3600));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            outcome = PushProviders.Result.RETRY;
        }
        var result = outcome;
        storage.transaction(true, db -> {
            if (result == PushProviders.Result.INVALID_TOKEN)
                execute(db, "DELETE FROM push_devices WHERE login_id=? AND token=?", login, row.get("token"));
            execute(db, """
                    UPDATE price_notifications SET finished=?,retry_at=?
                    WHERE observation_id=? AND login_id=? AND retry_at=?
                    """, result != PushProviders.Result.RETRY, now + 30, observation, login, now + 90);
            return null;
        });
    }

    private static String money(Object amount) {
        return ((BigDecimal) amount).stripTrailingZeros().toPlainString();
    }

    @PreDestroy void close() { sender.shutdownNow(); }
}
