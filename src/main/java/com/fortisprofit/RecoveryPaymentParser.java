package com.fortisprofit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse observed OSRS gravestone payments from either bank GP or Death's Coffer.
 * Returns the charged amount, not the remaining coffer balance. */
final class RecoveryPaymentParser
{
    private static final Pattern PAYMENT = Pattern.compile(
        "\\b(?:payment has been taken from your bank:|death charges you)\\s*([0-9][0-9,]*)\\s*x\\s*coins\\b",
        Pattern.CASE_INSENSITIVE);

    private RecoveryPaymentParser() { }

    static long parseGp(String message)
    {
        if (message == null) return 0;
        Matcher matcher = PAYMENT.matcher(message);
        if (!matcher.find()) return 0;
        try
        {
            long amount = Long.parseLong(matcher.group(1).replace(",", ""));
            return amount > 0 ? amount : 0;
        }
        catch (NumberFormatException ex)
        {
            return 0;
        }
    }
}
