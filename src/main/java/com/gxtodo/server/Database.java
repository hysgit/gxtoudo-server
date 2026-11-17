package com.gxtodo.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
final class Database {
    private final String url;

    Database(@Value("${gxtodo.database}") String file) throws IOException, SQLException {
        Path path = Path.of(file).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        url = "jdbc:sqlite:" + path;
        try (Connection db = connection(); Statement statement = db.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            String schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    private Connection connection() throws SQLException {
        Connection db = DriverManager.getConnection(url);
        try (Statement statement = db.createStatement()) {
            statement.execute("PRAGMA busy_timeout=15000");
            statement.execute("PRAGMA foreign_keys=ON");
            return db;
        } catch (SQLException error) {
            db.close();
            throw error;
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection db) throws SQLException;
    }

    <T> T transaction(boolean write, Work<T> work) {
        try (Connection db = connection(); Statement statement = db.createStatement()) {
            statement.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            try {
                T result = work.run(db);
                statement.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new StorageException(error);
        }
    }

    static int execute(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, args)) {
            return statement.executeUpdate();
        }
    }

    static long insert(Connection db, String sql, Object... args) throws SQLException {
        execute(db, sql, args);
        return Protocol.id(one(db, "SELECT last_insert_rowid() AS id"), "id");
    }

    static Map<String, Object> one(Connection db, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = query(db, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static List<Map<String, Object>> query(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, args); ResultSet rows = statement.executeQuery()) {
            List<Map<String, Object>> result = new ArrayList<>();
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= columns; index++) {
                    row.put(rows.getMetaData().getColumnLabel(index), rows.getObject(index));
                }
                result.add(row);
            }
            return result;
        }
    }

    private static PreparedStatement prepare(Connection db, String sql, Object... args) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        try {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }

    static final class StorageException extends RuntimeException {
        StorageException(SQLException cause) {
            super(cause);
        }
    }
}
