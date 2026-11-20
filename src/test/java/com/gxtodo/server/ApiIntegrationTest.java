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
    @Autowired javax.sql.DataSource source;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void databasePath(DynamicPropertyRegistry properties) throws IOException {
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("GXTODO_TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:15439/gxtodo_test"));
        properties.add("spring.datasource.username", () -> "gxtodo_test");
        properties.add("spring.datasource.password", () -> "gxtodo_test_local_only");
        properties.add("gxtodo.minio.endpoint", () -> "http://127.0.0.1:19019");
        properties.add("gxtodo.minio.access-key", () -> "gxtodo_test");
        properties.add("gxtodo.minio.secret-key", () -> "gxtodo_test_local_only");
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

    private Map<String, Object> uploadImage(Map<String, Object> session, String note, byte[] image) throws Exception {
        return uploadMedia(session, note, image, "image", "original.png");
    }

    private Map<String, Object> uploadMedia(Map<String, Object> session, String note, byte[] image, String kind, String name, Object... extra) throws Exception {
        String boundary = "gxtodo-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        var params = map("user_id", session.get("user_id"), "login_id", session.get("login_id"),
                "token", session.get("token"), "team_id", team(session), "note_id", note, "kind", kind);
        params.putAll(map(extra));
        for (var entry : params.entrySet()) body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + entry.getKey() + "\"\r\n\r\n" + entry.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/media/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return object(response.body());
    }

    @Test
    void retryUploadUsesStableClientIdAndConversationMessagesStayPrivate() throws Exception {
        var owner = create("chat-owner");
        var other = create("chat-other");
        String note = "49-chat-" + UUID.randomUUID();
        String clientId = "media-" + UUID.randomUUID();
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfusAAAAASUVORK5CYII=");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
        var executor = Executors.newFixedThreadPool(3);
        Map<String, Object> media;
        try {
            var jobs = new ArrayList<Callable<Map<String, Object>>>();
            for (int i = 0; i < 3; i++) jobs.add(() -> uploadMedia(owner, note, png, "image", "original.png",
                    "client_id", clientId, "sha256", hash));
            var results = executor.invokeAll(jobs);
            media = object(results.get(0).get().get("data"));
            for (var result : results) {
                assertEquals("0", result.get().get("status"));
                assertEquals(media.get("id"), object(result.get().get("data")).get("id"));
            }
        } finally { executor.shutdownNow(); }
        long rows = database.transaction(false, db -> id(Database.one(db,
                "SELECT COUNT(*) AS n FROM media_clients WHERE owner=? AND client_id=?", owner.get("user_id"), clientId), "n"));
        assertEquals(1, rows);
        long now = System.currentTimeMillis();
        var header = change(49, note, team(owner), id(owner, "user_id"), 1, 0, now,
                "title", "便签标题", "name", "便签标题", "conversation_version", 1);
        var entry = change(50, "50-" + UUID.randomUUID(), team(owner), id(owner, "user_id"), 1, 0, now + 1,
                "note_id", note, "kind", "image", "media_id", media.get("id"), "client_media_id", clientId,
                "entry_at", now, "sha256", hash, "size", png.length, "content_type", "image/png");
        var created = call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(header, entry));
        assertEquals("0", created.get("status"), created.toString());
        assertEquals(2, objects(created.get("data")).size());
        var unauthorized = call("/sync/do", other, "POST", "entropy", map());
        assertTrue(objects(unauthorized.get("down")).stream().noneMatch(row -> note.equals(object(row.get("data")).get("note_id"))));
        assertEquals("3", uploadMedia(owner, note, png, "image", "original.png", "client_id", clientId,
                "sha256", "0".repeat(64)).get("status"));
        var foreign = change(50, "50-forged-" + UUID.randomUUID(), team(other), id(other, "user_id"), 1, 0, now,
                "note_id", "49-other", "kind", "image", "media_id", media.get("id"));
        assertEquals("3", call("/sync/do", other, "POST", "entropy", map(), "_data", List.of(foreign)).get("status"));
    }

    @Test
    void ledgerFileExactMoneyAndParentIsolation() throws Exception {
        var owner=create("ledger-files");
        String bill="51-"+UUID.randomUUID();
        byte[] bytes="<html>原始收据文件</html>".getBytes(StandardCharsets.UTF_8);
        var uploaded=uploadMedia(owner,bill,bytes,"file","收据.html","parent_table",51);
        assertEquals("0",uploaded.get("status"),uploaded.toString());
        var file=object(uploaded.get("data"));
        var header=change(51,bill,team(owner),id(owner,"user_id"),1,0,System.currentTimeMillis(),
                "name","购买资料","date","2026-11-20","type","expense","amount","9007199254740993.010000000000000001",
                "channel","GooglePay","party","工行卡","location","测试位置","latitude","30.1","longitude","120.2");
        var attachment=change(52,"52-"+UUID.randomUUID(),team(owner),id(owner,"user_id"),1,0,System.currentTimeMillis(),
                "note_id",bill,"kind","file","media_id",file.get("id"),"media_name","收据.html");
        var result=call("/sync/do",owner,"POST","entropy",map(),"_data",List.of(header,attachment));
        assertEquals("0",result.get("status"),result.toString());
        var stored=database.transaction(false,db->SyncService.lookup(db,team(owner),51,bill));
        assertEquals("9007199254740993.010000000000000001",object(stored.get("data")).get("amount"));
        var credentials=map("user_id",owner.get("user_id"),"login_id",owner.get("login_id"),"token",owner.get("token"),"id",file.get("id"));
        var read=client.send(HttpRequest.newBuilder(URI.create(origin()+"/api/v7/media/read"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json(credentials))).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(bytes,read.body());
        assertEquals("application/octet-stream",read.headers().firstValue("Content-Type").orElse(""));
        assertTrue(read.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"));
        var forged=change(49,bill,team(owner),id(owner,"user_id"),1,0,System.currentTimeMillis(),"name","跨类型引用","attachments",json(List.of(file)));
        assertEquals("3",call("/sync/do",owner,"POST","entropy",map(),"_data",List.of(forged)).get("status"));
        var removal=change(51,bill,team(owner),id(owner,"user_id"),3,0,System.currentTimeMillis()+1000);
        assertEquals("0",call("/sync/do",owner,"POST","entropy",map(),"_data",List.of(removal)).get("status"));
        assertEquals("3",call("/media/read",owner,"POST","id",file.get("id")).get("status"));
    }

    @Test
    void ledgerAndOptionsRemainPrivateInsideSharedTeam() throws Exception {
        var owner=create("ledger-owner");var other=create("ledger-member");
        long shared=database.transaction(true,db->{
            long team=Accounts.createTeam(db,id(owner,"user_id"),"共享空间",11,8);
            Database.execute(db,"INSERT INTO members VALUES(?,?,?)",team,other.get("user_id"),448);return team;
        });
        var header=change(51,"51-"+UUID.randomUUID(),shared,id(owner,"user_id"),1,0,System.currentTimeMillis(),
                "name","私人账单","date","2026-11-20","type","income","amount","0.10","channel","微信","party","微信余额");
        var option=change(53,"53-"+UUID.randomUUID(),shared,id(owner,"user_id"),1,0,System.currentTimeMillis(),"name","私人支付途径","kind","channel");
        assertEquals("0",call("/sync/do",owner,"POST","entropy",map(),"_data",List.of(header,option)).get("status"));
        var download=call("/sync/do",other,"POST","entropy",map());
        assertEquals("0",download.get("status"));
        assertTrue(objects(download.get("down")).stream().noneMatch(row->List.of(51L,52L,53L).contains(id(row,"table_id"))));
    }

    @Test
    void audioBytesRemainUnchangedAndOldClientKeepsAttachments() throws Exception {
        var owner = create("voice-android");
        String note = "49-voice-" + UUID.randomUUID();
        var wave = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(40)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(4).putInt(0);
        var uploaded = uploadMedia(owner, note, wave.array(), "audio", "voice.wav");
        assertEquals("0", uploaded.get("status"), uploaded.toString());
        var audio = object(uploaded.get("data"));
        assertEquals("audio/wav", audio.get("content_type"));
        var create = change(49, note, team(owner), id(owner, "user_id"), 1, 0, System.currentTimeMillis(),
                "name", "语音", "attachments", json(List.of(audio)));
        var first = call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(create));
        assertEquals("0", first.get("status"), first.toString());
        long revision = id(objects(first.get("data")).get(0), "entropy");
        var legacy = change(49, note, team(owner), id(owner, "user_id"), 2, revision, System.currentTimeMillis() + 10,
                "name", "旧客户端仅修改文字");
        assertEquals("0", call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(legacy)).get("status"));
        var row = database.transaction(false, db -> SyncService.lookup(db, team(owner), 49, note));
        assertEquals(audio.get("id"), objects(object(row.get("data")).get("attachments")).get(0).get("id"));
        var credentials = map("user_id", owner.get("user_id"), "login_id", owner.get("login_id"),
                "token", owner.get("token"), "id", audio.get("id"));
        var read = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/media/read"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(credentials))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(wave.array(), read.body());
    }

    @Test
    void originalMediaRoundTripAuthorizationAndAttachmentOwnership() throws Exception {
        var owner = create("media-ios");
        var other = create("media-other");
        String note = "49-" + UUID.randomUUID();
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfusAAAAASUVORK5CYII=");
        var uploaded = uploadImage(owner, note, png);
        assertEquals("0", uploaded.get("status"), uploaded.toString());
        var attachment = object(uploaded.get("data"));
        assertEquals((long) png.length, id(attachment, "size"));
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
        assertEquals(checksum, attachment.get("sha256"));
        var noteChange = change(49, note, team(owner), id(owner, "user_id"), 1, 0, System.currentTimeMillis(),
                "name", "图片和语音便签", "attachments", json(List.of(attachment)));
        var synced = call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(noteChange));
        assertEquals("0", synced.get("status"), synced.toString());
        var credentials = map("user_id", owner.get("user_id"), "login_id", owner.get("login_id"),
                "token", owner.get("token"), "id", attachment.get("id"));
        var read = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/v7/media/read"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(credentials))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, read.statusCode());
        assertArrayEquals(png, read.body());
        assertEquals("image/png", read.headers().firstValue("content-type").orElse(""));
        assertEquals("3", call("/media/read", other, "POST", "id", attachment.get("id")).get("status"));
        var forged = change(49, "49-forged", team(owner), id(owner, "user_id"), 1, 0, System.currentTimeMillis(),
                "name", "跨便签盗用", "attachments", json(List.of(attachment)));
        assertEquals("3", call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(forged)).get("status"));
        assertEquals("1", uploadImage(owner, note, "not-an-image".getBytes(StandardCharsets.UTF_8)).get("status"));
        var tombstone = change(49, note, team(owner), id(owner, "user_id"), 3, 0, System.currentTimeMillis() + 1000);
        assertEquals("0", call("/sync/do", owner, "POST", "entropy", map(), "_data", List.of(tombstone)).get("status"));
        assertEquals("3", call("/media/read", owner, "POST", "id", attachment.get("id")).get("status"));
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
        var fresh = new Database(source);
        assertEquals("旧版账号", fresh.transaction(false, db -> Database.one(db, "SELECT nickname FROM users WHERE id=?", user)).get("nickname"));
        String fingerprint = digest(json(List.of(89, 1, 123456789L, 0, map("name", "迁移任务", "uid", "89-test", "team_id", 1, "user_id", 1))));
        assertEquals("6df75d1ef26ec3be53e14b987ec168b45b405378caaeb7379cce6a6e2770531e", fingerprint);
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
