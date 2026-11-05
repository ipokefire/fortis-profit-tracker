package com.fortisprofit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RunePouchDiffTest
{
    @Test public void ordinaryCast() { assertEquals(3, RunePouchDiff.consumed(8, 100, 8, 97)); }
    @Test public void lastRuneConsumed() { assertEquals(1, RunePouchDiff.consumed(8, 1, 0, 0)); }
    @Test public void refillIsNotConsumption() { assertEquals(0, RunePouchDiff.consumed(8, 20, 8, 100)); }
    @Test public void runeSwapIsNotConsumption() { assertEquals(0, RunePouchDiff.consumed(8, 100, 7, 50)); }
    @Test public void bulkWithdrawalNotCounted() { assertEquals(0, RunePouchDiff.consumed(8, 100, 8, 20)); }
    @Test public void typeMapping() { assertEquals(net.runelite.api.gameval.ItemID.BLOODRUNE, WaveSupplyTracker.runePouchItemId(8)); }
}
