package com.gxtodo.server;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class Collaboration {
    private static final String MESSAGES = "SELECT m.*,u.nickname,u.avatar FROM messages m JOIN users u ON u.id=m.sender ";

    static Map<String, Object> memberTeam(Connection db, long team, long user, boolean owner) throws SQLException {
        Map<String, Object> row = one(db, "SELECT t.*,m.role FROM teams t JOIN members m ON t.id=m.team_id "
                + "WHERE t.id=? AND m.user_id=? AND t.status=0", team, user);
        if (row == null || (owner && (id(row, "user_id") != user || id(row, "type") == 1))) {
            throw new ApiException(3, "无权操作此空间");
        }
        return row;
    }

    Map<String, Object> teams(Connection db, Map<String, Object> user, Map<String, Object> params) throws SQLException {
        String keyword = string(params.getOrDefault("keyword", ""), 100, false).strip();
        List<Map<String, Object>> rows = keyword.isEmpty() ? Accounts.teams(db, id(user, "id"))
                : query(db, "SELECT * FROM teams WHERE status=0 AND type!=1 AND (strpos(name,?)>0 OR CAST(id AS TEXT)=?) "
                + "ORDER BY id DESC LIMIT 100", keyword, keyword);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (id(row, "type") != 1) result.add(Accounts.teamData(db, row));
        }
        return map("data", result);
    }

    Map<String, Object> members(Connection db, Map<String, Object> user, Map<String, Object> params) throws SQLException {
        List<Map<String, Object>> rows;
        if (params.containsKey("team_id")) {
            long team = number(params.get("team_id"), 1, Long.MAX_VALUE);
            memberTeam(db, team, id(user, "id"), false);
            rows = query(db, "SELECT u.id,u.nickname,u.avatar,m.role FROM members m JOIN users u ON u.id=m.user_id "
                    + "WHERE m.team_id=? AND u.inactive=0", team);
        } else {
            rows = query(db, "SELECT DISTINCT u.id,u.nickname,u.avatar,0 AS role FROM members mine "
                    + "JOIN members m ON m.team_id=mine.team_id JOIN users u ON u.id=m.user_id "
                    + "WHERE mine.user_id=? AND u.inactive=0", user.get("id"));
        }
        Map<String, Object> members = map();
        for (Map<String, Object> row : rows) {
            members.put(text(row, "id"), map("user_id", row.get("id"), "nickname", row.get("nickname"),
                    "avatar", row.get("avatar"), "role", row.get("role"), "status", 0, "remark", ""));
        }
        return map("data", members);
    }

    Map<String, Object> prepare(Connection db, Map<String, Object> user, Map<String, Object> params) throws SQLException {
        Map<String, Object> result = map("data", map("has_try", 1, "year_price", 0, "month_price", 0));
        if (params.containsKey("team_id")) {
            result.put("team", Accounts.teamData(db, memberTeam(db, number(params.get("team_id")), id(user, "id"), false)));
        }
        return result;
    }

    Map<String, Object> saveTeam(Connection db, Map<String, Object> user, Map<String, Object> params, String method)
            throws SQLException {
        long team;
        if (params.containsKey("team_id")) {
            team = number(params.get("team_id"), 1, Long.MAX_VALUE);
            Map<String, Object> existing = memberTeam(db, team, id(user, "id"), !method.equals("GET"));
            if (method.equals("GET")) {
                Map<String, Object> result = Accounts.teamData(db, existing);
                return map("team", result, "data", result);
            }
            if (params.containsKey("_update_icon") || params.containsKey("_hasIcon")) {
                throw new ApiException(1, "未配置团队图标上传");
            }
            String name = string(params.getOrDefault("name", existing.get("name")), 100, true).strip();
            long size = number(params.getOrDefault("total_size", existing.get("total_size")), 1, 999);
            if (size < id(one(db, "SELECT COUNT(*) AS n FROM members WHERE team_id=?", team), "n")) {
                throw new ApiException(1, "人数上限不能小于当前成员数");
            }
            execute(db, "UPDATE teams SET name=?,total_size=?,updated_at=? WHERE id=?", name, size, timestamp(), team);
        } else {
            if (!method.equals("POST")) throw new ApiException(1, "缺少团队编号");
            team = Accounts.createTeam(db, id(user, "id"), string(params.get("name"), 100, true).strip(),
                    11, number(params.get("total_size"), 1, 999));
        }
        Map<String, Object> result = Accounts.teamData(db, memberTeam(db, team, id(user, "id"), false));
        SyncService.write(db, team, 12, Long.toString(team), id(user, "login_id"), System.currentTimeMillis(), 2, result);
        return map("team", result, "data", result);
    }

    private static Map<String, Object> messageData(Map<String, Object> row) {
        return map("id", row.get("id"), "message_id", row.get("id"), "from_user_id", row.get("sender"),
                "from_user_nickname", row.get("nickname"), "from_user_avatar", row.get("avatar"),
                "to_user_id", row.get("recipient"), "type", 1, "event", row.get("event"),
                "content", object(row.get("content")), "created_at", row.get("created_at"),
                "is_read", row.get("seen"), "is_delete", row.get("removed"));
    }

    Map<String, Object> messageDetail(Connection db, Map<String, Object> user, Map<String, Object> params) throws SQLException {
        Map<String, Object> row = one(db, MESSAGES + "WHERE m.id=? AND m.recipient=? AND m.removed=0",
                number(params.get("message_id"), 1, Long.MAX_VALUE), user.get("id"));
        if (row == null) throw new ApiException(3, "消息不存在");
        return map("data", messageData(row));
    }

    Map<String, Object> messages(Connection db, Map<String, Object> user, Map<String, Object> params, String method)
            throws SQLException {
        long userId = id(user, "id");
        if (method.equals("GET")) {
            long size = number(params.getOrDefault("page_size", 100), 1, 100);
            long page = number(params.getOrDefault("page", 1), 1, 100000);
            List<Map<String, Object>> rows = query(db, MESSAGES
                    + "WHERE m.recipient=? AND m.removed=0 ORDER BY m.id DESC LIMIT ? OFFSET ?", userId, size, (page - 1) * size);
            return map("data", rows.stream().map(Collaboration::messageData).toList());
        }
        if (method.equals("PUT")) {
            execute(db, "UPDATE messages SET seen=1,removed=? WHERE id=? AND recipient=? AND removed=0",
                    number(params.getOrDefault("is_delete", 0), 0, 1), number(params.get("message_id"), 1, Long.MAX_VALUE), userId);
            return map();
        }
        int event = (int) number(params.get("event"), 1, 999);
        Map<String, Object> payload = object(params.get("_data"));
        long teamId = number(payload.get("id"), 1, Long.MAX_VALUE);
        long recipient;
        switch (event) {
            case 11 -> {
                Map<String, Object> team = one(db, "SELECT * FROM teams WHERE id=? AND type!=1 AND status=0", teamId);
                if (team == null) throw new ApiException(3, "团队不存在");
                recipient = id(team, "user_id");
                if (one(db, "SELECT 1 FROM members WHERE team_id=? AND user_id=?", teamId, userId) != null) {
                    throw new ApiException(3, "已加入该团队");
                }
                payload = map("id", teamId, "name", team.get("name"), "operate", 0);
                Map<String, Object> last = one(db, "SELECT content FROM messages WHERE event=11 AND sender=? "
                        + "AND recipient=? ORDER BY id DESC LIMIT 1", userId, recipient);
                if (last != null && json(object(last.get("content"))).equals(json(payload))) return map();
            }
            case 12 -> {
                Map<String, Object> team = memberTeam(db, teamId, userId, true);
                long requestId = number(params.get("message_id"), 1, Long.MAX_VALUE);
                Map<String, Object> request = one(db, "SELECT * FROM messages WHERE id=? AND recipient=? AND event=11", requestId, userId);
                if (request == null) throw new ApiException(3, "申请不存在");
                Map<String, Object> original = object(request.get("content"));
                if (id(original, "id") != teamId || id(original, "operate") != 0) throw new ApiException(3, "申请已处理");
                recipient = id(request, "sender");
                long operation = number(payload.get("operate"), 1, 2);
                if (operation == 1) {
                    if (id(Accounts.teamData(db, team), "real_size") >= id(team, "total_size")) {
                        throw new ApiException(3, "团队人数已满");
                    }
                    execute(db, "INSERT INTO members VALUES(?,?,?) ON CONFLICT DO NOTHING", teamId, recipient, MEMBER);
                }
                original.put("operate", operation);
                execute(db, "UPDATE messages SET content=?,seen=1 WHERE id=?", json(original), requestId);
                payload = original;
            }
            case 15, 16, 17 -> {
                Map<String, Object> team = memberTeam(db, teamId, userId, event != 15);
                if (id(team, "type") == 1 || (event == 15 && id(team, "user_id") == userId)) {
                    throw new ApiException(3, "无法退出此空间");
                }
                recipient = event == 15 ? id(team, "user_id") : number(params.getOrDefault("recipient", userId), 1, Long.MAX_VALUE);
                if (event == 17) {
                    execute(db, "UPDATE teams SET status=-1 WHERE id=?", teamId);
                    return map();
                }
                long removed = event == 15 ? userId : recipient;
                if (removed == id(team, "user_id")) throw new ApiException(3, "无法移除创建者");
                execute(db, "DELETE FROM members WHERE team_id=? AND user_id=?", teamId, removed);
                payload = map("id", teamId, "name", team.get("name"), "operate", 0);
            }
            default -> throw new ApiException(1, "未知消息类型");
        }
        execute(db, "INSERT INTO messages(sender,recipient,event,content,created_at) VALUES(?,?,?,?,?)",
                userId, recipient, event, json(payload), timestamp());
        return map();
    }

    Map<String, Object> comments(Connection db, Map<String, Object> user, Map<String, Object> params, boolean create)
            throws SQLException {
        long team = number(params.get("team_id"), 1, Long.MAX_VALUE);
        memberTeam(db, team, id(user, "id"), false);
        String task = string(params.get("task_id"), 256, true);
        Map<String, Object> existing = SyncService.lookup(db, team, 89, task);
        if (existing == null || id(existing, "mode") == 3) throw new ApiException(3, "任务不存在，请先同步");
        if (create) {
            execute(db, "INSERT INTO comments(author,team_id,task_id,content,created_at) VALUES(?,?,?,?,?)",
                    user.get("id"), team, task, string(params.get("comment"), 4000, true).strip(), timestamp());
        }
        long size = number(params.getOrDefault("page_size", 20), 1, 100);
        long cursor = number(params.getOrDefault("comment_id", 0));
        List<Object> args = new ArrayList<>(List.of(team, task));
        String condition = "";
        if (cursor != 0) {
            condition = create || params.getOrDefault("type", "up").equals("up") ? " AND c.id>?" : " AND c.id<?";
            args.add(cursor);
        }
        args.add(size);
        List<Map<String, Object>> rows = query(db, "SELECT c.*,u.nickname,u.avatar FROM comments c JOIN users u ON c.author=u.id "
                + "WHERE c.team_id=? AND c.task_id=?" + condition + " ORDER BY c.id DESC LIMIT ?", args.toArray());
        List<Map<String, Object>> result = rows.stream().map(row -> map("id", row.get("id"), "comment", row.get("content"),
                "content", row.get("content"), "user_id", row.get("author"), "nickname", row.get("nickname"),
                "avatar", row.get("avatar"), "created_at", row.get("created_at"))).toList();
        return map("data", result, "total_num", id(one(db, "SELECT COUNT(*) AS n FROM comments WHERE team_id=? AND task_id=?", team, task), "n"));
    }
}
