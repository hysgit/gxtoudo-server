"""Team membership, inbox messages and task comments."""

import json
import time

from .accounts import create_team, team_data, user_teams
from .protocol import ApiError, MEMBER, compact, nested, number, string, timestamp
from .sync import lookup, write_object


def member_team(db, team_id, user_id, owner=False):
    team = db.execute(
        "SELECT t.*,m.role FROM teams t JOIN members m ON t.id=m.team_id "
        "WHERE t.id=? AND m.user_id=? AND t.status=0", (team_id, user_id),
    ).fetchone()
    if not team or (owner and (team["user_id"] != user_id or team["type"] == 1)):
        raise ApiError(3, "无权操作此空间")
    return team


def list_teams(db, user, params):
    keyword = string(params.get("keyword", ""), 100).strip()
    if not keyword:
        rows = user_teams(db, user["id"])
    else:
        rows = db.execute(
            "SELECT * FROM teams WHERE status=0 AND type!=1 AND (instr(name,?)>0 OR CAST(id AS TEXT)=?) "
            "ORDER BY id DESC LIMIT 100", (keyword, keyword),
        )
    return {"data": [team_data(db, team) for team in rows if team["type"] != 1]}


def team_members(db, user, params):
    if params.get("team_id"):
        team = member_team(db, number(params["team_id"], 1), user["id"])
        rows = db.execute(
            "SELECT u.id,u.nickname,u.avatar,m.role FROM members m JOIN users u ON u.id=m.user_id "
            "WHERE m.team_id=? AND u.inactive=0", (team["id"],),
        ).fetchall()
    else:
        rows = db.execute(
            "SELECT DISTINCT u.id,u.nickname,u.avatar,0 AS role FROM members mine "
            "JOIN members m ON m.team_id=mine.team_id JOIN users u ON u.id=m.user_id "
            "WHERE mine.user_id=? AND u.inactive=0", (user["id"],),
        ).fetchall()
    return {"data": {
        str(row["id"]): {"user_id": row["id"], "nickname": row["nickname"], "avatar": row["avatar"],
                         "role": row["role"], "status": 0, "remark": ""}
        for row in rows
    }}


def prepare_team(db, user, params):
    result = {"data": {"has_try": 1, "year_price": 0, "month_price": 0}}
    if params.get("team_id"):
        result["team"] = team_data(db, member_team(db, number(params["team_id"], 1), user["id"]))
    return result


def save_team(db, user, params, method):
    if params.get("team_id"):
        team_id = number(params["team_id"], 1)
        team = member_team(db, team_id, user["id"], owner=(method != "GET"))
        if method == "GET":
            return {"team": team_data(db, team), "data": team_data(db, team)}
        if any(key in params for key in ("_update_icon", "_hasIcon")):
            raise ApiError(1, "未配置团队图标上传")
        name = string(params.get("name", team["name"]), 100, required=True).strip()
        size = number(params.get("total_size", team["total_size"]), 1, 999)
        count = db.execute("SELECT COUNT(*) FROM members WHERE team_id=?", (team_id,)).fetchone()[0]
        if size < count:
            raise ApiError(1, "人数上限不能小于当前成员数")
        db.execute("UPDATE teams SET name=?,total_size=?,updated_at=? WHERE id=?", (name, size, timestamp(), team_id))
    else:
        if method != "POST":
            raise ApiError(1, "缺少团队编号")
        name = string(params.get("name"), 100, required=True).strip()
        size = number(params.get("total_size"), 1, 999)
        team_id = create_team(db, user["id"], name, 11, size)
    team = member_team(db, team_id, user["id"])
    result = team_data(db, team)
    write_object(db, team_id, 12, str(team_id), user["login_id"], int(time.time() * 1000), 2, result)
    return {"team": result, "data": result}


MESSAGE_SELECT = """
SELECT m.*,u.nickname,u.avatar FROM messages m JOIN users u ON u.id=m.sender
"""


def message_data(row):
    return {
        "id": row["id"], "message_id": row["id"], "from_user_id": row["sender"],
        "from_user_nickname": row["nickname"], "from_user_avatar": row["avatar"],
        "to_user_id": row["recipient"], "type": 1, "event": row["event"],
        "content": json.loads(row["content"]), "created_at": row["created_at"],
        "is_read": row["seen"], "is_delete": row["removed"],
    }


def message_detail(db, user, params):
    message_id = number(params.get("message_id"), 1)
    row = db.execute(
        MESSAGE_SELECT + "WHERE m.id=? AND m.recipient=? AND m.removed=0", (message_id, user["id"])
    ).fetchone()
    if not row:
        raise ApiError(3, "消息不存在")
    return {"data": message_data(row)}


def manage_message(db, user, params, method):
    user_id = user["id"]
    if method == "GET":
        size = number(params.get("page_size", 100), 1, 100)
        page = number(params.get("page", 1), 1, 100000)
        rows = db.execute(
            MESSAGE_SELECT + "WHERE m.recipient=? AND m.removed=0 ORDER BY m.id DESC LIMIT ? OFFSET ?",
            (user_id, size, (page - 1) * size),
        )
        return {"data": [message_data(row) for row in rows]}
    if method == "PUT":
        message_id = number(params.get("message_id"), 1)
        deleted = number(params.get("is_delete", 0), 0, 1)
        db.execute(
            "UPDATE messages SET seen=1,removed=? WHERE id=? AND recipient=? AND removed=0",
            (deleted, message_id, user_id),
        )
        return {}

    event = number(params.get("event"), 1)
    payload = nested(params, "_data", dict)
    team_id = number(payload.get("id"), 1)
    if event == 11:
        team = db.execute("SELECT * FROM teams WHERE id=? AND type!=1 AND status=0", (team_id,)).fetchone()
        if not team:
            raise ApiError(3, "团队不存在")
        recipient = team["user_id"]
        if db.execute("SELECT 1 FROM members WHERE team_id=? AND user_id=?", (team_id, user_id)).fetchone():
            raise ApiError(3, "已加入该团队")
        payload = {"id": team_id, "name": team["name"], "operate": 0}
        latest = db.execute(
            "SELECT content FROM messages WHERE event=11 AND sender=? AND recipient=? ORDER BY id DESC LIMIT 1",
            (user_id, recipient),
        ).fetchone()
        if latest and json.loads(latest["content"]) == payload:
            return {}
    elif event == 12:
        team = member_team(db, team_id, user_id, owner=True)
        request_id = number(params.get("message_id"), 1)
        request = db.execute(
            "SELECT * FROM messages WHERE id=? AND recipient=? AND event=11", (request_id, user_id)
        ).fetchone()
        if not request:
            raise ApiError(3, "申请不存在")
        original = json.loads(request["content"])
        if original["id"] != team_id or original["operate"] != 0:
            raise ApiError(3, "申请已处理")
        recipient = request["sender"]
        operation = number(payload.get("operate"), 1, 2)
        if operation == 1:
            if team_data(db, team)["real_size"] >= team["total_size"]:
                raise ApiError(3, "团队人数已满")
            db.execute("INSERT OR IGNORE INTO members VALUES(?,?,?)", (team_id, recipient, MEMBER))
        original["operate"] = operation
        db.execute("UPDATE messages SET content=?,seen=1 WHERE id=?", (compact(original), request_id))
        payload = original
    elif event in {15, 16, 17}:
        team = member_team(db, team_id, user_id, owner=event in {16, 17})
        if team["type"] == 1 or (event == 15 and team["user_id"] == user_id):
            raise ApiError(3, "无法退出此空间")
        recipient = team["user_id"] if event == 15 else number(params.get("recipient", user_id), 1)
        if event == 17:
            db.execute("UPDATE teams SET status=-1 WHERE id=?", (team_id,))
            return {}
        removed = user_id if event == 15 else recipient
        if removed == team["user_id"]:
            raise ApiError(3, "无法移除创建者")
        db.execute("DELETE FROM members WHERE team_id=? AND user_id=?", (team_id, removed))
        payload = {"id": team_id, "name": team["name"], "operate": 0}
    else:
        raise ApiError(1, "未知消息类型")
    db.execute(
        "INSERT INTO messages(sender,recipient,event,content,created_at) VALUES(?,?,?,?,?)",
        (user_id, recipient, event, compact(payload), timestamp()),
    )
    return {}


def comments(db, user, params, create=False):
    team_id = number(params.get("team_id"), 1)
    member_team(db, team_id, user["id"])
    task_id = string(params.get("task_id"), 256, required=True)
    task = lookup(db, team_id, 89, task_id)
    if not task or task["mode"] == 3:
        raise ApiError(3, "任务不存在，请先同步")
    if create:
        content = string(params.get("comment"), 4000, required=True).strip()
        db.execute(
            "INSERT INTO comments(author,team_id,task_id,content,created_at) VALUES(?,?,?,?,?)",
            (user["id"], team_id, task_id, content, timestamp()),
        )
    size = number(params.get("page_size", 20), 1, 100)
    cursor = number(params.get("comment_id", 0))
    mode = params.get("type", "up")
    condition = ""
    query_params = [team_id, task_id]
    if cursor:
        condition = " AND c.id>?" if mode == "up" or create else " AND c.id<?"
        query_params.append(cursor)
    rows = db.execute(
        "SELECT c.*,u.nickname,u.avatar FROM comments c JOIN users u ON c.author=u.id "
        "WHERE c.team_id=? AND c.task_id=?" + condition + " ORDER BY c.id DESC LIMIT ?",
        (*query_params, size),
    )
    total = db.execute("SELECT COUNT(*) FROM comments WHERE team_id=? AND task_id=?", (team_id, task_id)).fetchone()[0]
    return {"data": [
        {"id": row["id"], "comment": row["content"], "content": row["content"], "user_id": row["author"],
         "nickname": row["nickname"], "avatar": row["avatar"], "created_at": row["created_at"]}
        for row in rows
    ], "total_num": total}
