package com.gxtodo.server;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.gxtodo.server.Protocol.map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PushScheduleTest {
    @Test
    void recurringRemindersUseDeviceCalendarAndLocalTimeAcrossDaylightSaving() {
        ZoneId zone = ZoneId.of("America/New_York");
        long first = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, zone).toEpochSecond();
        long next = ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, zone).toEpochSecond();
        long at = next - 600;
        var schedule = map("start_at", first);
        var alert = map("alert_time", 600, "reminder_mode", "notification");
        var repeat = map("repeat_start_day", first, "day_interval", 86400);
        assertEquals(java.util.List.of(new PushSchedule.Due(next, at)),
                PushSchedule.due(schedule, alert, repeat, zone, at + 10));
        assertEquals(java.util.List.of(), PushSchedule.due(schedule, alert, repeat, zone, at - 1));
        assertEquals(java.util.List.of(), PushSchedule.due(schedule, alert, repeat, zone, at + PushSchedule.WINDOW));
    }
}
