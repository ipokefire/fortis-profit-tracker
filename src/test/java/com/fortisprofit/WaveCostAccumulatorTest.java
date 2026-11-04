package com.fortisprofit;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaveCostAccumulatorTest
{
    @Test public void combinesAllWaveExpensesIntoOneAdjEntryOnExit()
    {
        List<String> rows = new ArrayList<>();
        WaveCostAccumulator accumulator = new WaveCostAccumulator((wave, gp, note) -> rows.add(wave + ":" + gp + ":" + note));
        accumulator.begin(3);
        accumulator.add(1200, 2);
        accumulator.changeWave(4);
        assertTrue(rows.isEmpty());
        accumulator.add(500, 1);
        accumulator.finish();
        assertEquals(1, rows.size());
        assertEquals("-1:1700:Automatically tracked supplies (3 units; lobby and arena)", rows.get(0));
    }

    @Test public void neverWritesEmptyAttemptsOrCarriesCostsToNewAttempt()
    {
        List<String> rows = new ArrayList<>();
        WaveCostAccumulator accumulator = new WaveCostAccumulator((wave, gp, note) -> rows.add(wave + ":" + gp));
        accumulator.begin(1);
        accumulator.add(800, 1);
        accumulator.changeWave(2);
        accumulator.changeWave(3);
        accumulator.finish();
        accumulator.begin(1);
        accumulator.finish();
        assertEquals(java.util.Arrays.asList("-1:800"), rows);
    }
}
