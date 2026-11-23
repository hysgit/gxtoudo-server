package com.gxtodo.server;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@Service
final class DesktopLogin {
    private static final int VALID_SECONDS = 120;
    private final byte[] signingKey = Base64.getUrlDecoder().decode(Accounts.token());
    private final Database storage;

    DesktopLogin(Database storage) {
        this.storage = storage;
    }

    private record Grant(String hash, String challenge, String device, String label, long expires) {
        String code() {
            return String.format(Locale.ROOT, "%06d", Long.parseLong(challenge.substring(0, 8), 16) % 1000000);
        }
    }

    Map<String, Object> start(Map<String, Object> params) {
        String challenge = string(params.get("challenge"), 64, true);
        String device = string(params.get("dev_id"), 100, true);
        String label = string(params.get("label"), 32, true).strip();
        if (!challenge.matches("[0-9a-f]{64}") || !device.matches("desktop-[0-9a-f]{32}")
                || !label.matches("[\\p{L}\\p{N} ._-]{1,32}")) {
            throw new ApiException(1, "桌面登录参数无效");
        }
        long expires = now() + VALID_SECONDS;
        String body = encode(json(map("challenge", challenge, "device", device, "label", label,
                "expires", expires, "nonce", Accounts.token())).getBytes(StandardCharsets.UTF_8));
        var grant = new Grant("", challenge, device, label, expires);
        return map("request", body + "." + signature(body), "code", grant.code(), "valid_seconds", VALID_SECONDS);
    }

    Map<String, Object> preview(Map<String, Object> params) {
        Grant grant = readGrant(params);
        return storage.transaction(false, db -> {
            Accounts.authenticate(db, params);
            if (one(db, "SELECT 1 FROM desktop_login_approvals WHERE request_hash=?", grant.hash()) != null) {
                throw expired();
            }
            return map("label", grant.label(), "code", grant.code());
        });
    }

    Map<String, Object> approve(Map<String, Object> params) {
        Grant grant = readGrant(params);
        return storage.transaction(true, db -> {
            var user = Accounts.authenticate(db, params);
            if (grant.expires() <= now() || execute(db, """
                    INSERT INTO desktop_login_approvals(request_hash,login_id,expires) VALUES(?,?,?)
                    ON CONFLICT(request_hash) DO NOTHING
                    """, grant.hash(), user.get("login_id"), grant.expires()) != 1) {
                throw expired();
            }
            return map();
        });
    }

    Map<String, Object> poll(Map<String, Object> params) {
        Grant grant = readGrant(params);
        String verifier = string(params.get("verifier"), 100, true);
        if (!verifier.matches("[A-Za-z0-9_-]{43}") || !Accounts.equal(digest(verifier), grant.challenge())) {
            throw new ApiException(410, "请在原桌面窗口重新生成二维码");
        }
        // Most polls happen before the phone confirms, so they never take the write lock.
        if (storage.transaction(false, db -> one(db,
                "SELECT 1 FROM desktop_login_approvals WHERE request_hash=?", grant.hash())) == null) {
            return map("state", "waiting");
        }
        return storage.transaction(true, db -> {
            long now = now();
            var user = one(db, """
                    SELECT u.* FROM desktop_login_approvals a
                    JOIN sessions s ON s.id=a.login_id AND s.expires>?
                    JOIN users u ON u.id=s.user_id AND u.inactive=0
                    WHERE a.request_hash=? AND NOT a.consumed AND a.expires>?
                    """, now, grant.hash(), now);
            if (user == null) throw expired();
            execute(db, "UPDATE desktop_login_approvals SET consumed=true WHERE request_hash=?", grant.hash());
            var result = Accounts.issueSession(db, user, grant.device());
            result.put("state", "complete");
            return result;
        });
    }

    private Grant readGrant(Map<String, Object> params) {
        String request = string(params.get("request"), 1024, true);
        String[] parts = request.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]+")
                || !Accounts.equal(signature(parts[0]), parts[1])) throw expired();
        var body = object(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        long expires = id(body, "expires");
        if (expires <= now()) throw expired();
        return new Grant(digest(request), text(body, "challenge"), text(body, "device"), text(body, "label"), expires);
    }

    @Scheduled(fixedDelay = 120000)
    void cleanup() {
        long now = now();
        if (storage.transaction(false, db -> one(db, "SELECT 1 FROM desktop_login_approvals WHERE expires<=? LIMIT 1", now)) != null) {
            storage.transaction(true, db -> execute(db, "DELETE FROM desktop_login_approvals WHERE expires<=?", now));
        }
    }

    private String signature(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return encode(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String encode(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private static ApiException expired() {
        return new ApiException(410, "二维码已失效，请在电脑上刷新二维码");
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
