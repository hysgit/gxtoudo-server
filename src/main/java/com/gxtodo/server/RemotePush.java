package com.gxtodo.server;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class RemotePush {
    private record Job(Map<String, Object> row, PushSchedule.Due due) {}
    private final Database storage;
    private final PushProviders providers;
    private final boolean workerEnabled;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);

    RemotePush(Database storage, PushProviders providers, org.springframework.core.env.Environment env) {
        this.storage = storage;
        this.providers = providers;
        workerEnabled = env.getProperty("GXTODO_PUSH_WORKER", Boolean.class, true);
    }

    Map<String, Object> register(Connection db, Map<String, Object> user, Map<String, Object> params) throws SQLException {
        String provider = text(params, "provider");
        String token = string(params.get("push_token"), 4096, true);
        String zone = string(params.get("timezone"), 128, true);
        try { ZoneId.of(zone); }
        catch (java.time.DateTimeException error) { throw new ApiException(1, "设备时区无效"); }
        boolean valid = switch (provider) {
            case "apns", "apns_sandbox" -> token.matches("(?i)[0-9a-f]{64,256}");
            case "fcm" -> token.matches("[A-Za-z0-9_.:-]{24,4096}");
            default -> false;
        };
        if (!valid) throw new ApiException(1, "设备推送标识无效");
        long login = id(user, "login_id");
        execute(db, "DELETE FROM push_devices WHERE provider=? AND token=? AND login_id<>?", provider, token, login);
        execute(db, """
                INSERT INTO push_devices(login_id,provider,token,timezone) VALUES(?,?,?,?)
                ON CONFLICT(login_id) DO UPDATE SET provider=excluded.provider,token=excluded.token,timezone=excluded.timezone
                """, login, provider, token, zone);
        return map("configured", providers.available(provider));
    }

    @Scheduled(fixedDelay = 10000)
    void tick() {
        if (!workerEnabled || !providers.available("apns") && !providers.available("fcm")) return;
        long now = Instant.now().getEpochSecond();
        var jobs = storage.transaction(false, db -> due(db, now));
        for (int i = 0; i < jobs.size(); i += 8) {
            var batch = jobs.subList(i, Math.min(i + 8, jobs.size()));
            CompletableFuture.allOf(batch.stream().map(job -> CompletableFuture.runAsync(
                    () -> deliver(job), workers)).toArray(CompletableFuture[]::new)).join();
        }
    }

    private List<Job> due(Connection db, long now) throws SQLException {
        var rows = query(db, """
                SELECT p.*, s.user_id, a.team_id, a.uid AS alert_id, a.revision AS alert_rev, a.data AS alert,
                       t.uid AS task_id, t.revision AS task_rev, t.data AS task, sc.uid AS schedule_id,
                       sc.revision AS schedule_rev, sc.data AS schedule, r.uid AS repeat_id,
                       r.revision AS repeat_rev, r.data AS recurrence
                FROM push_devices p JOIN sessions s ON s.id=p.login_id AND s.expires>?
                JOIN users u ON u.id=s.user_id AND u.inactive=0
                JOIN members m ON m.user_id=s.user_id
                JOIN teams space ON space.id=m.team_id AND space.status=0
                JOIN objects a ON a.team_id=m.team_id AND a.table_id=77 AND a.mode<>3
                    AND a.data::jsonb->>'user_id'=s.user_id::text
                    AND coalesce(a.data::jsonb->>'reminder_mode','both')<>'alarm'
                JOIN objects t ON t.team_id=a.team_id AND t.table_id=89 AND t.mode<>3
                    AND t.uid=a.data::jsonb->>'task_id'
                JOIN objects sc ON sc.team_id=a.team_id AND sc.table_id=79 AND sc.mode<>3
                    AND sc.uid=a.data::jsonb->>'schedule_id'
                LEFT JOIN LATERAL (
                    SELECT uid, revision, data FROM objects
                    WHERE team_id=a.team_id AND table_id=78 AND mode<>3 AND data::jsonb->>'task_id'=t.uid
                    ORDER BY revision DESC LIMIT 1
                ) r ON TRUE
                  WHERE (r.uid IS NOT NULL OR
                    CASE WHEN sc.data::jsonb->>'start_at' ~ '^[0-9]{1,12}$'
                      THEN (sc.data::jsonb->>'start_at')::bigint BETWEEN ? AND ?
                      ELSE FALSE END OR
                    CASE WHEN a.data::jsonb->>'alert_time' ~ '^[0-9]{7,12}$'
                      THEN (a.data::jsonb->>'alert_time')::bigint BETWEEN ? AND ?
                      ELSE FALSE END)
                """, now, now - 100000, now + 864000, now - PushSchedule.WINDOW, now);
        var jobs = new ArrayList<Job>();
        for (var row : rows) {
            if (!providers.available(text(row, "provider"))) continue;
            var recurrence = row.get("recurrence") == null ? Map.<String, Object>of() : object(row.get("recurrence"));
            for (var due : PushSchedule.due(object(row.get("schedule")), object(row.get("alert")), recurrence,
                    ZoneId.of(text(row, "timezone")), now)) jobs.add(new Job(row, due));
        }
        return jobs;
    }

    private void deliver(Job job) {
        var row = job.row();
        var due = job.due();
        long now = Instant.now().getEpochSecond();
        if (now >= due.at() + PushSchedule.WINDOW || !storage.transaction(true, db -> claim(db, job, now))) return;
        String tag = digest(json(List.of(row.get("team_id"), row.get("task_id"), due.occurrence(), due.at())));
        PushProviders.Result outcome;
        try {
            String name = text(object(row.get("task")), "name");
            if (name.isBlank()) name = "高效Todo";
            if (name.codePointCount(0, name.length()) > 200) name = name.substring(0, name.offsetByCodePoints(0, 200));
            outcome = providers.send(text(row, "provider"), text(row, "token"), tag, name, now,
                    due.at() + PushSchedule.WINDOW);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            outcome = PushProviders.Result.RETRY;
        }
        var result = outcome;
        storage.transaction(true, db -> {
            if (result == PushProviders.Result.INVALID_TOKEN) {
                execute(db, "DELETE FROM push_devices WHERE login_id=? AND token=?", row.get("login_id"), row.get("token"));
            }
            execute(db, """
                    UPDATE push_deliveries SET finished=?, retry_at=?
                    WHERE login_id=? AND team_id=? AND task_id=? AND occurrence=? AND remind_at=? AND retry_at=?
                    """, result != PushProviders.Result.RETRY, Instant.now().getEpochSecond() + 30,
                    row.get("login_id"), row.get("team_id"), row.get("task_id"), due.occurrence(), due.at(), now + 60);
            return null;
        });
    }

    private boolean claim(Connection db, Job job, long now) throws SQLException {
        var row = job.row();
        var due = job.due();
        if (one(db, """
                SELECT 1 FROM push_devices p JOIN sessions s ON s.id=p.login_id AND s.expires>?
                JOIN users u ON u.id=s.user_id AND u.inactive=0
                JOIN members m ON m.user_id=s.user_id AND m.team_id=?
                JOIN teams t ON t.id=m.team_id AND t.status=0
                WHERE p.login_id=? AND p.provider=? AND p.token=? AND p.timezone=?
                """, now, row.get("team_id"), row.get("login_id"), row.get("provider"), row.get("token"),
                row.get("timezone")) == null) return false;
        long team = id(row, "team_id");
        if (!current(db, team, 77, row, "alert") || !current(db, team, 89, row, "task")
                || !current(db, team, 79, row, "schedule")) return false;
        var repeat = one(db, """
                SELECT uid, revision FROM objects WHERE team_id=? AND table_id=78 AND mode<>3
                AND data::jsonb->>'task_id'=? ORDER BY revision DESC LIMIT 1
                """, team, row.get("task_id"));
        if (repeat == null ? row.get("repeat_rev") != null
                : !repeat.get("revision").equals(row.get("repeat_rev"))) return false;
        if (one(db, """
                SELECT 1 FROM objects WHERE team_id=? AND table_id=76 AND mode<>3
                AND data::jsonb->>'task_id'=? AND data::jsonb->>'user_id'=? AND data::jsonb->>'status'='1'
                AND (? OR data::jsonb->>'start_at'=?)
                """, team, row.get("task_id"), text(row, "user_id"), repeat == null, Long.toString(due.occurrence())) != null) {
            return false;
        }
        return execute(db, """
                INSERT INTO push_deliveries(login_id,team_id,task_id,occurrence,remind_at,retry_at) VALUES(?,?,?,?,?,?)
                ON CONFLICT(login_id,team_id,task_id,occurrence,remind_at) DO UPDATE
                SET attempts=push_deliveries.attempts+1,retry_at=excluded.retry_at
                WHERE NOT push_deliveries.finished AND push_deliveries.attempts<3 AND push_deliveries.retry_at<=?
                """, row.get("login_id"), team, row.get("task_id"), due.occurrence(), due.at(), now + 60, now) == 1;
    }

    private boolean current(Connection db, long team, int table, Map<String, Object> row, String prefix) throws SQLException {
        return one(db, "SELECT 1 FROM objects WHERE team_id=? AND table_id=? AND uid=? AND revision=? AND mode<>3",
                team, table, row.get(prefix + "_id"), row.get(prefix + "_rev")) != null;
    }

    @Scheduled(fixedDelay = 3600000)
    void clean() {
        if (!workerEnabled) return;
        storage.transaction(true, db -> {
            execute(db, "DELETE FROM push_deliveries WHERE remind_at<?", Instant.now().getEpochSecond() - 86400);
            execute(db, "DELETE FROM push_devices WHERE login_id IN (SELECT id FROM sessions WHERE expires<=?)",
                    Instant.now().getEpochSecond());
            return null;
        });
    }

    @PreDestroy void close() { workers.shutdownNow(); }
}
