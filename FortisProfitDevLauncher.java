package com.fortisprofit;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Run locally from IntelliJ or with ./gradlew run. Not a JUnit test. */
public class FortisProfitDevLauncher
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(FortisProfitPlugin.class);
        RuneLite.main(args);
    }
}
