"""Incremental v7 synchronization with stable receipts and deletion tombstones."""

import json

from .accounts import team_data, user_teams
from .protocol import ApiError, TABLE_FIELDS, compact, digest, nested, number, string


PAGE_SIZE = 160
PRIVATE_TABLES = {49, 77}
MEMBER_TARGET_TABLES = {76, 83, 88, 98}


def lookup(db, team_id, table_id, uid):
    return db.execute(
        "SELECT * FROM objects WHERE team_id=? AND table_id=? AND uid=?",
        (team_id, table_id, uid),
    ).fetchone()


def write_object(db, team_id, table_id, uid, login_id, edit_time, mode, data):
    revision = db.execute("INSERT INTO revisions DEFAULT VALUES").lastrowid
    db.execute(
        "INSERT INTO objects VALUES(?,?,?,?,?,?,?,?) "
        "ON CONFLICT(team_id,table_id,uid) DO UPDATE SET revision=excluded.revision, "
        "login_id=excluded.login_id, edit_time=excluded.edit_time, mode=excluded.mode, data=excluded.data",
        (team_id, table_id, uid, revision, login_id, edit_time, mode, compact(data)),
    )
    return revision


def as_download(row):
    return {
        "table_id": row["table_id"], "edit_mode": row["mode"], "sync_entropy": row["revision"],
        "login_id": row["login_id"], "data": json.loads(row["data"]),
    }


def upload(db, user, teams, change):
    if not isinstance(change, dict):
        raise ApiError(1, "同步记录格式错误")
    table_id = number(change.get("table"))
    if table_id not in TABLE_FIELDS:
        raise ApiError(1, "未知的同步表")
    mode = number(change.get("mode"), 1, 3)
    edit_time = number(change.get("edit_time"))
    revision_at_edit = number(change.get("entropy", 0))
    data = nested(change, "data", dict)
    uid = string(data.get("uid"), 256, required=True)
    team_id = number(data.get("team_id"), 1)
    if team_id not in teams:
        # The old client can keep pending writes after a user is removed from a team.
        # Avoid acknowledging changes the server has no authority to save.
        raise ApiError(3, "没有加入此空间")
    current = lookup(db, team_id, table_id, uid)
    previous_data = json.loads(current["data"]) if current else {}
    user_id = user["id"]
    owner = number(previous_data.get("user_id") or data.get("user_id") or user_id, 1)
    if table_id in PRIVATE_TABLES and user_id != owner:
        raise ApiError(3, "无权修改他人的私人数据")
    if not current and owner != user_id:
        target = db.execute("SELECT 1 FROM members WHERE team_id=? AND user_id=?", (team_id, owner)).fetchone()
        if table_id not in MEMBER_TARGET_TABLES or not target:
            raise ApiError(3, "无效的数据所有者")
    if mode == 3:
        payload = {"uid": uid, "team_id": team_id, "user_id": owner}
    else:
        payload = dict(previous_data)
        for key in TABLE_FIELDS[table_id] & data.keys():
            value = data[key]
            if not isinstance(value, (str, int, float)) or isinstance(value, bool):
                raise ApiError(1, "同步字段格式错误")
            payload[key] = string(str(value), 65536)
        payload.update(uid=uid, team_id=team_id, user_id=owner)
    payload.pop("entropy", None)
    login_id = user["login_id"]
    fingerprint = digest(compact([table_id, mode, edit_time, revision_at_edit, data]))
    receipt = db.execute(
        "SELECT revision FROM receipts WHERE login_id=? AND fingerprint=?", (login_id, fingerprint)
    ).fetchone()
    repair = None
    if receipt:
        revision = receipt["revision"]
        if current and current["revision"] != revision:
            repair = as_download(current)
    elif current and (
        (revision_at_edit < current["revision"] and edit_time < current["edit_time"])
        or (current["mode"] == 3 and mode != 3)
    ):
        revision = current["revision"]
        repair = as_download(current)
    else:
        revision = write_object(db, team_id, table_id, uid, login_id, edit_time, mode, payload)
    if not receipt:
        db.execute("INSERT INTO receipts VALUES(?,?,?)", (login_id, fingerprint, revision))
    return {"uid": uid, "status": 0, "entropy": revision}, repair


def synchronize(db, user, params):
    entropy = nested(params, "entropy", dict)
    changes = nested(params, "_data", list, [])
    if len(changes) > 10000:
        raise ApiError(1, "单次同步记录过多")
    teams = {row["id"]: row for row in user_teams(db, user["id"])}
    acknowledgments = []
    repairs = {}
    for change in changes:
        ack, repair = upload(db, user, teams, change)
        acknowledgments.append(ack)
        if repair:
            repairs[repair["sync_entropy"]] = repair
    down = {}
    offsets = []
    for team_id in teams:
        key = f"t{team_id}"
        cursor = number(entropy.get(key, 0))
        bound = db.execute("SELECT COALESCE(MAX(revision),0) FROM objects WHERE team_id=?", (team_id,)).fetchone()[0]
        if cursor > bound:
            cursor = 0
        limit = PAGE_SIZE if key in entropy else PAGE_SIZE - 2
        if key not in entropy:
            # A membership change can introduce a team without a local cursor.
            team = teams[team_id]
            down[-2 * team_id] = {
                "table_id": 12, "edit_mode": 1, "sync_entropy": 0, "login_id": 0,
                "data": team_data(db, team),
            }
            down[1 - 2 * team_id] = {
                "table_id": 11, "edit_mode": 1, "sync_entropy": 0, "login_id": 0,
                "data": {"uid": f"11-{team_id}-{user['id']}", "team_id": team_id,
                         "user_id": user["id"], "role": team["role"], "status": 0},
            }
        rows = db.execute(
            "SELECT * FROM objects WHERE team_id=? AND revision>? ORDER BY revision LIMIT ?",
            (team_id, cursor, limit + 1),
        ).fetchall()
        for row in rows[:limit]:
            cursor = row["revision"]
            if row["table_id"] in PRIVATE_TABLES and int(json.loads(row["data"])["user_id"]) != user["id"]:
                continue
            down[cursor] = as_download(row)
        offsets.append({"team_id": team_id, "entropy": cursor, "remain": int(len(rows) > limit)})
    down.update(repairs)
    return {
        "team": [{"team_id": team_id, "status": 0} for team_id in teams],
        "data": acknowledgments, "down": [value for _, value in sorted(down.items())],
        "down_extra": offsets, "sync_tag": str(user["login_id"]),
    }
