package com.gxtodo.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Protocol {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static final int OWNER = 7680;
    static final int MEMBER = 448;
    static final String FAR_FUTURE = "2099-12-31 23:59:59";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final String COMMON = "uid user_id team_id entropy created_at updated_at ";
    static final Map<Integer, Set<String>> TABLES = Map.ofEntries(
            table(49, "name sequence status attachments"), table(59, "name"),
            table(69, "name map_type coor_type longitude latitude country province city district"),
            table(76, "task_id schedule_id start_at begin_time check_at cost_time role score status"),
            table(77, "schedule_id task_id alert_time"),
            table(78, "schedule_id task_id status repeat_start repeat_end repeat_count repeat_start_day "
                    + "day_interval repeat_start_week week_interval repeat_start_month month_interval "
                    + "repeat_start_year year_interval month day week weekday"),
            table(79, "task_id start_at end_at is_all_day is_repeat"),
            table(83, "task_id child_task_id status"), table(84, "task_id tag_name"),
            table(86, "content task_id type"), table(87, "name task_id sequence"),
            table(88, "task_id role sequence status"),
            table(89, "name rank privacy project_id position_id weight"),
            table(98, "project_id sequence role status"), table(99, "name intro color privacy status"));

    private Protocol() {}

    private static Map.Entry<Integer, Set<String>> table(int id, String fields) {
        return Map.entry(id, Set.of((COMMON + fields).split(" ")));
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    static long number(Object value) {
        return number(value, 0, Long.MAX_VALUE);
    }

    static long number(Object value, long minimum, long maximum) {
        if (!(value instanceof String || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            throw new ApiException(1, "无效的数字参数");
        }
        try {
            long result = Long.parseLong(value.toString().strip());
            if (result < minimum || result > maximum) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException error) {
            throw new ApiException(1, "数字参数无效或超出范围");
        }
    }

    static String string(Object value, int limit, boolean required) {
        if (!(value instanceof String result) || result.codePointCount(0, result.length()) > limit
                || (required && result.isBlank())) {
            throw new ApiException(1, "文本参数无效");
        }
        return result;
    }

    static String text(Map<String, Object> data, String key) {
        return data.get(key) == null ? "" : data.get(key).toString();
    }

    static long id(Map<String, Object> data, String key) {
        return number(data.get(key));
    }

    static Object parse(String value) {
        try {
            return JSON.readValue(value, Object.class);
        } catch (JsonProcessingException error) {
            throw new ApiException(1, "JSON 格式错误");
        }
    }

    static Map<String, Object> object(Object value) {
        Object decoded = value instanceof String text ? parse(text) : value;
        if (!(decoded instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new ApiException(1, "对象参数格式错误");
        }
        return JSON.convertValue(map, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    static List<Map<String, Object>> objects(Object value) {
        Object decoded = value instanceof String text ? parse(text) : value;
        if (!(decoded instanceof List<?> list)) {
            throw new ApiException(1, "数组参数格式错误");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) {
            result.add(object(entry));
        }
        return result;
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("无法序列化响应", error);
        }
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String timestamp() {
        return TIMESTAMP.format(Instant.now());
    }
}
