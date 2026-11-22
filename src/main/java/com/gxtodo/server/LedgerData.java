package com.gxtodo.server;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static com.gxtodo.server.Protocol.*;

final class LedgerData {
    private LedgerData() {}

    static void validate(Map<String,Object> data) {
        if (!Set.of("expense","income","transfer").contains(text(data,"type"))) {
            throw new ApiException(1,"账单类型无效");
        }
        String amount=text(data,"amount");
        if (!amount.matches("[0-9]{1,30}(\\.[0-9]{1,18})?")) throw new ApiException(1,"金额格式无效");
        // Preserve the exact decimal scale; never convert money through binary floating point.
        data.put("amount",new BigDecimal(amount).toPlainString());
        try { LocalDate.parse(text(data,"date")); }
        catch (java.time.format.DateTimeParseException error) { throw new ApiException(1,"账单日期无效"); }
        string(data.get("name"),500,true);
        string(data.get("channel"),100,true);
        string(data.get("party"),100,true);
        if (data.containsKey("include_in_totals")) number(data.get("include_in_totals"), 0, 1);
        if (text(data,"latitude").isBlank()!=text(data,"longitude").isBlank()) throw new ApiException(1,"定位坐标不完整");
        if (!text(data,"latitude").isBlank()) {
            coordinate(text(data,"latitude"),90); coordinate(text(data,"longitude"),180);
        }
    }

    private static void coordinate(String value,int limit) {
        try {
            if (new BigDecimal(value).abs().compareTo(BigDecimal.valueOf(limit))>0) throw new NumberFormatException();
        } catch (NumberFormatException error) { throw new ApiException(1,"定位坐标无效"); }
    }
}
