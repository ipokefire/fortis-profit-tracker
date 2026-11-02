package com.fortisprofit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.Filepath;

final class LedgerStore
{
    private static final Type EVENTS_TYPE = new TypeToken<List<LedgerEvent>>() {}.getType();
    private final Gson gson;
    private final Filepath directory;
    private final Filepath file;
    private final List<LedgerEvent> events = new ArrayList<>();

    LedgerStore(Gson gson, Filepath directory)
    {
        this.gson = gson;
        this.directory = directory;
        this.file = directory.joinSegment("ledger.json");
    }

    synchronized void load()
    {
        events.clear();
        if (!file.isFile()) return;
        try
        {
            List<LedgerEvent> found;
            try (Reader reader = file.openBufferedReader())
            {
                found = gson.fromJson(reader, EVENTS_TYPE);
            }
            if (found != null)
            {
                for (LedgerEvent event : found)
                {
                    if (event == null) continue;
                    // Some v1.3.3 quiver records were serialized without a type.
                    // Use the existing ADJUSTMENT type for durable compatibility.
                    if (event.type == null && event.note != null
                        && event.note.startsWith("Dizana's quiver exchanged for 4,000 Sunfire splinters"))
                    {
                        event.type = LedgerEvent.Type.ADJUSTMENT;
                    }
                    events.add(event);
                }
            }
            // Merge legacy two-row exit+loot pairs in memory, without rewriting on load.
            consolidateLegacyPairs();
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Could not read Fortis ledger " + file + ". File left unchanged.", ex);
        }
    }

    /** Consolidate v0.4 cash-out/clear event and corresponding loot into one outcome row. */
    private void consolidateLegacyPairs()
    {
        List<LedgerEvent> remove = new ArrayList<>();
        for (LedgerEvent loot : events)
        {
            LedgerEvent.Type outcomeType = loot.type == LedgerEvent.Type.CASH_OUT_LOOT
                ? LedgerEvent.Type.CASH_OUT : loot.type == LedgerEvent.Type.CLEAR_LOOT
                ? LedgerEvent.Type.CLEAR : null;
            if (outcomeType == null) continue;
            LedgerEvent match = null;
            long bestDelta = Long.MAX_VALUE;
            for (LedgerEvent outcome : events)
            {
                if (outcome.type != outcomeType || !java.util.Objects.equals(outcome.account, loot.account)
                    || outcome.gp != 0 || remove.contains(outcome)) continue;
                try
                {
                    long delta = Math.abs(java.time.Instant.parse(outcome.timestamp).toEpochMilli()
                        - java.time.Instant.parse(loot.timestamp).toEpochMilli());
                    if (delta <= 300_000 && delta < bestDelta) { match = outcome; bestDelta = delta; }
                }
                catch (Exception ignored) { }
            }
            if (match != null)
            {
                match.gp += loot.gp;
                if (match.wave == 0) match.wave = loot.wave;
                match.note = "Consolidated exit and claimed loot (legacy): " + loot.note;
                remove.add(loot);
            }
        }
        events.removeAll(remove);
        // Standalone old loot classified as a successful exit is a complete outcome itself.
        for (LedgerEvent e : events)
        {
            if (e.type == LedgerEvent.Type.CASH_OUT_LOOT) e.type = LedgerEvent.Type.CASH_OUT;
            if (e.type == LedgerEvent.Type.CLEAR_LOOT) e.type = LedgerEvent.Type.CLEAR;
        }
    }

    /** Add GP to an existing exit, avoiding a second ledger row. */
    synchronized boolean creditRecentExit(LedgerEvent.Type type, String account, long gp, int wave, String note)
    {
        long now = System.currentTimeMillis();
        for (int i = events.size() - 1; i >= 0; i--)
        {
            LedgerEvent entry = events.get(i);
            if (entry.type != type || !java.util.Objects.equals(account, entry.account)) continue;
            try
            {
                if (now - java.time.Instant.parse(entry.timestamp).toEpochMilli() > 300_000) continue;
            }
            catch (Exception ignored) { continue; }
            entry.gp += gp;
            if (entry.wave == 0) entry.wave = wave;
            entry.note = note;
            save();
            return true;
        }
        return false;
    }

    /** Apply an actual gravestone bank payment to the latest unpaid death.
     * Reject stale/ambiguous payments rather than changing unrelated P&L.
     * Older v1.1 DEATH rows are eligible as long as they're still at zero.
     */
    synchronized boolean applyRecoveryFee(String account, long gpPaid, long maxAgeMs)
    {
        if (gpPaid <= 0) return false;
        long now = System.currentTimeMillis();
        for (int i = events.size() - 1; i >= 0; i--)
        {
            LedgerEvent event = events.get(i);
            if (event.type != LedgerEvent.Type.DEATH || event.gp != 0
                || !java.util.Objects.equals(event.account, account)
                || (event.note != null && event.note.contains("[recovery fee recorded]"))) continue;
            long elapsed;
            try { elapsed = now - java.time.Instant.parse(event.timestamp).toEpochMilli(); }
            catch (Exception ex) { continue; }
            if (elapsed < 0 || elapsed > maxAgeMs) continue;
            event.gp = -gpPaid;
            event.note = (event.note == null ? "" : event.note) + " [recovery fee recorded]";
            save();
            return true;
        }
        return false;
    }

    synchronized List<LedgerEvent> list() { return new ArrayList<>(events); }
    synchronized void add(LedgerEvent event) { events.add(event); save(); }
    synchronized void delete(LedgerEvent event) { if (events.remove(event)) save(); }

    /** Convert one old unassigned loot row into a single cash-out outcome. */
    synchronized boolean classifyClaimAsCashOut(LedgerEvent event)
    {
        if (event.type != LedgerEvent.Type.LOOT || !events.contains(event)) return false;
        event.type = LedgerEvent.Type.CASH_OUT;
        event.note = "Early cash-out; manually classified from earlier loot";
        save();
        return true;
    }

    /** Correct a mistakenly inferred wave-12 cash-out without changing its GP. */
    synchronized boolean classifyAsClear(LedgerEvent event)
    {
        if (event.type != LedgerEvent.Type.CASH_OUT || event.wave != 12 || !events.contains(event)) return false;
        event.type = LedgerEvent.Type.CLEAR;
        event.note = (event.note == null ? "" : event.note) + " [manually corrected to Sol clear]";
        save();
        return true;
    }

    synchronized boolean setWave(LedgerEvent event, int wave)
    {
        if (wave < -1 || wave > 12 || !events.contains(event)) return false;
        event.wave = wave;
        // Keep the paired cash-out and cash-out loot (or death and fee) in sync
        // only if they are from the same account and within two seconds.
        if (event.type == LedgerEvent.Type.CASH_OUT || event.type == LedgerEvent.Type.CASH_OUT_LOOT
            || event.type == LedgerEvent.Type.DEATH || event.type == LedgerEvent.Type.DEATH_FEE
            || event.type == LedgerEvent.Type.CLEAR || event.type == LedgerEvent.Type.CLEAR_LOOT)
        {
            LedgerEvent.Type related = event.type == LedgerEvent.Type.CASH_OUT ? LedgerEvent.Type.CASH_OUT_LOOT
                : event.type == LedgerEvent.Type.CASH_OUT_LOOT ? LedgerEvent.Type.CASH_OUT
                : event.type == LedgerEvent.Type.DEATH ? LedgerEvent.Type.DEATH_FEE
                : event.type == LedgerEvent.Type.DEATH_FEE ? LedgerEvent.Type.DEATH
                : event.type == LedgerEvent.Type.CLEAR ? LedgerEvent.Type.CLEAR_LOOT
                : LedgerEvent.Type.CLEAR;
            long when = java.time.Instant.parse(event.timestamp).toEpochMilli();
            for (LedgerEvent candidate : events)
            {
                if (candidate != event && candidate.type == related && candidate.account.equals(event.account)
                    && Math.abs(java.time.Instant.parse(candidate.timestamp).toEpochMilli() - when) <= 2_000)
                {
                    candidate.wave = wave;
                }
            }
        }
        save();
        return true;
    }

    private void save()
    {
        try
        {
            directory.createDirectories();
            Filepath temp = directory.joinSegment("ledger.json.tmp");
            temp.write(gson.toJson(events));
            try
            {
                temp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException ex)
            {
                temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException ex) { throw new IllegalStateException("Could not save Fortis ledger: " + file, ex); }
    }
}
