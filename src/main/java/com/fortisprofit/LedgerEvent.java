package com.fortisprofit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class LedgerEvent {
    enum Type { CLEAR, DEATH, CASH_OUT, LOOT, CLEAR_LOOT, CASH_OUT_LOOT, DEATH_FEE, SUPPLIES, ADJUSTMENT }

    String account;
    String timestamp;
    Type type;
    long gp;
    String note;
    // 0 means unknown; missing in older v0.2/v0.3 JSON and defaults safely to 0.
    int wave;

    LedgerEvent(String account, Type type, long gp, String note) {
        this(account, type, gp, note, 0);
    }

    LedgerEvent(String account, Type type, long gp, String note, int wave) {
        this.account = account;
        this.timestamp = Instant.now().toString();
        this.type = type;
        this.gp = gp;
        this.note = note;
        this.wave = wave;
    }

    LocalDate date(ZoneId zone) { return Instant.parse(timestamp).atZone(zone).toLocalDate(); }
}
