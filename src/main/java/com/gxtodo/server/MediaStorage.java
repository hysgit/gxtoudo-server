package com.gxtodo.server;

import io.minio.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static com.gxtodo.server.Database.*;
import static com.gxtodo.server.Protocol.*;

@RestController
final class MediaStorage {
    static final long MAX_SIZE = 100L * 1024 * 1024;
    private final Database storage;
    private final MinioClient client;
    private final String bucket;
    private volatile boolean ready;

    MediaStorage(Database storage,
                 @Value("${gxtodo.minio.endpoint}") String endpoint,
                 @Value("${gxtodo.minio.access-key}") String access,
                 @Value("${gxtodo.minio.secret-key}") String secret,
                 @Value("${gxtodo.minio.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
        client = access.isBlank() || secret.isBlank() ? null : MinioClient.builder()
                .endpoint(endpoint).credentials(access, secret).build();
    }

    private synchronized void ensureBucket() throws Exception {
        if (ready) return;
        if (client == null) throw new ApiException(1, "实例尚未配置图片和语音存储");
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        ready = true;
    }

    void health() throws Exception {
        if (client == null) return;
        ensureBucket();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            throw new IllegalStateException("Media bucket unavailable");
        }
    }

    private static Map<String, Object> session(HttpServletRequest request) {
        return map("user_id", request.getParameter("user_id"), "login_id", request.getParameter("login_id"),
                "token", request.getParameter("token"));
    }

    @PostMapping(path = "/api/v7/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("team_id") long team,
                               @RequestParam("note_id") String note,
                               @RequestParam("kind") String kind,
                               HttpServletRequest request) throws Exception {
        string(note, 256, true);
        if (!Set.of("image", "audio").contains(kind)) throw new ApiException(1, "不支持的附件类型");
        if (file.isEmpty() || file.getSize() > MAX_SIZE) throw new ApiException(1, "单个附件应为 1 字节至 100 MB");
        Map<String, Object> user = storage.transaction(false, db -> {
            var account = Accounts.authenticate(db, session(request));
            Collaboration.memberTeam(db, team, id(account, "id"), false);
            var existing = SyncService.lookup(db, team, 49, note);
            if (existing != null && (id(existing, "mode") == 3
                    || id(object(existing.get("data")), "user_id") != id(account, "id"))) {
                throw new ApiException(3, "无权向此便签上传附件");
            }
            return account;
        });
        String mime;
        try (InputStream input = file.getInputStream()) {
            mime = detect(input.readNBytes(64), kind);
        }
        String supplied = Objects.toString(file.getOriginalFilename(), "attachment").replace('\\', '/');
        String name = supplied.substring(supplied.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank() || name.length() > 255) name = kind.equals("image") ? "图片" : "录音.m4a";
        String mediaId = UUID.randomUUID().toString();
        String key = "notes/" + id(user, "id") + "/" + mediaId;
        ensureBucket();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(file.getInputStream(), hash)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, file.getSize(), -1).contentType(mime).build());
        }
        String sha = HexFormat.of().formatHex(hash.digest());
        String savedName = name;
        try {
            storage.transaction(true, db -> {
                Accounts.authenticate(db, session(request));
                Collaboration.memberTeam(db, team, id(user, "id"), false);
                execute(db, "INSERT INTO media VALUES(?,?,?,?,?,?,?,?,?,?,?)", mediaId, id(user, "id"), team,
                        note, key, savedName, kind, mime, file.getSize(), sha, timestamp());
                return null;
            });
        } catch (RuntimeException error) {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            throw error;
        }
        return map("status", "0", "data", map("id", mediaId, "name", name, "kind", kind,
                "content_type", mime, "size", file.getSize(), "sha256", sha));
    }

    @PostMapping(path = "/api/v7/media/read", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<InputStreamResource> read(@RequestBody Map<String, Object> params) throws Exception {
        var media = storage.transaction(false, db -> {
            var user = Accounts.authenticate(db, params);
            var row = one(db, "SELECT * FROM media WHERE id=? AND owner=?", string(params.get("id"), 64, true), user.get("id"));
            if (row == null) throw new ApiException(3, "无权访问此附件");
            Collaboration.memberTeam(db, id(row, "team_id"), id(user, "id"), false);
            var note = SyncService.lookup(db, id(row, "team_id"), 49, text(row, "note_id"));
            if (note != null && id(note, "mode") == 3) throw new ApiException(3, "便签已删除");
            return row;
        });
        ensureBucket();
        var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(text(media, "object_key")).build());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(text(media, "content_type")))
                .contentLength(id(media, "size")).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(new InputStreamResource(input));
    }

    static void validateAttachments(Connection db, long user, long team, String note, Object value) throws SQLException {
        List<Map<String, Object>> attachments = objects(value);
        if (attachments.size() > 30) throw new ApiException(1, "每条便签最多保存 30 个附件");
        Set<String> ids = new HashSet<>();
        for (var attachment : attachments) {
            String id = string(attachment.get("id"), 64, true);
            if (!ids.add(id) || one(db, "SELECT 1 FROM media WHERE id=? AND owner=? AND team_id=? AND note_id=?",
                    id, user, team, note) == null) throw new ApiException(3, "附件不属于此便签");
        }
    }

    static String detect(byte[] head, String kind) {
        String raw = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (kind.equals("image")) {
            if (head.length >= 3 && (head[0] & 255) == 255 && (head[1] & 255) == 216 && (head[2] & 255) == 255) return "image/jpeg";
            if (raw.startsWith("\u0089PNG\r\n\u001a\n")) return "image/png";
            if (raw.startsWith("GIF87a") || raw.startsWith("GIF89a")) return "image/gif";
            if (raw.startsWith("RIFF") && raw.length() >= 12 && raw.substring(8, 12).equals("WEBP")) return "image/webp";
            if (raw.startsWith("BM")) return "image/bmp";
            if (raw.startsWith("II*\0") || raw.startsWith("MM\0*")) return "image/tiff";
            if (raw.length() > 12 && raw.substring(4, 8).equals("ftyp")) {
                if (raw.contains("avif") || raw.contains("avis")) return "image/avif";
                if (raw.contains("heic") || raw.contains("heix") || raw.contains("mif1")) return "image/heic";
            }
        } else {
            if (raw.startsWith("RIFF") && raw.length() >= 12 && raw.substring(8, 12).equals("WAVE")) return "audio/wav";
            if (raw.startsWith("OggS")) return "audio/ogg";
            if (raw.startsWith("ID3") || head.length > 1 && (head[0] & 255) == 255 && (head[1] & 224) == 224) return "audio/mpeg";
            if (raw.length() > 12 && raw.substring(4, 8).equals("ftyp")) return "audio/mp4";
        }
        throw new ApiException(1, "文件内容不是支持的图片或语音格式");
    }

    @ExceptionHandler(ApiException.class)
    Map<String, Object> invalid(ApiException error) {
        return map("status", error.status, "message", error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unavailable(Exception error) {
        org.slf4j.LoggerFactory.getLogger(MediaStorage.class)
                .warn("附件请求失败，异常类型 {}", error.getClass().getSimpleName());
        return ResponseEntity.status(503).body(map("status", "1", "message", "附件存储暂时不可用，请重试"));
    }
}
