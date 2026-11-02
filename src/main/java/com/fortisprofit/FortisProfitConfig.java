package com.fortisprofit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("fortisprofit")
public interface FortisProfitConfig extends Config
{
    @ConfigItem(keyName="deathFee", name="Estimated death fee (GP)", description="Legacy estimate (not automatically applied; record the actual fee with Add GP entry)", position=0)
    default int deathFee() { return 500000; }

    @ConfigItem(keyName="suppliesPerAttempt", name="Supplies per manual start (GP)", description="Estimate, applied only when manually starting an attempt", position=1)
    default int suppliesPerAttempt() { return 0; }

    @ConfigItem(keyName="autoDetect", name="Automatic tracking", description="Observe Colosseum loot, chat and death events", position=2)
    default boolean autoDetect() { return true; }

}
