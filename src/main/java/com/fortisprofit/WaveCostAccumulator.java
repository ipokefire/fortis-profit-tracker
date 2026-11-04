package com.fortisprofit;

/**
 * Accumulates all detected supplies for one Colosseum attempt, including
 * preparation in the lobby. Persist one Adj. expense when the run finishes.
 */
final class WaveCostAccumulator
{
    interface Sink { void record(int wave, long cost, String note); }
    private final Sink sink;
    private long cost;
    private int units;

    WaveCostAccumulator(Sink sink) { this.sink = sink; }

    void begin(int startWave) { reset(); }

    void add(long gp, int quantity)
    {
        if (gp > 0 && quantity > 0)
        {
            cost += gp;
            units += quantity;
        }
    }

    // Wave changes must not create expense entries or clear accrued lobby costs.
    void changeWave(int nextWave) { }

    void finish()
    {
        if (cost > 0)
        {
            sink.record(-1, cost, "Automatically tracked supplies (" + units + " units; lobby and arena)");
        }
        reset();
    }

    void reset()
    {
        cost = 0;
        units = 0;
    }
}
