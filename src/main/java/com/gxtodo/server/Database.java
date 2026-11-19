package com.gxtodo.server;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import java.sql.Connection;
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
    private final DataSource source;

    Database(DataSource source) {
        this.source = source;
    }

    @PostConstruct
    void initialize() throws IOException, SQLException {
        try (Connection db = source.getConnection(); Statement statement = db.createStatement()) {
            db.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(734192001)");
            String schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            db.commit();
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection db) throws SQLException;
    }

    <T> T transaction(boolean write, Work<T> work) {
        try (Connection db = source.getConnection(); Statement statement = db.createStatement()) {
            db.setAutoCommit(false);
            db.setReadOnly(!write);
            try {
                statement.execute("SET LOCAL lock_timeout = '15s'");
                statement.execute("SET LOCAL statement_timeout = '30s'");
                // A cursor must never pass a revision belonging to an uncommitted writer.
                // Preserve the SQLite service's write ordering across all API instances.
                if (write) statement.execute("SELECT pg_advisory_xact_lock(734192002)");
                T result = work.run(db);
                db.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    db.rollback();
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
        return Protocol.id(one(db, sql + " RETURNING id", args), "id");
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
