"""Email accounts and device-scoped sessions."""

import hashlib
import hmac
import re
import secrets
import time

from .protocol import ApiError, FAR_FUTURE, OWNER, digest, nested, number, string, timestamp


SESSION_AGE = 30 * 86400
PASSWORD_ROUNDS = 600_000
EMAIL = re.compile(r"[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+")


def email_address(value):
    address = string(value, 254, required=True).strip().lower()
    if not EMAIL.fullmatch(address):
        raise ApiError(1, "邮箱地址无效")
    return address


def password_hash(value, salt=None):
    value = string(value, 128, required=True)
    if len(value) < 6:
        raise ApiError(1, "密码至少需要六个字符")
    salt = salt or secrets.token_hex(16)
    key = hashlib.pbkdf2_hmac("sha256", value.encode(), bytes.fromhex(salt), PASSWORD_ROUNDS)
    return f"{salt}:{key.hex()}"


def verify_password(value, stored):
    calculated = password_hash(value, stored.split(":", 1)[0])
    return hmac.compare_digest(calculated, stored)


def profile(user):
    return {key: user[key] for key in ("email", "nickname", "intro", "avatar")}


def user_teams(db, user_id):
    return db.execute(
        "SELECT t.*, m.role FROM teams t JOIN members m ON t.id=m.team_id "
        "WHERE m.user_id=? AND t.status=0 ORDER BY t.type, t.id", (user_id,)
    ).fetchall()


def team_data(db, team):
    result = dict(team)
    result["service_expire_time"] = FAR_FUTURE
    result["real_size"] = db.execute(
        "SELECT COUNT(*) FROM members WHERE team_id=?", (team["id"],)
    ).fetchone()[0]
    return result


def create_team(db, owner, name, kind, size):
    now = timestamp()
    team_id = db.execute(
        "INSERT INTO teams(user_id,name,type,total_size,created_at,updated_at) VALUES(?,?,?,?,?,?)",
        (owner, name, kind, size, now, now),
    ).lastrowid
    db.execute("INSERT INTO members VALUES(?,?,?)", (team_id, owner, OWNER))
    return team_id


def sign_in(storage, params, register=False):
    address = email_address(params.get("email"))
    password = string(params.get("password"), 128, required=True)
    device = string(params.get("dev_id"), 256, required=True)
    token = secrets.token_urlsafe(32)
    if register:
        hashed_password = password_hash(password)
    else:
        with storage.connection() as db:
            found = db.execute("SELECT * FROM users WHERE email=?", (address,)).fetchone()
        if found is None:
            password_hash(password, "00" * 16)
            raise ApiError(10022, "邮箱或密码错误")
        if not verify_password(password, found["password_hash"]):
            raise ApiError(10022, "邮箱或密码错误")
        if found["inactive"]:
            raise ApiError(10040, "该账号已注销")
    with storage.transaction(write=True) as db:
        if register:
            if db.execute("SELECT 1 FROM users WHERE email=?", (address,)).fetchone():
                raise ApiError(10039, "邮箱已注册")
            user_id = db.execute(
                "INSERT INTO users(email,password_hash,nickname) VALUES(?,?,?)",
                (address, hashed_password, address.split("@", 1)[0][:64]),
            ).lastrowid
            create_team(db, user_id, "个人空间", 1, 1)
            user = db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
        else:
            user = db.execute("SELECT * FROM users WHERE id=?", (found["id"],)).fetchone()
            if user["inactive"] or user["password_hash"] != found["password_hash"]:
                raise ApiError(10022, "邮箱或密码已更新，请重新登录")
            user_id = user["id"]
        db.execute(
            "INSERT INTO sessions(user_id,device,token_hash,expires) VALUES(?,?,?,?) "
            "ON CONFLICT(user_id,device) DO UPDATE SET token_hash=excluded.token_hash,expires=excluded.expires",
            (user_id, device, digest(token), int(time.time()) + SESSION_AGE),
        )
        login_id = db.execute(
            "SELECT id FROM sessions WHERE user_id=? AND device=?", (user_id, device)
        ).fetchone()[0]
        return {
            "user_id": user_id, "login_id": login_id, "token": token, "data": profile(user),
            "teamData": [team_data(db, team) for team in user_teams(db, user_id)],
        }


def authenticate(db, params):
    login_id = number(params.get("login_id") or 0)
    user_id = number(params.get("user_id") or 0)
    token = string(params.get("token") or "", 128)
    session = db.execute(
        "SELECT u.*, s.id AS login_id, s.token_hash, s.expires "
        "FROM sessions s JOIN users u ON s.user_id=u.id WHERE s.id=? AND u.id=?",
        (login_id, user_id),
    ).fetchone()
    if (
        not session or session["inactive"] or session["expires"] <= int(time.time())
        or not hmac.compare_digest(digest(token), session["token_hash"])
    ):
        raise ApiError(5, "请重新登录")
    return session


def update_profile(db, user, params):
    fields = nested(params, "_data", dict)
    changes = {}
    if "nickname" in fields:
        changes["nickname"] = string(fields["nickname"], 64, required=True)
    if "intro" in fields:
        changes["intro"] = string(fields["intro"], 1000)
    if "email" in fields:
        address = email_address(fields["email"])
        if db.execute("SELECT 1 FROM users WHERE email=? AND id!=?", (address, user["id"])).fetchone():
            raise ApiError(10039, "邮箱已注册")
        changes["email"] = address
    if "password" in fields:
        previous = fields.get("password0", "")
        if "email" not in fields and (not previous or not verify_password(previous, user["password_hash"])):
            raise ApiError(10022, "原密码错误")
        changes["password_hash"] = password_hash(fields["password"])
        db.execute("UPDATE sessions SET expires=0 WHERE user_id=? AND id!=?", (user["id"], user["login_id"]))
        db.execute("DELETE FROM reset_codes WHERE user_id=?", (user["id"],))
    for key, value in changes.items():
        db.execute(f"UPDATE users SET {key}=? WHERE id=?", (value, user["id"]))
    updated = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
    return {"data": profile(updated)}


def issue_reset(storage, params, send_link):
    address = email_address(params.get("email"))
    token = secrets.token_urlsafe(32)
    with storage.transaction(write=True) as db:
        user = db.execute("SELECT id FROM users WHERE email=? AND inactive=0", (address,)).fetchone()
        if user is None:
            return {}
        now = int(time.time())
        previous = db.execute("SELECT expires FROM reset_codes WHERE user_id=?", (user["id"],)).fetchone()
        if previous and previous["expires"] > now + 1140:
            return {}
        db.execute(
            "INSERT INTO reset_codes VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE "
            "SET token_hash=excluded.token_hash,expires=excluded.expires",
            (user["id"], digest(token), now + 1200),
        )
    send_link(address, token)
    return {}


def reset_password(storage, token, password):
    token_hash = digest(string(token, 128, required=True))
    updated = password_hash(password)
    with storage.transaction(write=True) as db:
        row = db.execute(
            "SELECT user_id FROM reset_codes WHERE token_hash=? AND expires>?", (token_hash, int(time.time()))
        ).fetchone()
        if row is None:
            raise ApiError(1, "重置链接无效或已过期")
        db.execute("UPDATE users SET password_hash=? WHERE id=?", (updated, row["user_id"]))
        db.execute("UPDATE sessions SET expires=0 WHERE user_id=?", (row["user_id"],))
        db.execute("DELETE FROM reset_codes WHERE user_id=?", (row["user_id"],))
