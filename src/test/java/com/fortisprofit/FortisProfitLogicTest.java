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

    @Test public void allTimeTotalsIncludeEveryDateButOnlyActiveAccount()
    {
        LedgerEvent clear = new LedgerEvent("test-user", LedgerEvent.Type.CLEAR, 0, "", 12);
        LedgerEvent reward = new LedgerEvent("test-user", LedgerEvent.Type.CLEAR_LOOT, 2_000_000, "", 12);
        LedgerEvent death = new LedgerEvent("test-user", LedgerEvent.Type.DEATH, -25_000, "", 5);
        LedgerEvent cashOut = new LedgerEvent("test-user", LedgerEvent.Type.CASH_OUT, 0, "", 3);
        LedgerEvent cashLoot = new LedgerEvent("test-user", LedgerEvent.Type.CASH_OUT_LOOT, 150_000, "", 3);
        LedgerEvent other = new LedgerEvent("other-user", LedgerEvent.Type.CLEAR_LOOT, 50_000_000, "", 12);
        death.timestamp = "2026-10-30T12:00:00Z";
        clear.timestamp = "2026-11-01T12:00:00Z";
        reward.timestamp = "2026-11-01T12:00:01Z";
        cashOut.timestamp = "2026-10-31T12:00:00Z";
        cashLoot.timestamp = "2026-10-31T12:00:01Z";

        DailySummary all = DailySummary.allTime(
            Arrays.asList(clear, reward, death, cashOut, cashLoot, other), "test-user");
        assertEquals(1, all.clears);
        assertEquals(1, all.cashOuts);
        assertEquals(1, all.deaths);
        assertEquals(2_000_000, all.fullClearLoot);
        assertEquals(150_000, all.cashOutLoot);
        assertEquals(25_000, all.costs);
        assertEquals(2_125_000, all.net());
        assertEquals(0, DailySummary.allTime(Arrays.asList(other), "test-user").net());
    }

    @Test public void quiverTurnInCreditsProfitWithoutIncreasingClearCount()
    {
        LedgerEvent quiver = new LedgerEvent("test-user", LedgerEvent.Type.QUIVER_TURN_IN,
            4_000L * 379, "4,000 Sunfire splinters", -1);
        LedgerEvent supplies = new LedgerEvent("test-user", LedgerEvent.Type.SUPPLIES,
            -40_000, "supplies", -1);
        DailySummary all = DailySummary.allTime(Arrays.asList(quiver, supplies), "test-user");
        assertEquals(0, all.clears);
        assertEquals(0, all.cashOuts);
        assertEquals(1_516_000, all.totalCredits);
        assertEquals(40_000, all.costs);
        assertEquals(1_476_000, all.net());
        assertEquals(-1, quiver.wave);
    }

    @Test public void quiverProceedsReconcileWithClearCashOutAndExpenses()
    {
        LedgerEvent clear = new LedgerEvent("test-user", LedgerEvent.Type.CLEAR, 2_240_000, "", 12);
        LedgerEvent cash = new LedgerEvent("test-user", LedgerEvent.Type.CASH_OUT, 300_000, "", 7);
        LedgerEvent quiver = new LedgerEvent("test-user", LedgerEvent.Type.QUIVER_TURN_IN,
            1_336_000, "Dizana's quiver exchanged for 4,000 Sunfire splinters @ 334 GP each", -1);
        LedgerEvent supplies = new LedgerEvent("test-user", LedgerEvent.Type.SUPPLIES, -150_000, "", -1);
        DailySummary totals = DailySummary.allTime(Arrays.asList(clear, cash, quiver, supplies), "test-user");
        assertEquals(1_336_000, totals.quiverTurnIns);
        assertEquals(3_876_000, totals.totalCredits);
        assertEquals(3_726_000, totals.net());
        assertEquals(totals.totalCredits,
            totals.fullClearLoot + totals.cashOutLoot + totals.quiverTurnIns);
    }

    @Test public void categorizesCostsWithoutChangingNetProfit()
    {
        LedgerEvent clear = new LedgerEvent("user", LedgerEvent.Type.CLEAR, 2_000_000, "", 12);
        LedgerEvent death = new LedgerEvent("user", LedgerEvent.Type.DEATH, -500_000, "", 9);
        LedgerEvent fee = new LedgerEvent("user", LedgerEvent.Type.DEATH_FEE, -25_000, "", 9);
        LedgerEvent supplies = new LedgerEvent("user", LedgerEvent.Type.SUPPLIES, -120_000, "", -1);
        LedgerEvent adjustment = new LedgerEvent("user", LedgerEvent.Type.ADJUSTMENT, -8_000, "", -1);
        DailySummary totals = DailySummary.allTime(
            Arrays.asList(clear, death, fee, supplies, adjustment), "user");
        assertEquals(525_000, totals.deathCosts);
        assertEquals(120_000, totals.supplyCosts);
        assertEquals(8_000, totals.otherCosts());
        assertEquals(653_000, totals.costs);
        assertEquals(1_347_000, totals.net());
        assertEquals(totals.costs, totals.deathCosts + totals.supplyCosts + totals.otherCosts());
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
        assertEquals(0, WaveMessages.confirmedColosseumCompletion("Wave 12 is starting"));
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
