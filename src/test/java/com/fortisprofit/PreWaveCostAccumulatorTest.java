package com.fortisprofit;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreWaveCostAccumulatorTest
{
    @Test public void lobbyAndArenaSuppliesBecomeOneAdjEntry()
    {
        List<Integer> waves = new ArrayList<>();
        List<Long> costs = new ArrayList<>();
        WaveCostAccumulator accumulator = new WaveCostAccumulator((wave, cost, note) -> {
            waves.add(wave);
            costs.add(cost);
        });
        accumulator.begin(-1);
        accumulator.add(1250, 1);
        accumulator.changeWave(1);
        accumulator.add(850, 1);
        accumulator.changeWave(2);
        accumulator.add(100, 1);
        assertTrue(waves.isEmpty());
        accumulator.finish();
        assertEquals(java.util.Arrays.asList(-1), waves);
        assertEquals(java.util.Arrays.asList(2200L), costs);
    }

    @Test public void leavingLobbyWithoutAttemptDiscardsUnfinishedCosts()
    {
        List<Integer> waves = new ArrayList<>();
        WaveCostAccumulator accumulator = new WaveCostAccumulator((wave, cost, note) -> waves.add(wave));
        accumulator.begin(-1);
        accumulator.add(300, 1);
        accumulator.reset();
        accumulator.begin(-1);
        accumulator.finish();
        assertTrue(waves.isEmpty());
    }
}
