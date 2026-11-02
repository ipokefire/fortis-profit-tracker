package com.fortisprofit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FortisProfitLogicTest
{
    @Test public void abbreviatesGpWithoutChangingLedgerAmount()
    {
        assertEquals("999", GpFormat.shortGp(999));
        assertEquals("1K", GpFormat.shortGp(1000));
        assertEquals("2.5K", GpFormat.shortGp(2500));
        assertEquals("1.25M", GpFormat.shortGp(1_250_000));
        assertEquals("-500K", GpFormat.shortGp(-500_000));
    }

    @Test public void splitsCashOutAndClearProfitByDate()
    {
        LedgerEvent cash = new LedgerEvent("test-user", LedgerEvent.Type.CASH_OUT, 0, "", 1);
        LedgerEvent loot = new LedgerEvent("test-user", LedgerEvent.Type.CASH_OUT_LOOT, 150_000, "", 1);
        LedgerEvent death = new LedgerEvent("test-user", LedgerEvent.Type.DEATH, 0, "", 6);
        LedgerEvent fee = new LedgerEvent("test-user", LedgerEvent.Type.DEATH_FEE, -20_000, "", 6);
        Map<LocalDate, DailySummary> days = DailySummary.byDay(
            Arrays.asList(cash, loot, death, fee), "test-user", ZoneId.systemDefault());
        DailySummary today = days.get(LocalDate.now());
        assertNotNull(today);
        assertEquals(1, today.cashOuts);
        assertEquals(1, today.deaths);
        assertEquals(150_000, today.cashOutLoot);
        assertEquals(130_000, today.net());
    }

    @Test public void recognizesExactCompletedWaveMessageWithoutPriorRunState()
    {
        for (int wave = 1; wave <= 12; wave++)
        {
            assertEquals(wave, WaveMessages.confirmedColosseumCompletion(
                "Wave " + wave + " completed! Wave duration: 01:23"));
        }
        assertEquals(1, WaveMessages.confirmedColosseumCompletion("Wave 1 completed! Wave duration: 00:39"));
        assertEquals(0, WaveMessages.confirmedColosseumCompletion("Wave 1 completed!"));
        assertEquals(0, WaveMessages.confirmedColosseumCompletion("Wave 13 completed! Wave duration: 01:23"));
    }

    @Test public void parsesLiveRuneLiteColoredWaveMessage()
    {
        assertEquals(1, WaveMessages.confirmedColosseumCompletion(
            "Wave 1 completed! Wave duration: @mes_hl_red@0:32"));
        assertEquals(2, WaveMessages.confirmedColosseumCompletion(
            "Wave 2 completed! Wave duration: @mes_hl_red@1:13@mes_hl_end@"));
        assertEquals(12, WaveMessages.confirmedColosseumCompletion(
            "@red@Wave 12 completed! Wave duration: @mes_hl_red@12:01"));
    }

    @Test public void parsesWaveMessagesConservatively()
    {
        assertEquals(1, WaveMessages.wave("Fortis Colosseum: Wave 1"));
        assertEquals(12, WaveMessages.wave("Wave 12/12 completed"));
        assertEquals(0, WaveMessages.wave("No waves here"));
        assertTrue(WaveMessages.completed("You have completed wave 1!"));
        assertFalse(WaveMessages.completed("Wave 6 is starting"));
    }
}
