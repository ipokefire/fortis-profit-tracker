package com.fortisprofit;

import net.runelite.api.ItemID;
import java.lang.reflect.Field;
import java.util.Locale;

/** Canonical label/item key for 1-4-dose potions in the Current Run grid. */
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

    static int fullDoseId(String fullName)
    {
        try
        {
            Field field = ItemID.class.getField(fieldName(fullName));
            return field.getInt(null);
        }
        catch (ReflectiveOperationException exception)
        {
            return -1;
        }
    }
}
