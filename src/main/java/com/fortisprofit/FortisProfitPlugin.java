package com.fortisprofit;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@PluginDescriptor(internalName="fortis-profit-tracker", name="Colosseum Profit Tracker", description="Daily Fortis Colosseum P&L with wave history", tags={"colosseum", "profit", "loot"})
public class FortisProfitPlugin extends Plugin
{
    private static final Logger DIAGNOSTICS = Logger.getLogger(FortisProfitPlugin.class.getName());
    // Observed OSRS chat: "Payment has been taken from your bank: 10,000 x Coins"
    private static final Pattern RECOVERY_PAYMENT = Pattern.compile(
        "\\bpayment has been taken from your bank:\\s*([0-9][0-9,]*)\\s*x\\s*coins\\b",
        Pattern.CASE_INSENSITIVE);
    private static final long RECOVERY_WINDOW_MS = 30 * 60_000L;
    @Inject private Client client;
    @Inject private ClientToolbar toolbar;
    @Inject private Gson gson;
    @Inject private FortisProfitConfig config;
    @Inject private ItemManager itemManager;

    private LedgerStore ledger;
    private FortisProfitPanel panel;
    private NavigationButton navigation;
    private boolean active;
    private boolean terminal;
    private boolean exitHasLoot;
    private int activeWave;       // Wave currently being fought (death uses this).
    private int completedWave;    // Most recently completed wave (cash-out uses this).
    private int lastExitWave;     // Carry across lobby and chest opening.
    private int lastConfirmedWave; // Retained through exit/chest opening.
    private long lastConfirmedWaveMs;
    private long lastDeathMs;
    private String lastRecoveryPaymentFingerprint;
    private long lastRecoveryPaymentMs;
    private long lastColosseumSignalMs;
    private long lastClearMs;
    private LedgerEvent.Type lastExitType;
    private long lastExitMs;
    private long lastLootMs;
    private String lastLootFingerprint;
    private String lastAccount;

    @Provides FortisProfitConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(FortisProfitConfig.class);
    }

    @Override protected void startUp() throws IOException
    {
        ledger = new LedgerStore(gson, getPluginDirectory());
        ledger.load();
        panel = new FortisProfitPanel(this);
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(198, 149, 59));
        graphics.fillRoundRect(1, 1, 14, 14, 4, 4);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 12));
        graphics.drawString("F", 4, 12);
        graphics.dispose();
        navigation = NavigationButton.builder().tooltip("Colosseum Profit Tracker")
            .icon(image).priority(6).panel(panel).build();
        toolbar.addNavigation(navigation);
        refresh();
    }

    @Override protected void shutDown()
    {
        if (navigation != null) toolbar.removeNavigation(navigation);
        if (panel != null) panel.stopUpdates();
    }

    String account()
    {
        return client.getLocalPlayer() == null ? "Unknown" : client.getLocalPlayer().getName();
    }

    private void checkAccount()
    {
        String current = account();
        // Client.getLocalPlayer() can briefly be null during transitions; do not
        // discard an already confirmed wave simply because the name is unavailable.
        if ("Unknown".equals(current)) return;
        if (lastAccount == null || ( !"Unknown".equals(lastAccount) && !lastAccount.equals(current)))
        {
            active = false;
            terminal = false;
            exitHasLoot = false;
            activeWave = 0;
            completedWave = 0;
            lastExitWave = 0;
            lastConfirmedWave = 0;
            lastConfirmedWaveMs = 0;
            lastExitType = null;
            lastAccount = current;
        }
    }

    synchronized void record(LedgerEvent.Type type, long gp, String note, int wave)
    {
        ledger.add(new LedgerEvent(account(), type, gp, note, wave));
        refresh();
    }

    void record(LedgerEvent.Type type, long gp, String note)
    {
        record(type, gp, note, 0);
    }

    void delete(LedgerEvent event)
    {
        ledger.delete(event);
        refresh();
    }

    boolean classifyAsCashOut(LedgerEvent event)
    {
        boolean changed = ledger.classifyClaimAsCashOut(event);
        if (changed) refresh();
        return changed;
    }

    boolean classifyAsClear(LedgerEvent event)
    {
        boolean changed = ledger.classifyAsClear(event);
        if (changed) refresh();
        return changed;
    }

    boolean changeWave(LedgerEvent event, int wave)
    {
        boolean changed = ledger.setWave(event, wave);
        if (changed) refresh();
        return changed;
    }

    int activeWave() { return activeWave; }
    void setActiveWave(int wave)
    {
        if (wave < 1 || wave > 12) return;
        checkAccount();
        if (terminal)
        {
            lastExitType = null;
            lastExitWave = 0;
            completedWave = 0;
            exitHasLoot = false;
        }
        activeWave = wave;
        // This is the current fight, not evidence that the wave is completed.
        if (wave > 1) completedWave = Math.max(completedWave, wave - 1);
        active = true;
        terminal = false;
        refresh();
    }

    java.util.List<LedgerEvent> entries() { return ledger.list(); }

    private void refresh()
    {
        if (panel != null) SwingUtilities.invokeLater(panel::refresh);
    }

    void manualStart()
    {
        checkAccount();
        active = true;
        terminal = false;
        lastColosseumSignalMs = System.currentTimeMillis();
        activeWave = 1;
        completedWave = 0;
        lastExitWave = 0;
        lastConfirmedWave = 0;
        lastConfirmedWaveMs = 0;
        lastExitType = null;
        exitHasLoot = false;
        if (config.suppliesPerAttempt() > 0)
        {
            record(LedgerEvent.Type.SUPPLIES, -config.suppliesPerAttempt(), "Estimated supplies (manual start)", 1);
        }
        refresh();
    }

    void manualClear() { checkAccount(); if (!terminal) finishClear("Manual clear"); }
    void manualDeath() { checkAccount(); if (!terminal) finishDeath("Manual death"); }
    void manualCashOut() { checkAccount(); if (!terminal) finishCashOut("Manual early cash-out", cashOutWave()); }

    private int cashOutWave()
    {
        // Claimed chest loot establishes that the latest observed combat wave
        // was finished; a run cannot cash out in the middle of combat.
        long elapsed = System.currentTimeMillis() - lastConfirmedWaveMs;
        int recentConfirmed = lastConfirmedWaveMs > 0 && elapsed >= 0 && elapsed <= 10 * 60_000 ? lastConfirmedWave : 0;
        return Math.max(recentConfirmed, Math.max(completedWave, activeWave));
    }

    private void finishCashOut(String source, int wave)
    {
        record(LedgerEvent.Type.CASH_OUT, 0, source, wave);
        lastExitType = LedgerEvent.Type.CASH_OUT;
        lastExitMs = System.currentTimeMillis();
        lastExitWave = wave;
        exitHasLoot = false;
        terminal = true;
        active = false;
    }

    private void finishClear(String source)
    {
        record(LedgerEvent.Type.CLEAR, 0, source, 12);
        terminal = true;
        active = false;
        completedWave = 12;
        lastClearMs = System.currentTimeMillis();
        lastExitType = LedgerEvent.Type.CLEAR;
        lastExitMs = lastClearMs;
        lastExitWave = 12;
        exitHasLoot = false;
    }

    private void finishDeath(String source)
    {
        int wave = activeWave;
        record(LedgerEvent.Type.DEATH, 0, source, wave);
        // Recovery fee stays zero until a real payment is observed.
        // A detected gravestone payment updates THIS death row, not a second fee row.
        terminal = true;
        active = false;
        lastDeathMs = System.currentTimeMillis();
        lastExitType = LedgerEvent.Type.DEATH;
        lastExitMs = lastDeathMs;
        lastExitWave = wave;
        exitHasLoot = false;
    }

    @Subscribe public void onChatMessage(ChatMessage event)
    {
        if (!config.autoDetect()) return;
        // A completion can be delivered under ENGINE, CONSOLE or other game-message
        // categories. Parse the distinctive exact message before filtering types.
        String message = WaveMessages.normalize(Text.removeTags(event.getMessage()));
        int confirmedCompletion = WaveMessages.confirmedColosseumCompletion(message);
        if (message.contains("wave") && (message.contains("completed") || message.contains("duration")))
        {
            DIAGNOSTICS.info("Fortis wave message: type=" + event.getType()
                + " parsed=" + confirmedCompletion + " text=" + message);
        }
        if (confirmedCompletion == 0
            && event.getType() != ChatMessageType.GAMEMESSAGE
            && event.getType() != ChatMessageType.SPAM
            && event.getType() != ChatMessageType.ENGINE
            && event.getType() != ChatMessageType.CONSOLE) return;
        checkAccount();
        // Bank deduction can occur after exiting the arena; the death event
        // remains pending until its actual recovery payment is observed.
        Matcher recoveryPayment = RECOVERY_PAYMENT.matcher(message);
        if (recoveryPayment.find())
        {
            try
            {
                long gpPaid = Long.parseLong(recoveryPayment.group(1).replace(",", ""));
                long now = System.currentTimeMillis();
                String fingerprint = message + "|" + account();
                if (gpPaid > 0 && !(fingerprint.equals(lastRecoveryPaymentFingerprint)
                    && now - lastRecoveryPaymentMs < 5_000))
                {
                    boolean applied = ledger.applyRecoveryFee(account(), gpPaid, RECOVERY_WINDOW_MS);
                    DIAGNOSTICS.info("Fortis grave payment: gp=" + gpPaid + " matchedDeath=" + applied);
                    if (applied)
                    {
                        lastRecoveryPaymentFingerprint = fingerprint;
                        lastRecoveryPaymentMs = now;
                        refresh();
                    }
                }
            }
            catch (NumberFormatException ex)
            {
                DIAGNOSTICS.warning("Invalid gravestone payment amount: " + recoveryPayment.group(1));
            }
            return;
        }
        if (message.contains("colosseum") || confirmedCompletion > 0)
        {
            lastColosseumSignalMs = System.currentTimeMillis();
        }
        // Player deaths aren't guaranteed to fire ActorDeath in every
        // client transition. Use the death chat as a secondary signal, but
        // only when a Colosseum run was recently observed.
        if ((message.contains("oh dear, you are dead") || message.contains("you have died"))
            && !terminal && System.currentTimeMillis() - lastDeathMs > 15_000
            && (active || (lastColosseumSignalMs > 0
                && System.currentTimeMillis() - lastColosseumSignalMs < 20 * 60_000)))
        {
            finishDeath("Detected death message during Colosseum attempt");
            return;
        }
        if (confirmedCompletion > 0)
        {
            // The precise post-wave message is sufficient to establish a run,
            // even when the plugin did not catch the initial wave-start message.
            // completedWave is used for cash-outs; activeWave remains the last
            // observed fight until the next wave explicitly begins.
            if (!active || terminal || (confirmedCompletion == 1 && completedWave > 1))
            {
                active = true;
                terminal = false;
                activeWave = 0;
                completedWave = 0;
                lastExitType = null;
                lastExitWave = 0;
                exitHasLoot = false;
            }
            lastConfirmedWave = confirmedCompletion;
            lastConfirmedWaveMs = System.currentTimeMillis();
            lastColosseumSignalMs = lastConfirmedWaveMs;
            DIAGNOSTICS.info("Fortis confirmed completed wave " + confirmedCompletion
                + " for " + account());
            active = true;
            activeWave = Math.max(activeWave, confirmedCompletion);
            completedWave = Math.max(completedWave, confirmedCompletion);
            // Wave 12 completed is direct proof of defeating Sol Heredit.
            // Recording this before the chest is claimed avoids a false cash-out.
            if (confirmedCompletion == 12 && !terminal)
            {
                finishClear("Confirmed wave 12 completion (Sol Heredit)");
            }
            refresh();
            return;
        }
        int wave = WaveMessages.wave(message);
        // Only trust wave numbers when the message explicitly mentions the Colosseum,
        // or when an existing run is active. Avoid counting other minigames' waves.
        if (wave > 0 && (message.contains("colosseum") || active || (message.startsWith("wave: 1") && !terminal)))
        {
            if (wave == 1 && (activeWave == 0 || activeWave > 1 || terminal))
            {
                active = true;
                terminal = false;
                completedWave = 0;
                lastExitType = null;
                exitHasLoot = false;
            }
            if (wave > activeWave && activeWave > 0 && active)
            {
                // Reaching wave N shows the preceding wave was survived.
                completedWave = Math.max(completedWave, wave - 1);
            }
            activeWave = wave;
            lastColosseumSignalMs = System.currentTimeMillis();
            active = true;
            if (WaveMessages.completed(message))
            {
                completedWave = Math.max(completedWave, wave);
            }
            refresh();
        }
        boolean fullClearMessage = (message.contains("sol heredit") && (message.contains("defeated") || message.contains("vanquished")))
            || message.contains("completed the fortis colosseum")
            || message.contains("conquered the colosseum");
        if (fullClearMessage && !terminal && System.currentTimeMillis() - lastClearMs > 15_000)
        {
            finishClear("Detected Colosseum completion message");
        }
    }

    @Subscribe public void onActorDeath(ActorDeath event)
    {
        checkAccount();
        if (!config.autoDetect() || terminal || client.getLocalPlayer() == null) return;
        if (!active && !(lastColosseumSignalMs > 0
            && System.currentTimeMillis() - lastColosseumSignalMs < 20 * 60_000)) return;
        if (event.getActor() != client.getLocalPlayer()) return;
        if (System.currentTimeMillis() - lastDeathMs > 15_000)
        {
            finishDeath("Detected player death during active attempt");
        }
    }

    @Subscribe public void onLootReceived(LootReceived event)
    {
        if (!config.autoDetect()) return;
        checkAccount();
        String source = event.getName();
        if (source == null || !source.toLowerCase(Locale.ROOT).contains("colosseum")) return;
        long value = 0;
        StringBuilder fingerprint = new StringBuilder(source).append('|');
        for (ItemStack item : event.getItems())
        {
            if (item.getQuantity() > 0)
            {
                value += (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
                fingerprint.append(item.getId()).append(':').append(item.getQuantity()).append(';');
            }
        }
        if (value <= 0) return;
        long now = System.currentTimeMillis();
        String key = fingerprint.toString();
        if (key.equals(lastLootFingerprint) && now - lastLootMs < 5_000) return;

        LedgerEvent.Type outcome;
        int wave;
        boolean recentExit = lastExitType != null && now - lastExitMs < 300_000;
        if (recentExit && (lastExitType == LedgerEvent.Type.CLEAR || lastExitType == LedgerEvent.Type.CASH_OUT))
        {
            outcome = lastExitType;
            wave = Math.max(lastExitWave, cashOutWave());
        }
        else
        {
            // Only a completed wave 12 proves a Sol kill. Merely entering
            // wave 12 must not be classified as a clear.
            boolean solCompleted = completedWave >= 12
                || (lastConfirmedWave == 12 && lastConfirmedWaveMs > 0
                    && now - lastConfirmedWaveMs >= 0 && now - lastConfirmedWaveMs < 10 * 60_000);
            wave = solCompleted ? 12 : cashOutWave();
            if (solCompleted)
            {
                outcome = LedgerEvent.Type.CLEAR;
                finishClear("Confirmed wave 12 before Colosseum loot");
            }
            else
            {
                outcome = LedgerEvent.Type.CASH_OUT;
                finishCashOut("Inferred early cash-out from Colosseum loot", wave);
            }
        }
        DIAGNOSTICS.info("Fortis chest loot: source=" + source + " gp=" + value
            + " wave=" + wave + " confirmed=" + lastConfirmedWave
            + " completed=" + completedWave + " active=" + activeWave
            + " account=" + account());
        String note = "Claimed Colosseum loot (GE estimate; " + source + ")";
        if (!ledger.creditRecentExit(outcome, account(), value, wave, note))
        {
            record(outcome, value, note, wave);
        }
        refresh();
        exitHasLoot = true;
        lastLootFingerprint = key;
        lastLootMs = now;
    }
}
