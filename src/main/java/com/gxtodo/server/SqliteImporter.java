package com.gxtodo.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static com.gxtodo.server.Database.*;

/** Explicit one-time migration. Never changes the source SQLite file. */
@Component
final class SqliteImporter implements ApplicationRunner {
    private static final List<String> TABLES = List.of("users", "sessions", "reset_codes", "teams", "members",
            "revisions", "objects", "receipts", "messages", "comments");
    private final Database storage;
    private final String source;
    private final ConfigurableApplicationContext context;

    SqliteImporter(Database storage, @Value("${gxtodo.migrate-sqlite}") String source,
                   ConfigurableApplicationContext context) {
        this.storage = storage;
        this.source = source;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (source.isBlank()) return;
        Path file = Path.of(source).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("SQLite 源文件不存在");
        try (var sqlite = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + "?mode=ro")) {
            storage.transaction(true, pg -> {
                for (String table : TABLES) {
                    if (Protocol.id(one(pg, "SELECT COUNT(*) AS n FROM " + table), "n") != 0) {
                        throw new IllegalStateException("目标库必须为空，拒绝覆盖已有数据: " + table);
                    }
                }
                for (String table : TABLES) {
                    long count = 0;
                    try (var statement = sqlite.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
                        int columns = rows.getMetaData().getColumnCount();
                        String names = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(i -> {
                            try { return rows.getMetaData().getColumnName(i); }
                            catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                        }).collect(java.util.stream.Collectors.joining(","));
                        String marks = String.join(",", java.util.Collections.nCopies(columns, "?"));
                        try (var insert = pg.prepareStatement("INSERT INTO " + table + " (" + names + ") VALUES(" + marks + ")")) {
                            while (rows.next()) {
                                for (int i = 1; i <= columns; i++) insert.setObject(i, rows.getObject(i));
                                insert.addBatch();
                                if (++count % 500 == 0) insert.executeBatch();
                            }
                            insert.executeBatch();
                        }
                    }
                    long installed = Protocol.id(one(pg, "SELECT COUNT(*) AS n FROM " + table), "n");
                    if (installed != count) throw new IllegalStateException("迁移行数不一致: " + table);
                    if (!fingerprint(sqlite, table).equals(fingerprint(pg, table))) {
                        throw new IllegalStateException("迁移内容校验不一致: " + table);
                    }
                    if (List.of("users", "sessions", "teams", "revisions", "messages", "comments").contains(table)) {
                        one(pg, "SELECT setval(pg_get_serial_sequence(?, 'id'), COALESCE(MAX(id),1), MAX(id) IS NOT NULL) FROM " + table, table);
                    }
                    System.out.println("Migrated " + table + ": " + count);
                }
                return null;
            });
        }
        SpringApplication.exit(context);
    }

    private static String fingerprint(java.sql.Connection db, String table) throws java.sql.SQLException {
        String keys = switch (table) {
            case "reset_codes" -> "user_id";
            case "members" -> "team_id,user_id";
            case "objects" -> "team_id,table_id,uid";
            case "receipts" -> "login_id,fingerprint";
            default -> "id";
        };
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        try (var statement = db.prepareStatement("SELECT * FROM " + table + " ORDER BY " + keys)) {
            statement.setFetchSize(500);
            try (var rows = statement.executeQuery()) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    var row = new java.util.ArrayList<String>();
                    for (int i = 1; i <= columns; i++) row.add(java.util.Objects.toString(rows.getObject(i), ""));
                    digest.update(Protocol.json(row).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
