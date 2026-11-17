package com.gxtodo.server;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class Accounts {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+");
    private final Database storage;

    Accounts(Database storage) {
        this.storage = storage;
    }

    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String passwordHash(Object value, String salt) {
        String password = string(value, 128, true);
        if (password.codePointCount(0, password.length()) < 6) throw new ApiException(1, "密码至少需要六个字符");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), 600_000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return salt + ":" + HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        } finally {
            spec.clearPassword();
        }
    }

    static String passwordHash(Object value) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return passwordHash(value, HexFormat.of().formatHex(salt));
    }

    static boolean equal(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean verify(Object password, String stored) {
        return equal(passwordHash(password, stored.split(":", 2)[0]), stored);
    }

    private static String email(Object value) {
        String address = string(value, 254, true).strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(address).matches()) throw new ApiException(1, "邮箱地址无效");
        return address;
    }

    static Map<String, Object> profile(Map<String, Object> user) {
        return map("email", user.get("email"), "nickname", user.get("nickname"),
                "intro", user.get("intro"), "avatar", user.get("avatar"));
    }

    static List<Map<String, Object>> teams(Connection db, long user) throws SQLException {
        return query(db, "SELECT t.*,m.role FROM teams t JOIN members m ON t.id=m.team_id "
                + "WHERE m.user_id=? AND t.status=0 ORDER BY t.type,t.id", user);
    }

    static Map<String, Object> teamData(Connection db, Map<String, Object> team) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>(team);
        value.put("service_expire_time", FAR_FUTURE);
        value.put("real_size", id(one(db, "SELECT COUNT(*) AS n FROM members WHERE team_id=?", team.get("id")), "n"));
        return value;
    }

    static long createTeam(Connection db, long owner, String name, int kind, long size) throws SQLException {
        String now = timestamp();
        long team = insert(db, "INSERT INTO teams(user_id,name,type,total_size,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                owner, name, kind, size, now, now);
        execute(db, "INSERT INTO members VALUES(?,?,?)", team, owner, OWNER);
        return team;
    }

    Map<String, Object> login(Map<String, Object> params, boolean register) {
        String address = email(params.get("email"));
        String password = string(params.get("password"), 128, true);
        String device = string(params.get("dev_id"), 256, true);
        String token = token();
        String hashed = register ? passwordHash(password) : null;
        Map<String, Object> found = register ? null : storage.transaction(false,
                db -> one(db, "SELECT * FROM users WHERE email=?", address));
        if (!register) {
            String candidate = found == null ? passwordHash(password, "00".repeat(16))
                    : passwordHash(password, text(found, "password_hash").split(":", 2)[0]);
            if (found == null || !equal(candidate, text(found, "password_hash"))) {
                throw new ApiException(10022, "邮箱或密码错误");
            }
            if (id(found, "inactive") != 0) throw new ApiException(10040, "该账号已注销");
        }
        return storage.transaction(true, db -> {
            long userId;
            Map<String, Object> user;
            if (register) {
                if (one(db, "SELECT 1 FROM users WHERE email=?", address) != null) {
                    throw new ApiException(10039, "邮箱已注册");
                }
                String nickname = address.substring(0, address.indexOf('@'));
                userId = insert(db, "INSERT INTO users(email,password_hash,nickname) VALUES(?,?,?)",
                        address, hashed, nickname.substring(0, Math.min(64, nickname.length())));
                createTeam(db, userId, "个人空间", 1, 1);
                user = one(db, "SELECT * FROM users WHERE id=?", userId);
            } else {
                userId = id(found, "id");
                user = one(db, "SELECT * FROM users WHERE id=?", userId);
                if (id(user, "inactive") != 0 || !equal(text(user, "password_hash"), text(found, "password_hash"))) {
                    throw new ApiException(10022, "邮箱或密码已更新，请重新登录");
                }
            }
            execute(db, "INSERT INTO sessions(user_id,device,token_hash,expires) VALUES(?,?,?,?) "
                    + "ON CONFLICT(user_id,device) DO UPDATE SET token_hash=excluded.token_hash,expires=excluded.expires",
                    userId, device, digest(token), Instant.now().getEpochSecond() + 30L * 86400);
            long loginId = id(one(db, "SELECT id FROM sessions WHERE user_id=? AND device=?", userId, device), "id");
            List<Map<String, Object>> spaces = teams(db, userId);
            for (int i = 0; i < spaces.size(); i++) spaces.set(i, teamData(db, spaces.get(i)));
            return map("user_id", userId, "login_id", loginId, "token", token, "data", profile(user), "teamData", spaces);
        });
    }

    static Map<String, Object> authenticate(Connection db, Map<String, Object> params) throws SQLException {
        long login = number(params.getOrDefault("login_id", 0));
        long user = number(params.getOrDefault("user_id", 0));
        String token = string(params.getOrDefault("token", ""), 128, false);
        Map<String, Object> session = one(db, "SELECT u.*,s.id AS login_id,s.token_hash,s.expires "
                + "FROM sessions s JOIN users u ON s.user_id=u.id WHERE s.id=? AND u.id=?", login, user);
        if (session == null || id(session, "inactive") != 0 || id(session, "expires") <= Instant.now().getEpochSecond()
                || !equal(digest(token), text(session, "token_hash"))) {
            throw new ApiException(5, "请重新登录");
        }
        return session;
    }

    static Map<String, Object> updateProfile(Connection db, Map<String, Object> user, Map<String, Object> params)
            throws SQLException {
        Map<String, Object> fields = object(params.get("_data"));
        Map<String, Object> changes = new LinkedHashMap<>();
        if (fields.containsKey("nickname")) changes.put("nickname", string(fields.get("nickname"), 64, true));
        if (fields.containsKey("intro")) changes.put("intro", string(fields.get("intro"), 1000, false));
        if (fields.containsKey("email")) {
            String address = email(fields.get("email"));
            if (one(db, "SELECT 1 FROM users WHERE email=? AND id!=?", address, user.get("id")) != null) {
                throw new ApiException(10039, "邮箱已注册");
            }
            changes.put("email", address);
        }
        if (fields.containsKey("password")) {
            Object old = fields.getOrDefault("password0", "");
            if (!fields.containsKey("email") && (old.equals("") || !verify(old, text(user, "password_hash")))) {
                throw new ApiException(10022, "原密码错误");
            }
            changes.put("password_hash", passwordHash(fields.get("password")));
            execute(db, "UPDATE sessions SET expires=0 WHERE user_id=? AND id!=?", user.get("id"), user.get("login_id"));
            execute(db, "DELETE FROM reset_codes WHERE user_id=?", user.get("id"));
        }
        for (var entry : changes.entrySet()) {
            execute(db, "UPDATE users SET " + entry.getKey() + "=? WHERE id=?", entry.getValue(), user.get("id"));
        }
        return map("data", profile(one(db, "SELECT * FROM users WHERE id=?", user.get("id"))));
    }

    record Reset(String email, String token) {}

    Reset issueReset(Map<String, Object> params) {
        String address = email(params.get("email"));
        String token = token();
        return storage.transaction(true, db -> {
            Map<String, Object> user = one(db, "SELECT id FROM users WHERE email=? AND inactive=0", address);
            if (user == null) return null;
            long now = Instant.now().getEpochSecond();
            Map<String, Object> previous = one(db, "SELECT expires FROM reset_codes WHERE user_id=?", user.get("id"));
            if (previous != null && id(previous, "expires") > now + 1140) return null;
            execute(db, "INSERT INTO reset_codes VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE "
                    + "SET token_hash=excluded.token_hash,expires=excluded.expires", user.get("id"), digest(token), now + 1200);
            return new Reset(address, token);
        });
    }

    void resetPassword(Object token, Object password) {
        String hash = digest(string(token, 128, true));
        String updated = passwordHash(password);
        storage.transaction(true, db -> {
            Map<String, Object> row = one(db, "SELECT r.user_id FROM reset_codes r JOIN users u ON u.id=r.user_id "
                    + "WHERE r.token_hash=? AND r.expires>? AND u.inactive=0", hash, Instant.now().getEpochSecond());
            if (row == null) throw new ApiException(1, "重置链接无效或已过期");
            execute(db, "UPDATE users SET password_hash=? WHERE id=?", updated, row.get("user_id"));
            execute(db, "UPDATE sessions SET expires=0 WHERE user_id=?", row.get("user_id"));
            execute(db, "DELETE FROM reset_codes WHERE user_id=?", row.get("user_id"));
            return null;
        });
    }
}
