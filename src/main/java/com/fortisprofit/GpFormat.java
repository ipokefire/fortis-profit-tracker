package com.fortisprofit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Abbreviated presentation only. The ledger and CSV always use exact integer GP. */
final class GpFormat
{
    private GpFormat() {}

    static String shortGp(long amount)
    {
        BigDecimal absolute = BigDecimal.valueOf(amount).abs();
        String prefix = amount < 0 ? "-" : "";
        if (absolute.compareTo(BigDecimal.valueOf(999_995_000L)) >= 0)
        {
            return prefix + abbreviated(absolute, 1_000_000_000L, 2) + "B";
        }
        if (absolute.compareTo(BigDecimal.valueOf(999_950L)) >= 0)
        {
            return prefix + abbreviated(absolute, 1_000_000L, 2) + "M";
        }
        if (absolute.compareTo(BigDecimal.valueOf(1_000L)) >= 0)
        {
            return prefix + abbreviated(absolute, 1_000L, 1) + "K";
        }
        return Long.toString(amount);
    }

    private static String abbreviated(BigDecimal amount, long divisor, int places)
    {
        return amount.divide(BigDecimal.valueOf(divisor), places, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    static String exactGp(long amount)
    {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount) + " GP";
    }
}
