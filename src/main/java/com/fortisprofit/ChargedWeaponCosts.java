package com.fortisprofit;

/** Charge costs per attack/cast, based on current OSRS recipes. */
final class ChargedWeaponCosts
{
    private ChargedWeaponCosts() { }

    static long scythe(long bloodRuneGp, long vialOfBloodGp)
    {
        return 2L * bloodRuneGp + Math.round(vialOfBloodGp / 100.0);
    }

    static long venatorBow(long ancientEssenceGp)
    {
        return Math.max(0L, ancientEssenceGp);
    }

    static long shadow(long soulRuneGp, long chaosRuneGp)
    {
        return 2L * soulRuneGp + 5L * chaosRuneGp;
    }
}
