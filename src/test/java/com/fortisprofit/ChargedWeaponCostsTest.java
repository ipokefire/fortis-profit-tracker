package com.fortisprofit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ChargedWeaponCostsTest
{
    @Test public void scytheUsesTwoBloodRunesAndOneHundredthVial()
    {
        assertEquals(700L, ChargedWeaponCosts.scythe(300L, 10_000L));
    }

    @Test public void venatorBowUsesOneAncientEssencePerShot()
    {
        assertEquals(18L, ChargedWeaponCosts.venatorBow(18L));
        assertEquals(0L, ChargedWeaponCosts.venatorBow(0L));
    }

    @Test public void shadowUsesTwoSoulFiveChaos()
    {
        assertEquals(1_500L, ChargedWeaponCosts.shadow(500L, 100L));
    }
}
