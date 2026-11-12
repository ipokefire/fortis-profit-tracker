package com.fortisprofit;

import com.google.gson.Gson;
import java.io.StringReader;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedgerReloadTest
{
    private final Gson gson = new Gson();

    @Test public void acceptsEmptyLedger()
    {
        assertTrue(LedgerStore.parseEntries(gson, new StringReader("[]")).isEmpty());
    }

    @Test public void loadsValidEntry()
    {
        String json = "[{\"account\":\"Onward\",\"timestamp\":\"2026-11-10T14:20:00Z\","
            + "\"type\":\"CLEAR\",\"gp\":1000,\"wave\":12}]";
        List<LedgerEvent> entries = LedgerStore.parseEntries(gson, new StringReader(json));
        assertEquals(1, entries.size());
        assertEquals(1000, entries.get(0).gp);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesNullLedger() { LedgerStore.parseEntries(gson, new StringReader("null")); }

    @Test(expected = IllegalArgumentException.class)
    public void refusesMissingTimestamp()
    {
        LedgerStore.parseEntries(gson, new StringReader("[{\"account\":\"Onward\",\"type\":\"CLEAR\"}]"));
    }

    @Test(expected = com.google.gson.JsonSyntaxException.class)
    public void refusesMalformedJson()
    {
        LedgerStore.parseEntries(gson, new StringReader("[{not-json"));
    }

    @Test public void preservesOldQuiverTypeCompatibility()
    {
        String json = "[{\"account\":\"Onward\",\"timestamp\":\"2026-11-10T14:20:00Z\","
            + "\"note\":\"Dizana's quiver exchanged for 4,000 Sunfire splinters\",\"wave\":-1}]";
        assertEquals(LedgerEvent.Type.ADJUSTMENT,
            LedgerStore.parseEntries(gson, new StringReader(json)).get(0).type);
    }
}
