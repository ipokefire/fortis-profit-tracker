package com.fortisprofit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("fortisprofit")
public interface FortisProfitConfig extends Config
{
    @ConfigItem(keyName="autoSupplyTracking", name="Track Colosseum supplies", description="Track supported food, potions, inventory runes and equipped ammo during Colosseum attempts. Includes Eye of Ayak (Demon Tears); Scythe, Shadow, Venator Bow and Blowpipe charges supported (estimates).", position=3)
    default boolean autoSupplyTracking() { return true; }

    @ConfigItem(keyName="autoDetect", name="Automatic tracking", description="Observe Colosseum loot, chat and death events", position=2)
    default boolean autoDetect() { return true; }

}
