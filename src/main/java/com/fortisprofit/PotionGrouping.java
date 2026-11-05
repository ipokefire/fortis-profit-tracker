package com.fortisprofit;

import java.util.Locale;
import net.runelite.api.ItemID;

/** Canonical label/item key for 1-4-dose potions in the Current Run grid.
 * Uses a reviewable, explicit mapping rather than reflection on ItemID fields.
 */
final class PotionGrouping
{
    private PotionGrouping() { }

    static String fullName(String doseName)
    {
        return doseName.replaceFirst("\\([1-4]\\)$", "(4)");
    }

    static String fieldName(String fullName)
    {
        return fullName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
            .replaceFirst("_4_$", "4").replaceFirst("_4$", "4");
    }

    /** Explicitly known 4-dose variants. Unknowns return -1 and retain
     * WaveSupplyTracker's existing dose-price fallback behavior.
     */
    static int fullDoseId(String fullName)
    {
        switch (fullName.toLowerCase(Locale.ROOT))
        {
            case "saradomin brew(4)": return ItemID.SARADOMIN_BREW4;
            case "super restore(4)": return ItemID.SUPER_RESTORE4;
            case "prayer potion(4)": return ItemID.PRAYER_POTION4;
            case "ranging potion(4)": return ItemID.RANGING_POTION4;
            case "magic potion(4)": return ItemID.MAGIC_POTION4;
            case "stamina potion(4)": return ItemID.STAMINA_POTION4;
            case "super combat potion(4)": return ItemID.SUPER_COMBAT_POTION4;
            case "bastion potion(4)": return ItemID.BASTION_POTION4;
            case "battlemage potion(4)": return ItemID.BATTLEMAGE_POTION4;
            case "divine ranging potion(4)": return ItemID.DIVINE_RANGING_POTION4;
            case "divine magic potion(4)": return ItemID.DIVINE_MAGIC_POTION4;
            case "divine super combat potion(4)": return ItemID.DIVINE_SUPER_COMBAT_POTION4;
            case "super attack(4)": return ItemID.SUPER_ATTACK4;
            case "super strength(4)": return ItemID.SUPER_STRENGTH4;
            case "super defence(4)": return ItemID.SUPER_DEFENCE4;
            case "attack potion(4)": return ItemID.ATTACK_POTION4;
            case "strength potion(4)": return ItemID.STRENGTH_POTION4;
            case "defence potion(4)": return ItemID.DEFENCE_POTION4;
            case "restore potion(4)": return ItemID.RESTORE_POTION4;
            case "sanfew serum(4)": return ItemID.SANFEW_SERUM4;
            case "super energy(4)": return ItemID.SUPER_ENERGY4;
            case "energy potion(4)": return ItemID.ENERGY_POTION4;
            case "antifire potion(4)": return ItemID.ANTIFIRE_POTION4;
            case "extended antifire(4)": return ItemID.EXTENDED_ANTIFIRE4;
            case "super antifire potion(4)": return ItemID.SUPER_ANTIFIRE_POTION4;
            case "extended super antifire(4)": return ItemID.EXTENDED_SUPER_ANTIFIRE4;
            default: return -1;
        }
    }
}
