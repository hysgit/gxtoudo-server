package com.gxtodo.server;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.gxtodo.server.Protocol.*;

@RestController
final class HttpController {
    private static final Logger LOG = LoggerFactory.getLogger(HttpController.class);
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final Map<String, String> DOCUMENTS = Map.of("ServiceAgreement", "/agreement",
            "PrivacyPolicy", "/privacy", "ChildrenPrivacyStatement", "/minor_privacy");
    private final ApiService api;
    private final Accounts accounts;
    private final Database storage;
    private final MediaStorage media;

    HttpController(ApiService api, Accounts accounts, Database storage, MediaStorage media) {
        this.api = api;
        this.accounts = accounts;
        this.storage = storage;
        this.media = media;
    }

    @RequestMapping(path = "/api/v7/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    ResponseEntity<String> api(HttpServletRequest request) throws IOException {
        Map<String, Object> params = params(request);
        String actual = request.getMethod();
        Object supplied = params.getOrDefault("_method", actual);
        if (!(supplied instanceof String method) || !Set.of("GET", "POST", "PUT", "DELETE").contains(method)
                || (actual.equals("GET") && !method.equals("GET"))) {
            throw new ApiException(1, "请求方法不支持");
        }
        String route = request.getRequestURI().substring("/api/v7".length());
        if (route.equals("/time.php") && actual.equals("GET")) {
            return response(Long.toString(System.currentTimeMillis()), MediaType.TEXT_PLAIN, 200);
        }
        Map<String, Object> result = map("status", "0");
        result.putAll(api.dispatch(route, method, params));
        if (route.equals("/price/login")) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(json(result));
        }
        return jsonResponse(result, 200);
    }

    @RequestMapping(path = "/health", method = RequestMethod.GET)
    ResponseEntity<String> health() throws Exception {
        storage.transaction(false, db -> Database.one(db, "SELECT 1"));
        media.health();
        return jsonResponse(map("status", "ok"), 200);
    }

    @RequestMapping(path = "/intro/v.txt", method = RequestMethod.GET)
    ResponseEntity<String> version() {
        return jsonResponse(map("status", "0", "version_code", 112, "version_name", "7.2.0"), 200);
    }

    @RequestMapping(path = "/reset", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<String> reset(HttpServletRequest request) throws IOException {
        Map<String, Object> params = params(request);
        try {
            if (request.getMethod().equals("POST")) {
                accounts.resetPassword(params.get("token"), params.get("password"));
                return page("密码已重置", "<p>请在高效Todo里重新登录。</p>", 200);
            }
            String token = escape(text(params, "token"));
            return page("重置密码", "<form action=\"/reset\" method=\"post\">"
                    + "<input type=\"hidden\" name=\"token\" value=\"" + token + "\">"
                    + "<label>新密码 <input type=\"password\" name=\"password\" autocomplete=\"new-password\" "
                    + "minlength=\"6\" maxlength=\"128\" required></label> <button>保存</button></form>", 200);
        } catch (ApiException error) {
            return page("重置失败", "<p>" + escape(error.getMessage()) + "</p>", 400);
        }
    }

    @RequestMapping(path = {"/agreement", "/privacy", "/minor_privacy", "/intro/m-help.php", "/intro/m-detail.php"},
            method = RequestMethod.GET)
    ResponseEntity<String> document(HttpServletRequest request) throws IOException {
        String route = request.getRequestURI();
        if (route.equals("/intro/m-detail.php")) route = DOCUMENTS.getOrDefault(text(params(request), "type"), "/intro/m-help.php");
        return switch (route) {
            case "/agreement" -> page("使用协议", "<p>这是高效Todo自建服务。请使用你获授权的实例，并妥善保管账号。</p>", 200);
            case "/privacy" -> page("隐私政策", "<p>实例保存账号、任务、便签、账单及其图片、语音和文件用于同步。你授权提供的账单地点与坐标随该条账单保存。任务等协作内容按空间权限访问，便签和记账按所属账号隔离。定位仅在新增账单或手动刷新时请求，拒绝定位仍可保存。上传文件保留原始内容与元数据。启用远程推送时，实例保存设备推送标识和时区，向设备的 Apple 或 Google 推送服务提交提醒内容和设备推送标识。使用 AI 对话时，问题、相关记录片段和计算结果会发送到已配置的模型服务。商品追踪保存商品、地区和价格。使用 App 内平台登录时，页面操作转发至本实例的浏览器，再由官方网页处理。登录会话按 Todo 账号、平台保存在浏览器持久卷中。输入的密码、验证码不会写入 Todo 数据库或访问日志。</p>"
                    + "可在账户设置注销账号。备份和数据保留由实例管理员负责。</p>", 200);
            case "/minor_privacy" -> page("未成年人隐私说明", "<p>未成年人应在监护人同意和指导下使用本服务。</p>", 200);
            default -> page("使用帮助", "<p>在空间菜单管理个人空间或团队。登录后点击同步，在其他设备登录同一账号即可查看已同步数据。</p>", 200);
        };
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<String> invalid(ApiException error) {
        return jsonResponse(map("status", error.status, "message", error.getMessage()), 200);
    }

    @ExceptionHandler({Database.StorageException.class, MailException.class})
    ResponseEntity<String> unavailable(Exception error) {
        LOG.warn("服务请求失败，异常类型 {}", error.getClass().getSimpleName());
        return jsonResponse(map("status", "1", "message", "服务暂时不可用"), 503);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpected(Exception error) {
        LOG.error("未完成请求，异常类型 {}", error.getClass().getSimpleName());
        return jsonResponse(map("status", "1", "message", "无法完成请求"), 500);
    }

    private static Map<String, Object> params(HttpServletRequest request) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        form(params, request.getQueryString());
        if (request.getMethod().equals("POST") || request.getMethod().equals("PUT")) {
            if (request.getContentLengthLong() > MAX_BODY) throw new ApiException(1, "单次请求超过 16 MB");
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) throw new ApiException(1, "单次请求超过 16 MB");
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            } catch (CharacterCodingException error) {
                throw new ApiException(1, "请求必须使用 UTF-8");
            }
            if (request.getContentType() != null && request.getContentType().split(";", 2)[0].equalsIgnoreCase("application/json")) {
                params.putAll(object(content));
            } else {
                form(params, content);
            }
        }
        if (params.size() > 128) throw new ApiException(1, "请求参数过多");
        return params;
    }

    private static void form(Map<String, Object> params, String encoded) {
        if (encoded == null || encoded.isEmpty()) return;
        String[] pairs = encoded.split("&", -1);
        if (pairs.length > 128) throw new ApiException(1, "请求参数过多");
        try {
            for (String pair : pairs) {
                String[] field = pair.split("=", 2);
                params.put(URLDecoder.decode(field[0], StandardCharsets.UTF_8),
                        field.length == 2 ? URLDecoder.decode(field[1], StandardCharsets.UTF_8) : "");
            }
        } catch (IllegalArgumentException error) {
            throw new ApiException(1, "请求参数编码错误");
        }
    }

    private static ResponseEntity<String> jsonResponse(Object value, int status) {
        return response(json(value), MediaType.APPLICATION_JSON, status);
    }

    private static ResponseEntity<String> response(String value, MediaType type, int status) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, type + ";charset=UTF-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer").body(value);
    }

    private static ResponseEntity<String> page(String title, String content, int status) {
        return response("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><main style=\"max-width:40em;margin:2em auto;padding:0 1em;font-family:sans-serif\">"
                + "<h1>" + title + "</h1>" + content + "</main></html>", MediaType.TEXT_HTML, status);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
