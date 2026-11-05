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
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.ItemID;
import net.runelite.api.Item;
import net.runelite.api.Projectile;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import java.util.function.Consumer;
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
    @Inject private ClientThread clientThread;
    @Inject private SpriteManager spriteManager;

    private LedgerStore ledger;
    private WaveSupplyTracker supplies;
    // Minimus (NPC 12807) is in the Colosseum lobby.
    // Arena tracking is additionally gated by an active Colosseum run.
    private static final int COLOSSEUM_LOBBY_MINIMUS_ID = 12807;
    private boolean inColosseumLobby;
    // Ayak normal cast animation from SuppliesTrackerExternal.
    // Animation IDs and equipped-weapon verification adapted from
    // SuppliesTrackerExternal (BSD-2-Clause; THIRD_PARTY_NOTICES.md).
    private static final int SCYTHE_ATTACK_ANIMATION = 8056;
    private static final int SHADOW_CAST_ANIMATION = 9493;
    private static final int AYAK_CAST_ANIMATION = 12397;
    private static final int AYAK_SPECIAL_ANIMATION = 12394;
    private static final int BLOWPIPE_ATTACK_ANIMATION = 5061;
    // SuppliesTrackerExternal Venator Bow attack animation.
    private static final int VENATOR_BOW_ATTACK_ANIMATION = 9858;
    private boolean blowpipeWasFrameZero;
    private Projectile lastBlowpipeProjectile;
    private boolean ayakNormalWasFrameZero;
    private long lastLobbySeenMs;
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
        supplies = new WaveSupplyTracker(itemManager, (wave, cost, detail) -> {
            if (cost > 0) record(LedgerEvent.Type.SUPPLIES, -cost, detail, wave);
        }, this::refresh);
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
        // Do not write unfinalized supply deductions when shutting down mid-attempt.
        if (supplies != null) supplies.reset();
    }

    /** The exact Sol Heredit icon used by RuneLite's HiScores panel. */
    void loadSolHereditIcon(Consumer<BufferedImage> callback)
    {
        spriteManager.getSpriteAsync(HiscoreSkill.SOL_HEREDIT.getSpriteId(), 0, callback);
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
            if (supplies != null) supplies.reset();
            inColosseumLobby = false;
            lastLobbySeenMs = 0;
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

    boolean editEntry(LedgerEvent event, LedgerEvent.Type type, long gp, int wave, String note)
    {
        boolean changed = ledger.update(event, type, gp, wave, note);
        if (changed) refresh();
        return changed;
    }

    int activeWave() { return activeWave; }
    boolean trackingSupplies() { return supplies != null && supplies.isTracking(); }
    long currentSupplyCost() { return supplies == null ? 0 : supplies.currentCost(); }
    java.util.List<WaveSupplyTracker.ItemUsage> currentSupplyItems()
    {
        return supplies == null ? java.util.Collections.emptyList() : supplies.currentItems();
    }
    net.runelite.client.util.AsyncBufferedImage supplyIcon(int itemId)
    {
        return itemManager.getImage(itemId);
    }
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
        if (config.autoSupplyTracking() && (inColosseumLobby || client.isInInstancedRegion()))
        {
            if (!supplies.isTracking()) supplies.begin(wave, client.getItemContainer(InventoryID.INVENTORY),
                client.getItemContainer(InventoryID.EQUIPMENT));
            else supplies.changeWave(wave);
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

    /** ItemManager must be accessed on RuneLite's client thread, not Swing's EDT. */
    void requestQuiverTurnInValue(java.util.function.LongConsumer onValue)
    {
        clientThread.invokeLater(() ->
        {
            long unitPrice = itemManager.getItemPrice(28924); // Sunfire splinters
            long value = unitPrice > 0 ? unitPrice * 4_000L : 0L;
            SwingUtilities.invokeLater(() -> onValue.accept(value));
        });
    }

    void recordQuiverTurnIn(long gp, long unitPrice)
    {
        if (gp <= 0 || unitPrice <= 0 || gp != unitPrice * 4_000L)
        {
            throw new IllegalArgumentException("Invalid quiver exchange valuation");
        }
        record(LedgerEvent.Type.ADJUSTMENT, gp,
            "Dizana's quiver exchanged for 4,000 Sunfire splinters @ " + unitPrice + " GP each", -1);
    }

    /** Track pre-wave supplies only while Minimus is present in the Colosseum lobby.
     * Combat supply tracking continues only during a known Colosseum run in the arena.
     * Leaving the area abandons unfinalized consumption so no outside use is charged.
     */
    @Subscribe public void onGameTick(GameTick event)
    {
        if (supplies == null) return;
        if (!config.autoSupplyTracking() || client.getGameState() != GameState.LOGGED_IN)
        {
            supplies.reset();
            inColosseumLobby = false;
            ayakNormalWasFrameZero = false;
            blowpipeWasFrameZero = false;
            return;
        }
        inColosseumLobby = isColosseumLobby();
        long now = System.currentTimeMillis();
        if (inColosseumLobby) lastLobbySeenMs = now;
        boolean inArenaRun = active && !terminal && client.isInInstancedRegion();
        // The lobby disappears before the first wave-start message arrives.
        // Preserve its accrued costs during that brief transition, without
        // tracking inventory changes until the Colosseum run is confirmed.
        boolean enteringArena = !active && supplies.isTracking() && client.isInInstancedRegion()
            && lastLobbySeenMs > 0 && now - lastLobbySeenMs <= 30_000L;
        if (!inColosseumLobby && !inArenaRun && !enteringArena)
        {
            supplies.reset();
            ayakNormalWasFrameZero = false;
            blowpipeWasFrameZero = false;
            return;
        }
        if (inColosseumLobby && !active && !supplies.isTracking())
        {
            // -1 is the existing "Adj." wave marker; the note says "pre-wave lobby".
            supplies.begin(-1, client.getItemContainer(InventoryID.INVENTORY),
                client.getItemContainer(InventoryID.EQUIPMENT));
            supplies.snapshotQuiver(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO),
                client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT));
        }
        updateRunePouchSupplies();
        // Blowpipe uses internal darts and scales; no inventory stack shrinks.
        boolean blowpipeShot = inArenaRun && supplies.isTracking() && isBlowpipeEquipped()
            && client.getLocalPlayer() != null
            && client.getLocalPlayer().getAnimation() == BLOWPIPE_ATTACK_ANIMATION
            && client.getLocalPlayer().getAnimationFrame() == 0;
        if (blowpipeShot && !blowpipeWasFrameZero)
        {
            supplies.blowpipeShot(dartLossRate());
        }
        blowpipeWasFrameZero = blowpipeShot;
        // Run-level cost: normal Ayak attacks are sampled once at frame zero.
        // Do not count the same zero frame twice if it persists across ticks.
        boolean normal = inArenaRun && supplies.isTracking() && isAyakEquipped()
            && client.getLocalPlayer() != null
            && client.getLocalPlayer().getAnimation() == AYAK_CAST_ANIMATION
            && client.getLocalPlayer().getAnimationFrame() == 0;
        if (normal && !ayakNormalWasFrameZero) supplies.ayakCast();
        ayakNormalWasFrameZero = normal;
    }

    @Subscribe public void onAnimationChanged(AnimationChanged event)
    {
        if (event.getActor() != client.getLocalPlayer() || !active || terminal
            || !client.isInInstancedRegion() || supplies == null || !supplies.isTracking()) return;
        int animation = event.getActor().getAnimation();
        if (animation == AYAK_SPECIAL_ANIMATION && isAyakEquipped())
        {
            supplies.ayakCast();
        }
        else if (animation == SCYTHE_ATTACK_ANIMATION && isChargedScytheEquipped())
        {
            supplies.scytheAttack();
        }
        else if (animation == SHADOW_CAST_ANIMATION && isChargedShadowEquipped())
        {
            supplies.shadowCast();
        }
        else if (animation == VENATOR_BOW_ATTACK_ANIMATION && isChargedVenatorBowEquipped())
        {
            supplies.venatorBowAttack();
        }
    }

    private int equippedWeaponId()
    {
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment == null || equipment.getItems().length <= 3) return -1;
        net.runelite.api.Item weapon = equipment.getItems()[3];
        return weapon == null ? -1 : weapon.getId();
    }

    private boolean isChargedScytheEquipped()
    {
        int id = equippedWeaponId();
        return id == ItemID.SCYTHE_OF_VITUR || id == ItemID.HOLY_SCYTHE_OF_VITUR
            || id == ItemID.SANGUINE_SCYTHE_OF_VITUR;
    }

    private boolean isChargedShadowEquipped()
    {
        return equippedWeaponId() == ItemID.TUMEKENS_SHADOW;
    }

    private boolean isChargedVenatorBowEquipped()
    {
        // The uncharged bow has a different item ID and must not incur essence costs.
        return equippedWeaponId() == ItemID.VENATOR_BOW;
    }

    private boolean isBlowpipeEquipped()
    {
        return equippedWeaponId() == ItemID.TOXIC_BLOWPIPE;
    }

    private double dartLossRate()
    {
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment == null) return 1.0;
        Item[] items = equipment.getItems();
        int capeSlot = EquipmentInventorySlot.CAPE.getSlotIdx();
        if (items.length <= capeSlot || items[capeSlot] == null || items[capeSlot].getId() <= 0)
            return 1.0;
        String name = itemManager.getItemComposition(items[capeSlot].getId())
            .getName().toLowerCase(Locale.ROOT);
        if (name.contains("assembler") || name.contains("dizana") || name.contains("quiver")) return 0.20;
        if (name.contains("accumulator") || name.contains("ranging cape") || name.contains("max cape")) return 0.28;
        if (name.contains("attractor")) return 0.40;
        return 1.0;
    }

    @Subscribe public void onVarbitChanged(VarbitChanged event)
    {
        if (!config.autoSupplyTracking() || supplies == null || !supplies.isTracking()
            || !(inColosseumLobby || (active && !terminal && client.isInInstancedRegion()))) return;
        int varpId = event.getVarpId();
        if (varpId != VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO
            && varpId != VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT) return;
        supplies.quiverChanged(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO),
            client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT));
    }

    @Subscribe public void onProjectileMoved(ProjectileMoved event)
    {
        if (!active || terminal || !client.isInInstancedRegion() || supplies == null
            || !supplies.isTracking() || !isBlowpipeEquipped() || client.getLocalPlayer() == null
            || client.getLocalPlayer().getAnimation() != BLOWPIPE_ATTACK_ANIMATION) return;
        Projectile projectile = event.getProjectile();
        if (projectile == null || projectile == lastBlowpipeProjectile) return;
        int dart = dartForProjectile(projectile.getId());
        if (dart <= 0) return;
        WorldPoint from = WorldPoint.fromLocal(client,
            new LocalPoint(projectile.getX1(), projectile.getY1()));
        if (!client.getLocalPlayer().getWorldLocation().equals(from)) return;
        lastBlowpipeProjectile = projectile;
        supplies.blowpipeDartObserved(dart);
    }

    private static int dartForProjectile(int projectileId)
    {
        switch (projectileId)
        {
            case 226: return ItemID.BRONZE_DART;
            case 227: return ItemID.IRON_DART;
            case 228: return ItemID.STEEL_DART;
            case 32: return ItemID.BLACK_DART;
            case 229: return ItemID.MITHRIL_DART;
            case 230: return ItemID.ADAMANT_DART;
            case 231: return ItemID.RUNE_DART;
            case 1936: return ItemID.AMETHYST_DART;
            case 1122: return ItemID.DRAGON_DART;
            default: return -1;
        }
    }

    private boolean isAyakEquipped()
    {
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment == null || equipment.getItems().length <= 3) return false;
        net.runelite.api.Item weapon = equipment.getItems()[3];
        return weapon != null && weapon.getId() == ItemID.EYE_OF_AYAK;
    }

    private boolean isColosseumLobby()
    {
        // Minimus can also appear during an instanced run; that is not the lobby.
        if (client.isInInstancedRegion()) return false;
        for (NPC npc : client.getNpcs())
        {
            if (npc != null && npc.getId() == COLOSSEUM_LOBBY_MINIMUS_ID)
            {
                return true;
            }
        }
        return false;
    }

    @Subscribe public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (config.autoSupplyTracking() && supplies != null && supplies.isTracking()
            && (inColosseumLobby || (active && !terminal && client.isInInstancedRegion())))
        {
            supplies.onMenuOption(event.getMenuOption(), event.getItemId());
        }
    }

    @Subscribe public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!config.autoSupplyTracking() || supplies == null || !supplies.isTracking()
            || !(inColosseumLobby || (active && !terminal && client.isInInstancedRegion()))) return;
        ItemContainer container = event.getItemContainer();
        if (container == null) return;
        if (container.getId() == InventoryID.INVENTORY.getId()) supplies.inventoryChanged(container);
        else if (container.getId() == InventoryID.EQUIPMENT.getId()) supplies.equipmentChanged(container);
    }

    private void startSuppliesIfNecessary(int wave)
    {
        if (!config.autoSupplyTracking() || supplies == null
            || !(inColosseumLobby || client.isInInstancedRegion())) return;
        if (!supplies.isTracking()) supplies.begin(wave, client.getItemContainer(InventoryID.INVENTORY),
            client.getItemContainer(InventoryID.EQUIPMENT));
        else supplies.changeWave(wave);
        supplies.snapshotQuiver(client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO),
            client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT));
    }

    /** Sync four rune pouch slots once per game tick while Colosseum tracking is active.
     * ItemManager valuation happens here on the RuneLite client thread.
     */
    private void updateRunePouchSupplies()
    {
        if (supplies == null || !supplies.isTracking()) return;
        int[] types = {
            client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1),
            client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_2),
            client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_3),
            client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_4)
        };
        int[] quantities = {
            client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1),
            client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_2),
            client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_3),
            client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_4)
        };
        supplies.runePouchChanged(types, quantities);
    }

    private void completeSupplies()
    {
        if (supplies != null) supplies.finish();
    }

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
        completeSupplies();
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
        completeSupplies();
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
        completeSupplies();
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
                // Keep pre-wave lobby expenses for this attempt. A prior run's
                // expenses were already finalized by its finish method.
                lastExitType = null;
                lastExitWave = 0;
                exitHasLoot = false;
            }
            startSuppliesIfNecessary(confirmedCompletion);
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
                // Do not discard expenses accumulated in the lobby before wave 1.
                completedWave = 0;
                lastExitType = null;
                exitHasLoot = false;
            }
            if (wave > activeWave && activeWave > 0 && active)
            {
                // Reaching wave N shows the preceding wave was survived.
                completedWave = Math.max(completedWave, wave - 1);
            }
            startSuppliesIfNecessary(wave);
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
