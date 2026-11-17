package com.gxtodo.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.gxtodo.server.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired Database database;
    @Autowired Accounts accounts;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void databasePath(DynamicPropertyRegistry properties) throws IOException {
        Path directory = Files.createTempDirectory("gxtodo-java-test-");
        properties.add("gxtodo.database", () -> directory.resolve("test.sqlite3").toString());
    }

    private Map<String, Object> call(String route, Map<String, Object> session, String method, Object... values)
            throws IOException, InterruptedException {
        Map<String, Object> params = map(values);
        if (session != null) {
            for (String key : List.of("user_id", "login_id", "token")) params.putIfAbsent(key, session.get(key));
        }
        params.put("_method", method);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7" + route))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json(params))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return object(response.body());
    }

    private String origin() {
        return "http://127.0.0.1:" + port;
    }

    private Map<String, Object> create(String device) throws Exception {
        return login(UUID.randomUUID() + "@example.net", device, "Example1234", true);
    }

    private Map<String, Object> login(String email, String device, String password, boolean create) throws Exception {
        Map<String, Object> result = call(create ? "/user/base" : "/user/login", null, "POST",
                "email", email, "dev_id", device, "password", password);
        assertEquals("0", result.get("status"), result.toString());
        return result;
    }

    private long team(Map<String, Object> session) {
        return id(objects(session.get("teamData")).get(0), "id");
    }

    private Map<String, Object> change(int table, String uid, long team, long user, int mode, long revision,
                                      long edited, Object... values) {
        Map<String, Object> payload = map("uid", uid, "team_id", team, "user_id", user);
        payload.putAll(map(values));
        return map("table", table, "mode", mode, "edit_time", edited, "entropy", revision, "data", payload);
    }

    @Test
    void syncRetryDeletionAndAccountIsolation() throws Exception {
        Map<String, Object> first = create("android");
        Map<String, Object> second = login(text(object(first.get("data")), "email"), "ios", "Example1234", false);
        Map<String, Object> other = create("other");
        long team = team(first);
        Map<String, Object> change = change(89, "89-task", team, id(first, "user_id"), 1, 0,
                System.currentTimeMillis(), "name", "接入两端", "privacy", 6720);
        List<Callable<Map<String, Object>>> writes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            writes.add(() -> call("/sync/do", first, "POST", "entropy", map("t" + team, 0), "_data", List.of(change)));
        }
        long revision;
        var executor = Executors.newFixedThreadPool(4);
        try {
            var results = executor.invokeAll(writes);
            revision = id(objects(results.get(0).get().get("data")).get(0), "entropy");
            for (var future : results) {
                assertEquals("0", future.get().get("status"));
                assertEquals(revision, id(objects(future.get().get("data")).get(0), "entropy"));
            }
        } finally {
            executor.shutdownNow();
        }
        Map<String, Object> pulled = call("/sync/do", second, "POST", "entropy", map("t" + team, 0));
        assertEquals("接入两端", object(objects(pulled.get("down")).get(0).get("data")).get("name"));
        assertEquals("3", call("/sync/do", other, "POST", "entropy", map(), "_data", List.of(change)).get("status"));
        Map<String, Object> impostor = new HashMap<>(first);
        impostor.put("token", second.get("token"));
        assertEquals("5", call("/sync/do", impostor, "POST", "entropy", map()).get("status"));
        Map<String, Object> removal = change(89, "89-task", team, id(first, "user_id"), 3, revision,
                System.currentTimeMillis() + 10);
        assertEquals("0", call("/sync/do", second, "POST", "entropy", map("t" + team, revision), "_data", List.of(removal)).get("status"));
        call("/sync/do", first, "POST", "entropy", map("t" + team, 0), "_data", List.of(change));
        var deleted = objects(call("/sync/do", first, "POST", "entropy", map("t" + team, 0)).get("down"));
        assertEquals(3, id(deleted.get(0), "edit_mode"));
        var unauthorized = objects(call("/sync/do", other, "POST", "entropy", map("t" + team, 0)).get("down"));
        assertTrue(unauthorized.stream().noneMatch(row -> "89-task".equals(object(row.get("data")).get("uid"))));
    }

    @Test
    void teamApprovalsCommentsPrivateNotesAndRemoval() throws Exception {
        var owner = create("owner");
        var member = create("member");
        long team = id(object(call("/team/base", owner, "POST", "name", "发布团队", "total_size", 4).get("team")), "id");
        assertEquals("0", call("/message/base", member, "POST", "event", 11, "_data", map("id", team)).get("status"));
        var invitation = objects(call("/message/base", owner, "GET").get("data")).get(0);
        assertEquals("0", call("/message/base", owner, "POST", "event", 12, "message_id", invitation.get("id"),
                "_data", map("id", team, "operate", 1)).get("status"));
        assertTrue(object(call("/team/user", owner, "GET", "team_id", team).get("data")).containsKey(text(member, "user_id")));
        long now = System.currentTimeMillis();
        var task = change(89, "team-task", team, id(owner, "user_id"), 1, 0, now, "name", "团队任务");
        var note = change(49, "private-note", team, id(owner, "user_id"), 1, 0, now, "name", "私人内容");
        assertEquals("0", call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(task, note)).get("status"));
        var down = objects(call("/sync/do", member, "POST", "entropy", map("t" + team, 0)).get("down"));
        assertTrue(down.stream().noneMatch(row -> "private-note".equals(object(row.get("data")).get("uid"))));
        var altered = change(49, "private-note", team, id(member, "user_id"), 2, 0, now + 1, "name", "越权覆盖");
        assertEquals("3", call("/sync/do", member, "POST", "entropy", map(), "_data", List.of(altered)).get("status"));
        assertEquals("0", call("/comment/create", member, "POST", "team_id", team, "task_id", "team-task", "comment", "已确认").get("status"));
        assertEquals("已确认", objects(call("/comment/list", owner, "POST", "team_id", team, "task_id", "team-task").get("data")).get(0).get("comment"));
        assertEquals("0", call("/message/base", owner, "POST", "event", 16, "recipient", member.get("user_id"), "_data", map("id", team)).get("status"));
        assertEquals("3", call("/comment/list", member, "POST", "team_id", team, "task_id", "team-task").get("status"));
    }

    @Test
    void pagesAndPartialBatchRollback() throws Exception {
        var session = create("paging");
        long team = team(session);
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 170; i++) batch.add(change(49, "note-" + i, team, id(session, "user_id"), 1, 0,
                System.currentTimeMillis(), "name", "便签" + i));
        var first = call("/sync/do", session, "POST", "entropy", map("t" + team, 0), "_data", batch);
        assertEquals(160, objects(first.get("down")).size());
        var marker = objects(first.get("down_extra")).get(0);
        assertEquals(1, id(marker, "remain"));
        var last = call("/sync/do", session, "POST", "entropy", map("t" + team, marker.get("entropy")));
        assertEquals(10, objects(last.get("down")).size());
        assertEquals(0, id(objects(last.get("down_extra")).get(0), "remain"));
        var good = change(49, "rollback-note", team, id(session, "user_id"), 1, 0, System.currentTimeMillis(), "name", "回滚");
        var bad = change(999, "unknown-table", team, id(session, "user_id"), 1, 0, System.currentTimeMillis());
        assertEquals("1", call("/sync/do", session, "POST", "entropy", map(), "_data", List.of(good, bad)).get("status"));
        assertNull(database.transaction(false, db -> SyncService.lookup(db, team, 49, "rollback-note")));
    }

    @Test
    void resetPasswordInvalidatesSessionsAndTokenIsSingleUse() throws Exception {
        var user = create("old");
        String email = text(object(user.get("data")), "email");
        Accounts.Reset reset = accounts.issueReset(map("email", email));
        assertNotNull(reset);
        HttpResponse<String> resetPage = client.send(HttpRequest.newBuilder(URI.create(origin() + "/reset"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("token=" + reset.token() + "&password=Changed4321")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resetPage.statusCode());
        assertTrue(resetPage.body().contains("密码已重置"));
        assertEquals("5", call("/user/base", user, "GET").get("status"));
        assertEquals("10022", call("/user/login", null, "POST", "email", email, "dev_id", "old", "password", "Example1234").get("status"));
        assertEquals(user.get("user_id"), login(email, "old", "Changed4321", false).get("user_id"));
        assertThrows(ApiException.class, () -> accounts.resetPassword(reset.token(), "Changed5678"));
    }

    @Test
    void oldPythonSchemaPasswordsAndReceiptRemainCompatible() throws Exception {
        // Fixture hash and fingerprint were generated by the former Python implementation.
        String salt = "000102030405060708090a0b0c0d0e0f";
        String password = "迁移密码123";
        String expected = "000102030405060708090a0b0c0d0e0f:3e961c6becb4977244d832825e6ceb313096a77f511c5a64b19f6563b778afa4";
        assertEquals(expected, Accounts.passwordHash(password, salt));
        String address = UUID.randomUUID() + "@example.net";
        long user = database.transaction(true, db -> {
            long id = Database.insert(db, "INSERT INTO users(email,password_hash,nickname) VALUES(?,?,?)", address, expected, "旧版账号");
            Accounts.createTeam(db, id, "个人空间", 1, 1);
            return id;
        });
        var session = login(address, "migrated", password, false);
        assertEquals(user, id(session, "user_id"));
        var fresh = new Database(databaseFile());
        assertEquals("旧版账号", fresh.transaction(false, db -> Database.one(db, "SELECT nickname FROM users WHERE id=?", user)).get("nickname"));
        String fingerprint = digest(json(List.of(89, 1, 123456789L, 0, map("name", "迁移任务", "uid", "89-test", "team_id", 1, "user_id", 1))));
        assertEquals("6df75d1ef26ec3be53e14b987ec168b45b405378caaeb7379cce6a6e2770531e", fingerprint);
    }

    private String databaseFile() {
        return database.transaction(false, db -> text(Database.one(db, "PRAGMA database_list"), "file"));
    }

    @Test
    void healthLegacyFormsClockAndMalformedInput() throws Exception {
        var health = client.send(HttpRequest.newBuilder(URI.create(origin() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("ok", object(health.body()).get("status"));
        var clock = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/time.php")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(Math.abs(System.currentTimeMillis() - Long.parseLong(clock.body())) < 5000);
        var user = create("legacy");
        String encoded = "p=A&user_id=" + user.get("user_id") + "&login_id=" + user.get("login_id")
                + "&token=" + URLEncoder.encode(text(user, "token"), StandardCharsets.UTF_8) + "&_method=GET";
        var form = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/user/base"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encoded)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("0", object(form.body()).get("status"));
        var get = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/user/base?" + encoded)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("0", object(get.body()).get("status"));
        var malformed = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/sync/do"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("[]")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("1", object(malformed.body()).get("status"));
        assertFalse(malformed.body().contains("Exception"));
    }
}
