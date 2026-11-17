"""SQLite storage shared by login, synchronization and collaboration."""

from contextlib import contextmanager
import sqlite3
from pathlib import Path


SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    nickname TEXT NOT NULL,
    intro TEXT NOT NULL DEFAULT '',
    avatar TEXT NOT NULL DEFAULT '',
    inactive INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    device TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    expires INTEGER NOT NULL,
    UNIQUE (user_id, device)
);
CREATE TABLE IF NOT EXISTS reset_codes (
    user_id INTEGER PRIMARY KEY REFERENCES users(id),
    token_hash TEXT NOT NULL,
    expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS teams (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    name TEXT NOT NULL,
    type INTEGER NOT NULL,
    intro TEXT NOT NULL DEFAULT '',
    notice TEXT NOT NULL DEFAULT '',
    icon TEXT NOT NULL DEFAULT '',
    total_size INTEGER NOT NULL DEFAULT 1,
    status INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS personal_team ON teams(user_id) WHERE type = 1;
CREATE TABLE IF NOT EXISTS members (
    team_id INTEGER NOT NULL REFERENCES teams(id),
    user_id INTEGER NOT NULL REFERENCES users(id),
    role INTEGER NOT NULL,
    PRIMARY KEY(team_id, user_id)
);
CREATE TABLE IF NOT EXISTS revisions (
    id INTEGER PRIMARY KEY AUTOINCREMENT
);
CREATE TABLE IF NOT EXISTS objects (
    team_id INTEGER NOT NULL REFERENCES teams(id),
    table_id INTEGER NOT NULL,
    uid TEXT NOT NULL,
    revision INTEGER NOT NULL,
    login_id INTEGER NOT NULL,
    edit_time INTEGER NOT NULL,
    mode INTEGER NOT NULL,
    data TEXT NOT NULL,
    PRIMARY KEY(team_id, table_id, uid)
);
CREATE INDEX IF NOT EXISTS object_sync ON objects(team_id, revision);
CREATE TABLE IF NOT EXISTS receipts (
    login_id INTEGER NOT NULL REFERENCES sessions(id),
    fingerprint TEXT NOT NULL,
    revision INTEGER NOT NULL,
    PRIMARY KEY(login_id, fingerprint)
);
CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender INTEGER NOT NULL REFERENCES users(id),
    recipient INTEGER NOT NULL REFERENCES users(id),
    event INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    seen INTEGER NOT NULL DEFAULT 0,
    removed INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS message_inbox ON messages(recipient, removed, id);
CREATE TABLE IF NOT EXISTS comments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    author INTEGER NOT NULL REFERENCES users(id),
    team_id INTEGER NOT NULL REFERENCES teams(id),
    task_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS comment_task ON comments(team_id, task_id, id);
"""


class Storage:
    def __init__(self, path):
        self.path = Path(path).resolve()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
        finally:
            db.close()

    @contextmanager
    def transaction(self, write=False):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise
