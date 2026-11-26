package com.fortisprofit;

import com.google.gson.Gson;
import java.io.Reader;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.Filepath;

final class LedgerStore
{
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

    /** Reload without changing the current in-memory ledger unless parsing succeeds. */
    synchronized int load()
    {
        // A brand-new installation may not have a ledger file yet.
        if (!file.isFile()) return events.size();
        return reload();
    }

    synchronized int reload()
    {
        if (!file.isFile())
        {
            throw new IllegalStateException("Ledger file not found: " + file);
        }
        try (Reader reader = file.openBufferedReader())
        {
            List<LedgerEvent> loaded = parseEntries(gson, reader);
            events.clear();
            events.addAll(loaded);
            return events.size();
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Could not read ledger " + file + ". Existing history retained.", ex);
        }
    }

    /** Parse in isolation so errors never erase the current ledger. */
    static List<LedgerEvent> parseEntries(Gson gson, Reader reader)
    {
        LedgerEvent[] found = gson.fromJson(reader, LedgerEvent[].class);
        if (found == null)
        {
            throw new IllegalArgumentException("Expected a JSON array of ledger entries, not null");
        }
        List<LedgerEvent> loaded = new ArrayList<>();
        for (LedgerEvent event : found)
        {
            if (event == null)
            {
                throw new IllegalArgumentException("Ledger contains a null entry");
            }
            // Some v1.3.3 quiver records were serialized without a type.
            if (event.type == null && event.note != null
                && event.note.startsWith("Dizana's quiver exchanged for 4,000 Sunfire splinters"))
            {
                event.type = LedgerEvent.Type.ADJUSTMENT;
            }
            if (event.type == null || event.account == null || event.account.trim().isEmpty()
                || event.timestamp == null || event.wave < -1 || event.wave > 12)
            {
                throw new IllegalArgumentException("Ledger entry has missing or invalid fields");
            }
            try { java.time.Instant.parse(event.timestamp); }
            catch (Exception ex) { throw new IllegalArgumentException("Invalid ledger timestamp", ex); }
            loaded.add(event);
        }
        // Preserve older exit/loot records without rewriting their file on reload.
        consolidateLegacyPairs(loaded);
        return loaded;
    }

    /** Consolidate v0.4 cash-out/clear event and corresponding loot into one outcome row. */
    private static void consolidateLegacyPairs(List<LedgerEvent> entries)
    {
        List<LedgerEvent> remove = new ArrayList<>();
        for (LedgerEvent loot : entries)
        {
            LedgerEvent.Type outcomeType = loot.type == LedgerEvent.Type.CASH_OUT_LOOT
                ? LedgerEvent.Type.CASH_OUT : loot.type == LedgerEvent.Type.CLEAR_LOOT
                ? LedgerEvent.Type.CLEAR : null;
            if (outcomeType == null) continue;
            LedgerEvent match = null;
            long bestDelta = Long.MAX_VALUE;
            for (LedgerEvent outcome : entries)
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
        entries.removeAll(remove);
        // Standalone old loot classified as a successful exit is a complete outcome itself.
        for (LedgerEvent e : entries)
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

    /** Apply an observed bank or Death's Coffer gravestone payment to the latest unpaid death.
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

    /** Modify a saved entry without changing its original timestamp or account. */
    synchronized boolean update(LedgerEvent event, LedgerEvent.Type type, long gp, int wave, String note)
    {
        if (event == null || type == null || !events.contains(event) || wave < -1 || wave > 12)
        {
            return false;
        }
        event.type = type;
        event.gp = gp;
        event.wave = wave;
        event.note = note == null ? "" : note;
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
