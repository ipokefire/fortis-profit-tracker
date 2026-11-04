package com.fortisprofit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Single source of truth for Today and History, derived from recorded ledger events. */
final class DailySummary
{
    int clears;
    int deaths;
    int cashOuts;
    long fullClearLoot;
    long cashOutLoot;
    long unassignedLoot;
    long quiverTurnIns;
    long totalCredits;
    long costs;
    long deathCosts;
    long supplyCosts;

    long otherCosts()
    {
        return costs - deathCosts - supplyCosts;
    }

    long net()
    {
        return totalCredits - costs;
    }

    void accept(LedgerEvent event)
    {
        if (event.type == LedgerEvent.Type.CLEAR) clears++;
        if (event.type == LedgerEvent.Type.CASH_OUT) cashOuts++;
        if (event.type == LedgerEvent.Type.DEATH) deaths++;
        if (event.type == LedgerEvent.Type.CLEAR || event.type == LedgerEvent.Type.CLEAR_LOOT) fullClearLoot += event.gp;
        if (event.type == LedgerEvent.Type.CASH_OUT || event.type == LedgerEvent.Type.CASH_OUT_LOOT) cashOutLoot += event.gp;
        if (event.type == LedgerEvent.Type.LOOT) unassignedLoot += event.gp;
        if (isQuiverTurnIn(event)) quiverTurnIns += event.gp;
        if (event.gp >= 0) totalCredits += event.gp;
        else
        {
            long expense = -event.gp;
            costs += expense;
            if (event.type == LedgerEvent.Type.DEATH || event.type == LedgerEvent.Type.DEATH_FEE)
            {
                deathCosts += expense;
            }
            else if (event.type == LedgerEvent.Type.SUPPLIES)
            {
                supplyCosts += expense;
            }
        }
    }

    /** Handles older quiver records whose type was absent in v1.3.3. */
    static boolean isQuiverTurnIn(LedgerEvent event)
    {
        return event.type == LedgerEvent.Type.QUIVER_TURN_IN
            || (event.note != null && event.note.startsWith("Dizana's quiver exchanged for 4,000 Sunfire splinters"));
    }

    static DailySummary allTime(List<LedgerEvent> entries, String account)
    {
        DailySummary total = new DailySummary();
        for (LedgerEvent entry : entries)
        {
            if (account.equals(entry.account)) total.accept(entry);
        }
        return total;
    }

    static Map<LocalDate, DailySummary> byDay(List<LedgerEvent> entries, String account, ZoneId zone)
    {
        Map<LocalDate, DailySummary> daily = new TreeMap<>((a, b) -> b.compareTo(a));
        for (LedgerEvent entry : entries)
        {
            if (!account.equals(entry.account)) continue;
            daily.computeIfAbsent(entry.date(zone), ignored -> new DailySummary()).accept(entry);
        }
        return daily;
    }
}
