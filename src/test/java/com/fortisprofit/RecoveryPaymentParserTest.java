package com.fortisprofit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RecoveryPaymentParserTest
{
    @Test public void readsObservedDeathsCofferMessageWithoutUsingRemainingBalance()
    {
        assertEquals(10_000L, RecoveryPaymentParser.parseGp(
            "Death charges you 10,000 x Coins. You have 3,089,869 x Coins left in Death's Coffer."));
    }

    @Test public void continuesToReadBankPayments()
    {
        assertEquals(25_000L, RecoveryPaymentParser.parseGp(
            "Payment has been taken from your bank: 25,000 x Coins"));
    }

    @Test public void ignoresNonPaymentsAndInvalidAmounts()
    {
        assertEquals(0L, RecoveryPaymentParser.parseGp("You have 3,089,869 x Coins left in Death's Coffer."));
        assertEquals(0L, RecoveryPaymentParser.parseGp("Death charges you 0 x Coins."));
        assertEquals(0L, RecoveryPaymentParser.parseGp("Death charges you 99999999999999999999999 x Coins."));
        assertEquals(0L, RecoveryPaymentParser.parseGp("Something else happened."));
    }
}
