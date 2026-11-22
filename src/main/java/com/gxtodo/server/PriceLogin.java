package com.gxtodo.server;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static com.gxtodo.server.Protocol.*;

@Service
final class PriceLogin {
    private static final Set<String> ACTIONS = Set.of("status", "start", "frame", "tap", "scroll", "drag",
            "type", "key", "reset", "finish", "cancel");
    private static final Set<String> KEYS = Set.of("Enter", "Tab", "Backspace", "Escape", "ControlOrMeta+a");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI worker;
    private final String credential;

    PriceLogin(Environment environment) {
        String url = environment.getProperty("GXTODO_PRICE_BROWSER_URL", "").strip();
        String file = environment.getProperty("GXTODO_PRICE_BROWSER_TOKEN_FILE", "").strip();
        worker = url.isEmpty() ? null : URI.create(url.replaceFirst("/+$", "") + "/login");
        try {
            credential = file.isEmpty() ? "" : Files.readString(Path.of(file)).strip();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取商品浏览器密钥");
        }
    }

    Map<String, Object> call(Map<String, Object> user, Map<String, Object> params) {
        if (worker == null || credential.isBlank()) throw new ApiException(1, "此服务端还没有启用平台登录");
        String platform = text(params, "platform");
        String action = text(params, "action");
        if (!Set.of("jd", "taobao").contains(platform) || !ACTIONS.contains(action))
            throw new ApiException(1, "平台登录参数无效");
        var payload = map("user", id(user, "id"), "login", id(user, "login_id"),
                "platform", platform, "action", action);
        if (!Set.of("status", "start").contains(action)) {
            String session = string(params.get("session"), 48, true);
            if (!session.matches("[a-f0-9]{48}")) throw new ApiException(1, "平台登录会话无效");
            payload.put("session", session);
        }
        switch (action) {
            case "tap", "scroll" -> {
                payload.put("x", number(params.get("x"), 0, 389));
                payload.put("y", number(params.get("y"), 0, 759));
                if (action.equals("scroll")) payload.put("dy", number(params.get("dy"), -1000, 1000));
            }
            case "type" -> payload.put("input", string(params.get("input"), 512, true));
            case "drag" -> {
                for (String corner : Set.of("from", "to")) {
                    payload.put(corner + "_x", number(params.get(corner + "_x"), 0, 389));
                    payload.put(corner + "_y", number(params.get(corner + "_y"), 0, 759));
                }
            }
            case "key" -> {
                String key = text(params, "key");
                if (!KEYS.contains(key)) throw new ApiException(1, "网页按键无效");
                payload.put("key", key);
            }
        }
        try {
            var request = HttpRequest.newBuilder(worker).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + credential)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json(payload))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ApiException(1, "暂时无法连接平台浏览器");
            var result = object(response.body());
            String status = text(result, "status");
            if (!status.equals("0")) {
                throw new ApiException(status.equals("410") ? 410 : status.equals("409") ? 409 : 1,
                        text(result, "message"));
            }
            return map("data", object(result.get("data")));
        } catch (IOException error) {
            throw new ApiException(1, "暂时无法连接平台浏览器");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(1, "平台登录请求中断，请重试");
        }
    }
}
