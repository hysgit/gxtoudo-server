package com.gxtodo.server;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static com.gxtodo.server.Protocol.*;

@Component
final class PushProviders {
    enum Result { SENT, INVALID_TOKEN, RETRY }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Credentials apns, fcm;
    private static final String TOPIC = "net.icycloud.fdtodolist";
    private static final java.util.Set<String> INVALID_APNS = java.util.Set.of("Unregistered", "BadDeviceToken");

    private record Credentials(String id, String account, PrivateKey key) {}
    private record Token(String value, long until) {}
    private Token appleToken, googleToken;

    PushProviders(org.springframework.core.env.Environment env) {
        apns = load("APNs", env.getProperty("GXTODO_APNS_KEY_FILE", ""), path ->
                new Credentials(required(env.getProperty("GXTODO_APNS_KEY_ID")),
                        required(env.getProperty("GXTODO_APNS_TEAM_ID")), key("EC", Files.readString(path))));
        fcm = load("FCM", env.getProperty("GXTODO_FCM_ACCOUNT_FILE", ""), path -> {
            var service = object(Files.readString(path));
            String project = text(service, "project_id");
            if (!project.matches("[a-z0-9][a-z0-9-]{3,62}")) throw new IllegalArgumentException();
            return new Credentials(project, required(text(service, "client_email")),
                    key("RSA", text(service, "private_key")));
        });
    }

    private interface Loader { Credentials parse(Path path) throws Exception; }

    private static Credentials load(String provider, String file, Loader loader) {
        if (file.isBlank()) return null;
        try { return loader.parse(Path.of(file)); }
        catch (Exception error) {
            LoggerFactory.getLogger(PushProviders.class).warn("{} push credentials could not be loaded", provider);
            return null;
        }
    }

    boolean available(String provider) {
        return switch (provider) {
            case "apns", "apns_sandbox" -> apns != null;
            case "fcm" -> fcm != null;
            default -> false;
        };
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static PrivateKey key(String algorithm, String pem) throws Exception {
        String bytes = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s+", "");
        return KeyFactory.getInstance(algorithm).generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(bytes)));
    }

    private static String base64(byte[] data) { return Base64.getUrlEncoder().withoutPadding().encodeToString(data); }

    private static String jwt(String algorithm, PrivateKey key, Map<String, Object> header,
                              Map<String, Object> claims) throws Exception {
        String body = base64(json(header).getBytes(StandardCharsets.UTF_8)) + "."
                + base64(json(claims).getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update(body.getBytes(StandardCharsets.US_ASCII));
        return body + "." + base64(signature.sign());
    }

    private HttpResponse<String> post(HttpRequest.Builder request, String body) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private synchronized String appleToken(long now) throws Exception {
        if (appleToken == null || appleToken.until() <= now) {
            appleToken = new Token(jwt("SHA256withECDSAinP1363Format", apns.key(),
                    map("alg", "ES256", "kid", apns.id()), map("iss", apns.account(), "iat", now)), now + 3000);
        }
        return appleToken.value();
    }

    private synchronized String googleToken(long now) throws Exception {
        if (googleToken == null || googleToken.until() <= now) {
            String endpoint = "https://oauth2.googleapis.com/token";
            String assertion = jwt("SHA256withRSA", fcm.key(), map("alg", "RS256", "typ", "JWT"),
                    map("iss", fcm.account(), "scope", "https://www.googleapis.com/auth/firebase.messaging",
                            "aud", endpoint, "iat", now, "exp", now + 3600));
            var response = post(HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded"),
                    "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
                            + URLEncoder.encode(assertion, StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("FCM authentication failed");
            var data = object(response.body());
            googleToken = new Token(required(text(data, "access_token")), now + number(data.get("expires_in")) - 60);
        }
        return googleToken.value();
    }

    Result send(String provider, String device, String tag, String title, long now, long expires) throws Exception {
        if (provider.startsWith("apns")) {
            String host = provider.equals("apns_sandbox") ? "api.sandbox.push.apple.com" : "api.push.apple.com";
            var response = post(HttpRequest.newBuilder(URI.create("https://" + host + "/3/device/" + device))
                    .version(HttpClient.Version.HTTP_2).header("authorization", "bearer " + appleToken(now))
                    .header("apns-topic", TOPIC).header("apns-push-type", "alert")
                    .header("apns-priority", "10").header("apns-expiration", Long.toString(expires))
                    .header("apns-collapse-id", tag).header("Content-Type", "application/json"),
                    json(map("aps", map("alert", map("title", title, "body", "任务提醒"), "sound", "default"))));
            if (response.statusCode() == 200) return Result.SENT;
            String reason = response.body().isBlank() ? "" : text(object(response.body()), "reason");
            return INVALID_APNS.contains(reason) ? Result.INVALID_TOKEN : Result.RETRY;
        }
        var response = post(HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + fcm.id() + "/messages:send"))
                .header("authorization", "Bearer " + googleToken(now)).header("Content-Type", "application/json"),
                json(map("message", map("token", device, "notification", map("title", title, "body", "任务提醒"),
                        "android", map("priority", "high", "ttl", Math.max(0, expires - now) + "s",
                                "notification", map("channel_id", "task_remote_push", "tag", tag, "sound", "default")),
                        "data", map("task_push_tag", tag)))));
        if (response.statusCode() == 200) return Result.SENT;
        return response.statusCode() == 404 && response.body().contains("\"UNREGISTERED\"")
                ? Result.INVALID_TOKEN : Result.RETRY;
    }

}
