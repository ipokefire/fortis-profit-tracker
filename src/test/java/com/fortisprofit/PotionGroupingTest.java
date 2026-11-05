package com.fortisprofit;

import org.junit.Test;
import net.runelite.api.ItemID;
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
        assertEquals(ItemID.SUPER_RESTORE4, PotionGrouping.fullDoseId("super restore(4)"));
        assertEquals(ItemID.SARADOMIN_BREW4, PotionGrouping.fullDoseId("saradomin brew(4)"));
        assertEquals(-1, PotionGrouping.fullDoseId("unknown potion(4)"));
    }
}
