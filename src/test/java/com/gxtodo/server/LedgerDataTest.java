package com.gxtodo.server;

import org.junit.jupiter.api.Test;
import static com.gxtodo.server.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerDataTest {
    @Test void preciseAmountsAndInvalidDates() {
        var value=map("name","事项","date","2026-11-20","type","expense","amount","9007199254740993.010000000000000001",
                "channel","支付宝","party","工行卡");
        LedgerData.validate(value);
        assertEquals("9007199254740993.010000000000000001",value.get("amount"));
        value.put("amount","1e3");assertThrows(ApiException.class,()->LedgerData.validate(value));
        value.put("amount","1.00");value.put("date","2026-02-30");assertThrows(ApiException.class,()->LedgerData.validate(value));
        value.put("date","2026-11-20");value.put("latitude","30");assertThrows(ApiException.class,()->LedgerData.validate(value));
    }
}
