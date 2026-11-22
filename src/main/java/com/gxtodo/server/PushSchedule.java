package com.gxtodo.server;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gxtodo.server.Protocol.text;

final class PushSchedule {
    record Due(long occurrence, long at) {}
    static final int WINDOW = 300;

    private PushSchedule() {}

    static long value(Map<String, Object> fields, String key, long fallback) {
        try { return Long.parseLong(text(fields, key)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    static List<Due> due(Map<String, Object> schedule, Map<String, Object> alert,
                         Map<String, Object> repeat, ZoneId zone, long now) {
        long start = value(schedule, "start_at", 0);
        long lead = value(alert, "alert_time", -1);
        if (start <= 0 || start > 253402300799L || lead < 0 || lead > 253402300799L
                || text(alert, "reminder_mode").equals("alarm")) return List.of();
        var first = Instant.ofEpochSecond(start).atZone(zone);
        boolean allDay = lead == 0 && value(schedule, "is_all_day", 0) == 1;
        long adjustment = lead > 864000 ? start - lead : lead;
        if (repeat.isEmpty()) {
            long at = allDay ? epoch(first.toLocalDate(), LocalTime.of(9, 0), zone) : start - adjustment;
            return at <= now && at > now - WINDOW ? List.of(new Due(start, at)) : List.of();
        }
        var result = new ArrayList<Due>();
        var firstDay = first.toLocalDate();
        var begin = Instant.ofEpochSecond(now - WINDOW + adjustment).atZone(zone).toLocalDate();
        var end = Instant.ofEpochSecond(now + adjustment).atZone(zone).toLocalDate();
        for (var day = begin; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.isBefore(firstDay) || !matches(day, firstDay, repeat)) continue;
            long occurrence = day.equals(firstDay) ? start : epoch(day, first.toLocalTime(), zone);
            long repeatsUntil = value(repeat, "repeat_end", 0);
            if (repeatsUntil > 0 && occurrence > repeatsUntil) continue;
            long at = allDay ? epoch(day, LocalTime.of(9, 0), zone) : occurrence - adjustment;
            if (at <= now && at > now - WINDOW) result.add(new Due(occurrence, at));
        }
        return result;
    }

    private static long epoch(LocalDate day, LocalTime time, ZoneId zone) {
        return day.atTime(time).atZone(zone).toEpochSecond();
    }

    private static boolean matches(LocalDate day, LocalDate first, Map<String, Object> repeat) {
        long elapsed = ChronoUnit.DAYS.between(first, day);
        long count = value(repeat, "repeat_count", 0);
        if (value(repeat, "repeat_start_day", 0) > 0) {
            long interval = Math.max(1, value(repeat, "day_interval", 0) / 86400);
            return elapsed % interval == 0 && (count == 0 || elapsed / interval < count);
        }
        if (value(repeat, "repeat_start_week", 0) > 0) {
            String mask = text(repeat, "weekday");
            int firstDay = first.getDayOfWeek().getValue() % 7;
            int weekday = day.getDayOfWeek().getValue() % 7;
            long week = (elapsed + firstDay) / 7;
            long interval = Math.max(1, value(repeat, "week_interval", 0) / 604800);
            if (mask.length() != 7 || mask.charAt(weekday) == '0' || week % interval != 0) return false;
            if (count == 0) return true;
            if (week == 0) return marked(mask.substring(firstDay, weekday + 1)) <= count;
            return marked(mask.substring(firstDay)) + (week - 1) / interval * marked(mask)
                    + marked(mask.substring(0, weekday + 1)) <= count;
        }
        int monthDay = (int) Math.min(value(repeat, "day", first.getDayOfMonth()), day.lengthOfMonth());
        if (value(repeat, "repeat_start_month", 0) > 0) {
            long months = (day.getYear() - first.getYear()) * 12L + day.getMonthValue() - first.getMonthValue();
            long interval = Math.max(1, value(repeat, "month_interval", 1));
            return day.getDayOfMonth() == monthDay && months % interval == 0
                    && (count == 0 || months / interval < count);
        }
        if (value(repeat, "repeat_start_year", 0) > 0) {
            long years = day.getYear() - first.getYear();
            long interval = Math.max(1, value(repeat, "year_interval", 1));
            return day.getDayOfMonth() == monthDay
                    && day.getMonthValue() == value(repeat, "month", first.getMonthValue())
                    && years % interval == 0 && (count == 0 || years / interval < count);
        }
        return elapsed == 0;
    }

    private static long marked(String mask) {
        return mask.chars().filter(character -> character != '0').count();
    }
}
