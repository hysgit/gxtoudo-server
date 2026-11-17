import json
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen

from .__main__ import Handler
from .accounts import issue_reset, reset_password
from .storage import Storage


class SilentHandler(Handler):
    def log_message(self, *args):
        pass


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), SilentHandler)
        self.server.storage = Storage(Path(self.directory.name) / "test.sqlite3")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.origin = f"http://127.0.0.1:{self.server.server_port}/api/v7"

    def call(self, route, session=None, method="POST", **fields):
        if session is not None:
            for key in ("user_id", "login_id", "token"):
                fields.setdefault(key, session[key])
        request = Request(self.origin + route, method="POST",
                          data=json.dumps({"_method": method, **fields}, ensure_ascii=False).encode(),
                          headers={"Content-Type": "application/json; charset=utf-8"})
        with urlopen(request, timeout=10) as response:
            return json.load(response)

    def login(self, email, device, create=False, password="Example1234"):
        value = self.call("/user/base" if create else "/user/login", email=email, password=password, dev_id=device)
        self.assertEqual("0", value["status"], value)
        return value

    def operation(self, table, uid, team, user, mode=1, revision=0, edited=None, **values):
        return {"table": table, "mode": mode, "edit_time": edited or int(time.time() * 1000),
                "entropy": revision, "data": {"uid": uid, "team_id": team, "user_id": user, **values}}

    def test_parallel_retry_account_isolation_and_tombstone(self):
        one = self.login("owner@example.net", "android", create=True)
        two = self.login("owner@example.net", "ios")
        stranger = self.login("other@example.net", "other", create=True)
        team = one["teamData"][0]["id"]
        task = self.operation(89, "89-record", team, one["user_id"], name="接入两端")
        with ThreadPoolExecutor(max_workers=4) as pool:
            writes = list(pool.map(lambda _: self.call("/sync/do", one, entropy={f"t{team}": 0}, _data=[task]), range(4)))
        self.assertEqual(["0"] * 4, [row["status"] for row in writes])
        revision = writes[0]["data"][0]["entropy"]
        self.assertEqual({revision}, {row["data"][0]["entropy"] for row in writes})
        pulled = self.call("/sync/do", two, entropy={f"t{team}": 0})
        self.assertEqual(["接入两端"], [row["data"]["name"] for row in pulled["down"]])
        self.assertFalse(any(row["data"].get("uid") == "89-record"
                             for row in self.call("/sync/do", stranger, entropy={f"t{team}": 0})["down"]))
        self.assertEqual("3", self.call("/sync/do", stranger, entropy={}, _data=[task])["status"])
        self.assertEqual("5", self.call("/sync/do", one, token=two["token"], entropy={})["status"])
        deleted = self.operation(89, "89-record", team, one["user_id"], mode=3, revision=revision)
        self.assertEqual("0", self.call("/sync/do", two, entropy={f"t{team}": revision}, _data=[deleted])["status"])
        pull = self.call("/sync/do", one, entropy={f"t{team}": revision})
        self.assertEqual([3], [item["edit_mode"] for item in pull["down"]])
        self.call("/sync/do", one, entropy={f"t{team}": 0}, _data=[task])
        self.assertEqual(3, self.call("/sync/do", two, entropy={f"t{team}": 0})["down"][0]["edit_mode"])

    def test_team_access_comments_and_private_note(self):
        owner = self.login("owner@example.net", "one", create=True)
        member = self.login("member@example.net", "two", create=True)
        team = self.call("/team/base", owner, name="排期团队", total_size=4)["team"]["id"]
        self.assertEqual("0", self.call("/message/base", member, event=11, _data={"id": team})["status"])
        invitation = self.call("/message/base", owner, method="GET")["data"][0]
        self.assertEqual("0", self.call("/message/base", owner, event=12, message_id=invitation["id"],
                                        _data={"id": team, "operate": 1})["status"])
        self.assertIn(str(member["user_id"]), self.call("/team/user", owner, method="GET", team_id=team)["data"])
        task = self.operation(89, "89-team", team, owner["user_id"], name="共享任务")
        note = self.operation(49, "49-private", team, owner["user_id"], name="仅本人")
        self.assertEqual("0", self.call("/sync/do", owner, entropy={}, _data=[task, note])["status"])
        pulled = self.call("/sync/do", member, entropy={f"t{team}": 0})
        self.assertNotIn("49-private", {row["data"].get("uid") for row in pulled["down"]})
        self.assertEqual("0", self.call("/comment/create", member, team_id=team, task_id="89-team", comment="已确认")["status"])
        comments = self.call("/comment/list", owner, team_id=team, task_id="89-team")
        self.assertEqual(["已确认"], [row["comment"] for row in comments["data"]])
        self.assertEqual("0", self.call("/message/base", owner, event=16, recipient=member["user_id"],
                                        _data={"id": team})["status"])
        self.assertEqual("3", self.call("/comment/list", member, team_id=team, task_id="89-team")["status"])

    def test_password_reset_invalidates_previous_session(self):
        address = "owner@example.net"
        original = self.login(address, "original", create=True)
        mails = []
        issue_reset(self.server.storage, {"email": address}, lambda receiver, token: mails.append((receiver, token)))
        self.assertEqual(address, mails[0][0])
        reset_password(self.server.storage, mails[0][1], "Example4321")
        self.assertEqual("5", self.call("/user/base", original, method="GET")["status"])
        self.assertEqual("10022", self.call("/user/login", email=address, dev_id="original", password="Example1234")["status"])
        renewed = self.login(address, "original", password="Example4321")
        self.assertEqual(original["user_id"], renewed["user_id"])


if __name__ == "__main__":
    unittest.main()
