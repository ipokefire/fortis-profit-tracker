package com.fortisprofit;

/** Expected internal blowpipe usage. One-third of shots save a scale;
 * darts are saved according to Ava's equipment. No RNG-based GP fluctuation.
 */
final class AmmoCostEstimator
{
    private double dartFraction;
    private double scaleFraction;

    int[] shot(double dartLossRate)
    {
        dartFraction += Math.max(0, Math.min(1, dartLossRate));
        scaleFraction += 2.0 / 3.0;
        int darts = (int) (dartFraction + 1e-9);
        int scales = (int) (scaleFraction + 1e-9);
        dartFraction -= darts;
        scaleFraction -= scales;
        return new int[]{darts, scales};
    }

    void reset()
    {
        dartFraction = 0;
        scaleFraction = 0;
    }
}
