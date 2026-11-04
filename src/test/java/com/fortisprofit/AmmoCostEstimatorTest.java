package com.fortisprofit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AmmoCostEstimatorTest
{
    @Test public void assemblerUsesThreeDartsAndTenScalesOverFifteenShots()
    {
        AmmoCostEstimator estimator = new AmmoCostEstimator();
        int darts = 0, scales = 0;
        for (int i = 0; i < 15; i++)
        {
            int[] used = estimator.shot(0.20);
            darts += used[0]; scales += used[1];
        }
        assertEquals(3, darts);
        assertEquals(10, scales);
    }

    @Test public void noAvasUsesOneDartPerShot()
    {
        AmmoCostEstimator estimator = new AmmoCostEstimator();
        assertEquals(1, estimator.shot(1.0)[0]);
    }
}
