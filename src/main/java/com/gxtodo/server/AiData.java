package com.gxtodo.server;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.gxtodo.server.Database.query;
import static com.gxtodo.server.Protocol.*;

@Service
final class AiData {
    private final Database storage;
    private static final String SCOPE = """
            FROM objects o JOIN teams t ON t.id=o.team_id AND t.status=0
            JOIN members m ON m.team_id=o.team_id AND m.user_id=?
            WHERE o.mode<>3 AND o.table_id=
            """;

    AiData(Database storage) { this.storage = storage; }

    record Output(Map<String, Object> data, List<Map<String, Object>> refs) {}
    record Query(ZoneId zone, LocalDate from, LocalDate to, String term, int offset, int limit, long space) {
        static Query parse(Map<String, Object> params, ZoneId zone) {
            var from = date(params, "from");
            var to = date(params, "to");
            if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("起始日期晚于结束日期");
            return new Query(zone, from, to, text(params, "keyword").strip().toLowerCase(Locale.ROOT),
                    (int) number(params.getOrDefault("offset", 0), 0, 50000),
                    (int) number(params.getOrDefault("limit", 20), 1, 40),
                    number(params.getOrDefault("space_id", 0)));
        }

        private static LocalDate date(Map<String, Object> params, String key) {
            String value = text(params, key);
            return value.isBlank() ? null : LocalDate.parse(value);
        }

        boolean filter(LocalDate day) {
            return (from == null || day != null && !day.isBefore(from))
                    && (to == null || day != null && !day.isAfter(to));
        }
        boolean matches(String... values) {
            return term.isEmpty() || Arrays.stream(values).anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(term));
        }
    }

    Output call(long user, ZoneId zone, String tool, Map<String, Object> params) {
        if (tool.equals("calculate")) return calculate(params);
        var request = Query.parse(params, zone);
        return storage.transaction(false, db -> switch (tool) {
            case "query_tasks" -> tasks(db, user, request, text(params, "status"));
            case "query_bills", "summarize_bills" -> bills(db, user, request, params, tool.equals("summarize_bills"));
            case "query_notes" -> notes(db, user, request);
            default -> throw new IllegalArgumentException("未知的查询工具");
        });
    }

    private static List<Map<String, Object>> rows(Connection db, long user, long space, int table)
            throws SQLException {
        return query(db, "SELECT o.uid,o.team_id,t.name AS space,o.data " + SCOPE + "? "
                + "AND (?=0 OR o.team_id=?) AND o.data::jsonb->>'user_id'=? "
                + "ORDER BY o.revision DESC LIMIT 50001",
                user, table, space, space, Long.toString(user));
    }

    private static void ensureSize(List<?> rows) {
        if (rows.size() > 50000) throw new IllegalArgumentException("记录过多，请指定一个空间再查询");
    }

    private static Map<String, Object> source(Map<String, Object> row, int table, String title) {
        return map("uid", row.get("uid"), "table", table, "team_id", row.get("team_id"), "name", clip(title, 100));
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private static Output page(List<Map<String, Object>> matches, Query request) {
        int start = Math.min(request.offset(), matches.size());
        var selected = matches.subList(start, Math.min(start + request.limit(), matches.size()));
        return new Output(map("match_count", matches.size(), "offset", start, "items", selected),
                selected.stream().map(item -> object(item.get("source"))).toList());
    }

    private static Output bills(Connection db, long user, Query request, Map<String, Object> params, boolean summary)
            throws SQLException {
        String type = text(params, "type"), party = text(params, "party"), channel = text(params, "channel");
        boolean all = "all".equals(text(params, "inclusion"));
        var rows = rows(db, user, request.space(), 51);
        ensureSize(rows);
        BigDecimal expense = BigDecimal.ZERO, income = BigDecimal.ZERO, transfer = BigDecimal.ZERO;
        var groups = new TreeMap<String, BigDecimal>();
        var matches = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var bill = object(row.get("data"));
            String name = text(bill, "name"), category = text(bill, "type");
            String billParty = text(bill, "party"), billChannel = text(bill, "channel");
            boolean included = !"0".equals(text(bill, "include_in_totals"));
            if (!request.filter(LocalDate.parse(text(bill, "date")))
                    || !request.matches(name, text(bill, "remark"), billParty, billChannel, text(bill, "location"))
                    || !type.isEmpty() && !type.equals(category) || !party.isEmpty() && !party.equals(billParty)
                    || !channel.isEmpty() && !channel.equals(billChannel)
                    || summary && !(all || included && !category.equals("transfer"))) continue;
            BigDecimal amount = new BigDecimal(text(bill, "amount"));
            switch (category) {
                case "expense" -> {
                    expense = expense.add(amount);
                    groups.merge(billParty, amount, BigDecimal::add);
                }
                case "income" -> income = income.add(amount);
                case "transfer" -> transfer = transfer.add(amount);
                default -> { continue; }
            }
            matches.add(map("source", source(row, 51, name), "amount", amount.toPlainString(), "type", category,
                    "date", text(bill, "date"), "channel", billChannel, "party", billParty,
                    "included", included, "remark", clip(text(bill, "remark"), 240)));
        }
        matches.sort(Comparator.comparing(item -> text(item, "date"), Comparator.reverseOrder()));
        if (!summary) return page(matches, request);
        var breakdown = new TreeMap<String, String>();
        groups.forEach((label, amount) -> breakdown.put(label, amount.toPlainString()));
        var result = page(matches, request);
        return new Output(map("count", matches.size(), "expense", expense.toPlainString(), "income", income.toPlainString(),
                "transfer", transfer.toPlainString(), "expense_by_party", breakdown,
                "scope", all ? "all" : "counted", "examples", result.data().get("items")), result.refs());
    }

    private static Output notes(Connection db, long user, Query request) throws SQLException {
        var notes = rows(db, user, request.space(), 49);
        var entries = rows(db, user, request.space(), 50);
        ensureSize(notes);
        ensureSize(entries);
        var lookup = new HashMap<String, List<String>>();
        Collections.reverse(entries);
        for (var row : entries) {
            var entry = object(row.get("data"));
            if ("text".equals(text(entry, "kind"))) {
                String parent = row.get("team_id") + ":" + text(entry, "note_id");
                lookup.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(text(entry, "content"));
            }
        }
        var matches = new ArrayList<Map<String, Object>>();
        for (var row : notes) {
            var note = object(row.get("data"));
            String date = text(note, "created_at");
            if (!request.filter(date.length() < 10 ? null : LocalDate.parse(date.substring(0, 10)))) continue;
            String title = text(note, "title");
            if (title.isBlank()) title = clip(text(note, "name"), 80);
            String legacy = text(note, "name");
            var lines = lookup.getOrDefault(row.get("team_id") + ":" + row.get("uid"), List.of());
            var matchingLines = lines.stream().filter(request::matches).toList();
            if (!request.matches(title, legacy) && matchingLines.isEmpty()) continue;
            boolean titleMatch = request.matches(title, legacy);
            String content = clip(String.join("\n", titleMatch ? lines : matchingLines), 2800);
            if (content.isBlank()) content = clip(legacy, 2800);
            matches.add(map("source", source(row, 49, title), "created_at", date, "text", content,
                    "text_entry_count", lines.size(), "matched_entry_count", matchingLines.size()));
        }
        return page(matches, request);
    }

    private static Output tasks(Connection db, long user, Query request, String state) throws SQLException {
        var records = rows(db, user, request.space(), 89);
        ensureSize(records);
        var ownedTasks = new HashSet<String>();
        for (var row : records) ownedTasks.add(row.get("team_id") + ":" + row.get("uid"));
        var related = new HashMap<String, Map<Integer, List<Map<String, Object>>>>();
        for (int table : List.of(76, 78, 79, 84, 86, 87)) {
            var rows = rows(db, user, request.space(), table);
            ensureSize(rows);
            for (var row : rows) {
                var detail = object(row.get("data"));
                String key = row.get("team_id") + ":" + text(detail, "task_id");
                if (!ownedTasks.contains(key)) continue;
                related.computeIfAbsent(key, ignored -> new HashMap<>())
                        .computeIfAbsent(table, ignored -> new ArrayList<>()).add(detail);
            }
        }
        var matches = new ArrayList<Map<String, Object>>();
        var now = ZonedDateTime.now(request.zone());
        LocalDate today = now.toLocalDate();
        boolean ranged = request.from() != null || request.to() != null;
        LocalDate from = request.from() != null ? request.from() : request.to() == null
                ? state.isEmpty() ? today : today.minusDays(30) : request.to().minusDays(30);
        LocalDate to = request.to() != null ? request.to() : request.from() == null ? today.plusDays(30) : from.plusDays(30);
        if (ChronoUnit.DAYS.between(from, to) > 366) throw new IllegalArgumentException("请将任务发生日期限制在一年内");
        for (var row : records) {
            var task = object(row.get("data"));
            var items = related.getOrDefault(row.get("team_id") + ":" + row.get("uid"), Map.of());
            var schedule = first(items.get(79));
            long start = PushSchedule.value(schedule, "start_at", 0);
            if (start > 253402300799L) continue;
            var repeat = first(items.get(78));
            var checks = items.getOrDefault(76, List.of());
            var completed = new HashSet<Long>();
            for (var check : checks) if (PushSchedule.value(check, "status", 0) == 1) {
                completed.add(PushSchedule.value(check, "start_at", 0));
            }
            var details = items.getOrDefault(86, List.of()).stream().map(value -> text(value, "content")).toList();
            var tags = items.getOrDefault(84, List.of()).stream().map(value -> text(value, "tag_name")).toList();
            var children = items.getOrDefault(87, List.of()).stream().map(value -> text(value, "name")).toList();
            String name = text(task, "name");
            if (!request.matches(name, String.join(" ", details), String.join(" ", tags), String.join(" ", children))) continue;
            var base = map("source", source(row, 89, name), "space", row.get("space"), "priority", task.get("rank"),
                    "text", clip(String.join("\n", details), 750), "tags", tags, "children", children);
            if (repeat.isEmpty() || start == 0) {
                var day = start > 0 ? Instant.ofEpochSecond(start).atZone(request.zone()).toLocalDate() : null;
                if (request.filter(day)) addTask(matches, base, start, schedule, !completed.isEmpty(), state, now);
                continue;
            }
            if (!ranged && state.isEmpty()) {
                var next = from;
                if (Instant.ofEpochSecond(start).atZone(request.zone()).toLocalDate().isAfter(from))
                    next = Instant.ofEpochSecond(start).atZone(request.zone()).toLocalDate();
                for (var day = next; !day.isAfter(next.plusDays(366)); day = day.plusDays(1)) {
                    long at = PushSchedule.occurrence(start, repeat, day, request.zone());
                    if (at > 0) {
                        addTask(matches, base, at, schedule, completed.contains(at), state, now);
                        break;
                    }
                }
                continue;
            }
            for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
                long at = PushSchedule.occurrence(start, repeat, day, request.zone());
                if (at > 0) addTask(matches, base, at, schedule, completed.contains(at), state, now);
                if (matches.size() > 50000) throw new IllegalArgumentException("任务发生次数过多，请缩小日期或空间");
            }
        }
        if (!ranged && !state.isEmpty()) {
            var result = page(matches, request);
            result.data().put("recurring_window", map("from", from.toString(), "to", to.toString()));
            return result;
        }
        return page(matches, request);
    }

    private static Map<String, Object> first(List<Map<String, Object>> values) {
        return values == null || values.isEmpty() ? Map.of() : values.get(0);
    }

    private static void addTask(List<Map<String, Object>> result, Map<String, Object> base, long at,
                                Map<String, Object> schedule, boolean completed, String filter, ZonedDateTime now) {
        long original = PushSchedule.value(schedule, "start_at", 0);
        long end = PushSchedule.value(schedule, "end_at", original);
        long elapsed = end >= original && end <= 253402300799L ? end - original : 0;
        String state = completed ? "done" : at == 0 ? "unscheduled" : at + elapsed < now.toEpochSecond() ? "overdue" : "open";
        if (!filter.isEmpty() && !filter.equals(state) && !(filter.equals("pending") && !completed)) return;
        var row = new LinkedHashMap<>(base);
        row.put("status", state);
        row.put("start", at == 0 ? "" : Instant.ofEpochSecond(at).atZone(now.getZone()).toLocalDateTime().toString());
        result.add(row);
    }

    private static Output calculate(Map<String, Object> params) {
        String operation = text(params, "operation");
        BigDecimal left = decimal(text(params, "left")), right = decimal(text(params, "right"));
        var value = switch (operation) {
            case "add" -> left.add(right);
            case "subtract" -> left.subtract(right);
            case "multiply" -> left.multiply(right);
            case "divide" -> left.divide(right, MathContext.DECIMAL128);
            default -> throw new IllegalArgumentException("未知的计算操作");
        };
        return new Output(map("value", value.stripTrailingZeros().toPlainString()), List.of());
    }

    private static BigDecimal decimal(String value) {
        if (!value.matches("-?[0-9]{1,60}(\\.[0-9]{1,50})?")) throw new IllegalArgumentException("小数格式无效");
        return new BigDecimal(value);
    }
}
