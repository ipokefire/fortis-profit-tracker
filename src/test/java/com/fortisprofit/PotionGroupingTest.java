package com.fortisprofit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PotionGroupingTest
{
    @Test public void consolidatesAllPotionDoseNames()
    {
        for (int i = 1; i <= 4; i++)
        {
            assertEquals("super restore(4)", PotionGrouping.fullName("super restore(" + i + ")"));
        }
        assertEquals("SUPER_RESTORE4", PotionGrouping.fieldName("super restore(4)"));
    }
}
