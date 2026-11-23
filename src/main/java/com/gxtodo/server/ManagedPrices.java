package com.gxtodo.server;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.gxtodo.server.Accounts.token;
import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class ManagedPrices {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedPrices.class);
    private static final String DUE = """
            JOIN users u ON u.id=w.user_id AND u.inactive=0
            WHERE NOT w.deleted AND w.platform='taobao' AND w.sku_id<>''
              AND w.next_check<=? AND w.lease_until<=?
              AND NOT EXISTS (SELECT 1 FROM price_collectors c WHERE c.user_id=w.user_id AND c.last_seen>?)
              AND NOT EXISTS (SELECT 1 FROM price_observations p WHERE p.watch_id=w.id AND p.basis='payable_verified')
            """;
    private record Batch(long user, String platform, String lease, List<Map<String, Object>> watches) {}
    private record Key(String url, String sku, long quantity) {
        static Key from(Map<String, Object> row) {
            return new Key(text(row, "url"), text(row, "sku_id"), id(row, "quantity"));
        }
    }
    private final Database storage;
    private final PriceLogin browser;
    private final PriceTracker prices;
    private final boolean enabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> running;

    ManagedPrices(Database storage, PriceLogin browser, PriceTracker prices, Environment env) {
        this.storage = storage;
        this.browser = browser;
        this.prices = prices;
        enabled = env.getProperty("GXTODO_MANAGED_PRICE_WORKER", Boolean.class, true);
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 15000)
    synchronized void schedule() {
        if (!enabled || !browser.available() || running != null && !running.isDone()) return;
        running = executor.submit(() -> {
            try { tick(); }
            catch (RuntimeException error) { LOG.warn("Managed cart price check deferred ({})", error.getClass().getSimpleName()); }
        });
    }

    void tick() {
        long now = Instant.now().getEpochSecond();
        var first = storage.transaction(false, db -> one(db,
                "SELECT w.user_id,w.platform FROM price_watches w " + DUE + " ORDER BY w.next_check,w.id LIMIT 1",
                now, now, now - 300));
        if (first == null) return;
        var batch = storage.transaction(true, db -> claim(db, first, now));
        if (batch.watches().isEmpty()) return;

        Map<Key, Map<String, Object>> quotes = new HashMap<>();
        String failure = "needs_verification";
        long retry = PriceTracker.CHECK_INTERVAL;
        try {
            var result = browser.cartPrices(batch.user(), batch.platform(), batch.watches());
            for (var quote : objects(result.get("quotes"))) quotes.put(Key.from(quote), quote);
        } catch (ApiException error) {
            switch (error.status) {
                case "409" -> { failure = ""; retry = 45; }
                case "411" -> failure = "login_required";
                default -> failure = "unavailable";
            }
        }
        final String failedState = failure;
        final long delay = retry;
        long observed = Instant.now().getEpochSecond();
        storage.transaction(true, db -> {
            for (var watch : batch.watches()) {
                var watched = watch.get("id");
                // Only the current lease may write, and a deleted watch stays deleted.
                if (one(db, """
                        SELECT 1 FROM price_watches w JOIN users u ON u.id=w.user_id AND u.inactive=0
                        WHERE w.id=? AND w.user_id=? AND w.lease=? AND w.lease_until>=? AND NOT w.deleted
                        """, watched, batch.user(), batch.lease(), observed) == null) continue;
                var quote = quotes.get(Key.from(watch));
                if (quote == null) {
                    execute(db, """
                            UPDATE price_watches SET state=COALESCE(NULLIF(?,''),state),next_check=?,
                                last_checked=CASE WHEN ?='' THEN last_checked ELSE ? END,
                                lease='',lease_until=0 WHERE id=? AND lease=?
                            """, failedState, observed + delay, failedState, observed, watched, batch.lease());
                    continue;
                }
                prices.observe(db, batch.user(), observed, map("watch_id", watched, "lease", batch.lease(),
                        "state", "verified", "amount", quote.get("amount"), "details", quote.get("details")), "cart_display");
            }
            return null;
        });
    }

    private Batch claim(Connection db, Map<String, Object> target, long now) throws SQLException {
        long user = id(target, "user_id");
        String platform = text(target, "platform");
        String lease = token();
        var selected = query(db, "SELECT w.id,w.url,w.sku_id,w.quantity FROM price_watches w " + DUE
                + " AND w.user_id=? AND w.platform=? ORDER BY w.next_check,w.id LIMIT 30",
                now, now, now - 300, user, platform);
        var claimed = new ArrayList<Map<String, Object>>();
        for (var watch : selected) {
            if (execute(db, """
                    UPDATE price_watches SET lease=?,lease_until=?
                    WHERE id=? AND user_id=? AND next_check<=? AND lease_until<=? AND NOT deleted
                    """, lease, now + 90, watch.get("id"), user, now, now) == 1) claimed.add(watch);
        }
        return new Batch(user, platform, lease, claimed);
    }

    @PreDestroy void close() { executor.shutdownNow(); }
}
