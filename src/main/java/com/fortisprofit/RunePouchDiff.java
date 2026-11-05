package com.fortisprofit;

/** Compares rune-pouch slots without mistaking refills or slot swaps for casts. */
final class RunePouchDiff
{
    private RunePouchDiff() { }

    /** A change of rune type is not enough evidence of consumption.
     * The slot's type may become zero when the last rune is used.
     * Restrict one observed decrement to 20 runes to avoid counting withdrawals.
     */
    static int consumed(int oldType, int oldQuantity, int newType, int newQuantity)
    {
        if (oldType <= 0 || oldQuantity <= 0 || newQuantity >= oldQuantity) return 0;
        if (newType != oldType && !(newType == 0 && newQuantity == 0)) return 0;
        int loss = oldQuantity - newQuantity;
        return loss <= 20 ? loss : 0;
    }
}
