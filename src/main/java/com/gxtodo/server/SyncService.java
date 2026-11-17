package com.gxtodo.server;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class SyncService {
    private static final Set<Integer> PRIVATE = Set.of(49, 77);
    private static final Set<Integer> MEMBER_TARGETS = Set.of(76, 83, 88, 98);

    static Map<String, Object> lookup(Connection db, long team, int table, String uid) throws SQLException {
        return one(db, "SELECT * FROM objects WHERE team_id=? AND table_id=? AND uid=?", team, table, uid);
    }

    static long write(Connection db, long team, int table, String uid, long login, long edited, int mode,
                      Map<String, Object> data) throws SQLException {
        long revision = insert(db, "INSERT INTO revisions DEFAULT VALUES");
        execute(db, "INSERT INTO objects VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(team_id,table_id,uid) "
                + "DO UPDATE SET revision=excluded.revision,login_id=excluded.login_id,edit_time=excluded.edit_time,"
                + "mode=excluded.mode,data=excluded.data", team, table, uid, revision, login, edited, mode, json(data));
        return revision;
    }

    static Map<String, Object> download(Map<String, Object> row) {
        return map("table_id", row.get("table_id"), "edit_mode", row.get("mode"),
                "sync_entropy", row.get("revision"), "login_id", row.get("login_id"), "data", object(row.get("data")));
    }

    record Accepted(Map<String, Object> acknowledgment, Map<String, Object> repair) {}

    private Accepted upload(Connection db, Map<String, Object> user, Map<Long, Map<String, Object>> teams,
                            Map<String, Object> change) throws SQLException {
        int table = (int) number(change.get("table"), 0, 999);
        if (!TABLES.containsKey(table)) throw new ApiException(1, "未知的同步表");
        int mode = (int) number(change.get("mode"), 1, 3);
        long edited = number(change.get("edit_time"));
        long base = number(change.getOrDefault("entropy", 0));
        Map<String, Object> data = object(change.get("data"));
        String uid = string(data.get("uid"), 256, true);
        long team = number(data.get("team_id"), 1, Long.MAX_VALUE);
        if (!teams.containsKey(team)) throw new ApiException(3, "没有加入此空间");
        Map<String, Object> current = lookup(db, team, table, uid);
        Map<String, Object> previous = current == null ? map() : object(current.get("data"));
        long userId = id(user, "id");
        long owner = number(previous.getOrDefault("user_id", data.getOrDefault("user_id", userId)), 1, Long.MAX_VALUE);
        if (PRIVATE.contains(table) && owner != userId) throw new ApiException(3, "无权修改他人的私人数据");
        if (current == null && owner != userId && (!MEMBER_TARGETS.contains(table)
                || one(db, "SELECT 1 FROM members WHERE team_id=? AND user_id=?", team, owner) == null)) {
            throw new ApiException(3, "无效的数据所有者");
        }
        Map<String, Object> payload = new LinkedHashMap<>(previous);
        if (mode == 3) {
            payload = map("uid", uid, "team_id", team, "user_id", owner);
        } else {
            for (String field : TABLES.get(table)) {
                if (!data.containsKey(field)) continue;
                Object value = data.get(field);
                if (!(value instanceof String || value instanceof Number)
                        || (value instanceof Double d && !Double.isFinite(d))) {
                    throw new ApiException(1, "同步字段格式错误");
                }
                payload.put(field, string(value.toString(), 65536, false));
            }
            payload.putAll(map("uid", uid, "team_id", team, "user_id", owner));
        }
        payload.remove("entropy");
        long login = id(user, "login_id");
        String fingerprint = digest(json(List.of(table, mode, edited, base, data)));
        Map<String, Object> receipt = one(db, "SELECT revision FROM receipts WHERE login_id=? AND fingerprint=?", login, fingerprint);
        long revision;
        Map<String, Object> repair = null;
        if (receipt != null) {
            revision = id(receipt, "revision");
            if (current != null && id(current, "revision") != revision) repair = download(current);
        } else if (current != null && ((base < id(current, "revision") && edited < id(current, "edit_time"))
                || (id(current, "mode") == 3 && mode != 3))) {
            revision = id(current, "revision");
            repair = download(current);
        } else {
            revision = write(db, team, table, uid, login, edited, mode, payload);
        }
        if (receipt == null) execute(db, "INSERT INTO receipts VALUES(?,?,?)", login, fingerprint, revision);
        return new Accepted(map("uid", uid, "status", 0, "entropy", revision), repair);
    }

    Map<String, Object> synchronize(Connection db, Map<String, Object> user, Map<String, Object> params) throws SQLException {
        Map<String, Object> entropy = object(params.get("entropy"));
        List<Map<String, Object>> changes = objects(params.getOrDefault("_data", List.of()));
        if (changes.size() > 10000) throw new ApiException(1, "单次同步记录过多");
        Map<Long, Map<String, Object>> teams = new LinkedHashMap<>();
        for (Map<String, Object> team : Accounts.teams(db, id(user, "id"))) teams.put(id(team, "id"), team);
        List<Map<String, Object>> acknowledgments = new ArrayList<>();
        Map<Long, Map<String, Object>> repairs = new TreeMap<>();
        for (Map<String, Object> change : changes) {
            Accepted accepted = upload(db, user, teams, change);
            acknowledgments.add(accepted.acknowledgment());
            if (accepted.repair() != null) repairs.put(id(accepted.repair(), "sync_entropy"), accepted.repair());
        }
        Map<Long, Map<String, Object>> down = new TreeMap<>();
        List<Map<String, Object>> offsets = new ArrayList<>();
        for (var entry : teams.entrySet()) {
            long team = entry.getKey();
            String key = "t" + team;
            long cursor = number(entropy.getOrDefault(key, 0));
            long bound = id(one(db, "SELECT COALESCE(MAX(revision),0) AS n FROM objects WHERE team_id=?", team), "n");
            if (cursor > bound) cursor = 0;
            int limit = entropy.containsKey(key) ? 160 : 158;
            if (!entropy.containsKey(key)) {
                down.put(-2 * team, map("table_id", 12, "edit_mode", 1, "sync_entropy", 0, "login_id", 0,
                        "data", Accounts.teamData(db, entry.getValue())));
                down.put(1 - 2 * team, map("table_id", 11, "edit_mode", 1, "sync_entropy", 0, "login_id", 0,
                        "data", map("uid", "11-" + team + "-" + user.get("id"), "team_id", team,
                                "user_id", user.get("id"), "role", entry.getValue().get("role"), "status", 0)));
            }
            List<Map<String, Object>> rows = query(db, "SELECT * FROM objects WHERE team_id=? AND revision>? "
                    + "ORDER BY revision LIMIT ?", team, cursor, limit + 1);
            for (Map<String, Object> row : rows.subList(0, Math.min(limit, rows.size()))) {
                cursor = id(row, "revision");
                if (PRIVATE.contains((int) id(row, "table_id"))
                        && id(object(row.get("data")), "user_id") != id(user, "id")) continue;
                down.put(cursor, download(row));
            }
            offsets.add(map("team_id", team, "entropy", cursor, "remain", rows.size() > limit ? 1 : 0));
        }
        down.putAll(repairs);
        return map("team", teams.keySet().stream().map(id -> map("team_id", id, "status", 0)).toList(),
                "data", acknowledgments, "down", new ArrayList<>(down.values()), "down_extra", offsets,
                "sync_tag", text(user, "login_id"));
    }
}
