package com.fortisprofit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.Item;
import net.runelite.api.ItemID;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;

/**
 * Restricted, Colosseum-only inventory/equipment consumption tracker.
 * Costs across the lobby and all waves are finalized as one Adj. ledger row.
 *
 * Inspired by the inventory comparison approach in SuppliesTrackerExternal,
 * Copyright (c) 2019 Dozer, BSD-2-Clause (see THIRD_PARTY_NOTICES.md).
 * Uses deliberately conservative detection. Rune pouch changes are tracked
 * via varbits; charged weapons and blowpipe shots use attack/projectile estimates.
 * Direct ammunition losses and Dizana's quiver stack changes are recorded.
 * Inventory swaps are never assumed to be consumed ammunition.
 * All ItemManager calls must run on the RuneLite client thread.
 */
final class WaveSupplyTracker
{
    interface Sink { void record(int wave, long gpCost, String description); }

    private final ItemManager itemManager;
    private final Sink sink;
    private final Runnable onChange;
    static final int DEMON_TEAR_ID = 31111;
    // Formulas sourced from SuppliesTrackerExternal, updated to the current
    // OSRS scythe charge recipe (2 blood runes + 1/100 vial per charge).
    static final int SCYTHE_ICON_ID = ItemID.SCYTHE_OF_VITUR;
    static final int SHADOW_ICON_ID = ItemID.TUMEKENS_SHADOW;
    static final int VENATOR_BOW_ICON_ID = ItemID.VENATOR_BOW;


    static final class ItemUsage
    {
        final int itemId;
        final String name;
        final int count;
        final long cost;
        ItemUsage(int itemId, String name, int count, long cost)
        {
            this.itemId = itemId;
            this.name = name;
            this.count = count;
            this.cost = cost;
        }
    }

    private final Map<Integer, ItemUsage> usage = new HashMap<>();
    private long currentCost;
    private final int[] pouchTypes = new int[4];
    private final int[] pouchQuantities = new int[4];
    private boolean pouchInitialized;
    private Map<Integer, Integer> inventory = new HashMap<>();
    private int ammoId = -1;
    private int ammoQuantity;
    private int quiverAmmoId = -1;
    private int quiverAmmoQuantity;
    private int blowpipeDartId = -1;
    private int pendingBlowpipeDarts;
    private final AmmoCostEstimator blowpipeCosts = new AmmoCostEstimator();
    private boolean tracking;
    private int wave;
    private final WaveCostAccumulator accumulator;
    private int pendingConsumedId = -1;
    private long pendingAt;

    WaveSupplyTracker(ItemManager itemManager, Sink sink, Runnable onChange)
    {
        this.itemManager = itemManager;
        this.sink = Objects.requireNonNull(sink);
        this.onChange = Objects.requireNonNull(onChange);
        this.accumulator = new WaveCostAccumulator(sink::record);
    }

    List<ItemUsage> currentItems()
    {
        List<ItemUsage> items = new ArrayList<>(usage.values());
        items.sort((a, b) -> Long.compare(b.cost, a.cost));
        return Collections.unmodifiableList(items);
    }

    long currentCost() { return currentCost; }

    boolean isTracking() { return tracking; }

    void begin(int wave, ItemContainer inventoryContainer, ItemContainer equipmentContainer)
    {
        reset();
        tracking = true;
        this.wave = wave == -1 ? -1 : Math.max(1, Math.min(12, wave));
        accumulator.begin(this.wave);
        inventory = countItems(inventoryContainer);
        snapshotAmmo(equipmentContainer);
        quiverAmmoId = -1;
        quiverAmmoQuantity = 0;
    }

    void reset()
    {
        boolean changed = tracking || !usage.isEmpty() || currentCost != 0;
        tracking = false;
        inventory.clear();
        ammoId = -1;
        ammoQuantity = 0;
        quiverAmmoId = -1;
        quiverAmmoQuantity = 0;
        blowpipeDartId = -1;
        pendingBlowpipeDarts = 0;
        blowpipeCosts.reset();
        wave = 0;
        accumulator.reset();
        pendingConsumedId = -1;
        usage.clear();
        currentCost = 0;
        pouchInitialized = false;
        java.util.Arrays.fill(pouchTypes, 0);
        java.util.Arrays.fill(pouchQuantities, 0);
        if (changed) onChange.run();
    }

    void changeWave(int wave)
    {
        if (!tracking || wave < 1 || wave > 12) return;
        if (wave != this.wave)
        {
            accumulator.changeWave(wave);
            this.wave = wave;
        }
    }

    void finish()
    {
        if (tracking) accumulator.finish();
        reset();
    }

    /** Only count an eaten food or drunk potion if an action occurred shortly before its disappearance. */
    void onMenuOption(String option, int itemId)
    {
        if (!tracking || itemId <= 0 || option == null) return;
        String action = option.toLowerCase(Locale.ROOT);
        if (action.equals("eat") || action.equals("drink"))
        {
            pendingConsumedId = itemId;
            pendingAt = System.currentTimeMillis();
        }
    }

    void inventoryChanged(ItemContainer container)
    {
        if (!tracking) return;
        Map<Integer, Integer> next = countItems(container);
        boolean pendingFresh = System.currentTimeMillis() - pendingAt < 2_500L;
        for (Map.Entry<Integer, Integer> previous : inventory.entrySet())
        {
            int id = previous.getKey();
            int lost = previous.getValue() - next.getOrDefault(id, 0);
            if (lost <= 0) continue;
            String name = itemName(id);
            if (name.endsWith(" rune"))
            {
                // Inventory rune consumption; withdrawing/placing into a pouch can
                // look the same, so pricing this category is explicitly best-effort.
                accrue(id, name, lost, price(id));
            }
            else if (id == pendingConsumedId && pendingFresh)
            {
                int dose = potionDoses(name);
                if (dose > 0)
                {
                    // Every dose (whether drawn from (1), (2), (3), or (4))
                    // is valued at exactly 1/4 of the same full potion.
                    String canonical = PotionGrouping.fullName(name);
                    int canonicalId = PotionGrouping.fullDoseId(canonical);
                    boolean knownFullDose = canonicalId > 0;
                    long fullPrice = knownFullDose ? price(canonicalId) : 0;
                    // Known potions are valued at a quarter of the 4-dose price.
                    // For an unknown variant, use the observed dose variant's
                    // price divided by its remaining doses as a fallback.
                    long each = Math.round((double) (fullPrice > 0 ? fullPrice : price(id))
                        / (fullPrice > 0 ? 4 : dose));
                    accrue(knownFullDose ? canonicalId : id, canonical, Math.min(lost, 1), each);
                }
                else
                {
                    accrue(id, name, Math.min(lost, 1), price(id));
                }
                pendingConsumedId = -1;
            }
        }
        inventory = next;
    }

    void equipmentChanged(ItemContainer equipment)
    {
        if (!tracking || equipment == null) return;
        // RuneLite's equipment ammo slot is index 13.
        Item[] equipped = equipment.getItems();
        Item ammo = equipped.length > 13 ? equipped[13] : null;
        int id = ammo == null ? -1 : ammo.getId();
        int quantity = ammo == null ? 0 : Math.max(0, ammo.getQuantity());
        if (id >= 0 && id == ammoId && ammoQuantity > quantity && isAmmo(itemName(id)))
        {
            int lost = ammoQuantity - quantity;
            // Include 2-shot weapons, but exclude bulk inventory moves.
            if (lost <= 2) accrue(id, itemName(id), lost, price(id));
        }
        else if ((id < 0 || id != ammoId) && ammoId > 0 && ammoQuantity <= 2
            && isAmmo(itemName(ammoId)))
        {
            // Last arrows fired: the stack can disappear completely.
            accrue(ammoId, itemName(ammoId), ammoQuantity, price(ammoId));
        }
        ammoId = id;
        ammoQuantity = quantity;
    }

    /** Rune pouch quantities and types come from RuneLite varbits, unlike inventory runes.
     * Snapshot first; subsequent decrements are valued as consumed runes.
     * Inspired by SuppliesTrackerExternal's RunePouch/Runes mapping.
     */
    void runePouchChanged(int[] types, int[] quantities)
    {
        if (!tracking || types == null || quantities == null || types.length != 4 || quantities.length != 4) return;
        for (int i = 0; i < 4; i++)
        {
            int type = types[i];
            int amount = Math.max(0, quantities[i]);
            if (pouchInitialized)
            {
                int consumed = RunePouchDiff.consumed(pouchTypes[i], pouchQuantities[i], type, amount);
                int runeId = runePouchItemId(pouchTypes[i]);
                if (consumed > 0 && runeId > 0)
                    accrue(runeId, itemName(runeId), consumed, price(runeId));
            }
            pouchTypes[i] = type;
            pouchQuantities[i] = amount;
        }
        pouchInitialized = true;
    }

    /** Rune-pouch type codes from SuppliesTrackerExternal's Runes enum. */
    static int runePouchItemId(int type)
    {
        switch (type)
        {
            case 1: return net.runelite.api.gameval.ItemID.AIRRUNE;
            case 2: return net.runelite.api.gameval.ItemID.WATERRUNE;
            case 3: return net.runelite.api.gameval.ItemID.EARTHRUNE;
            case 4: return net.runelite.api.gameval.ItemID.FIRERUNE;
            case 5: return net.runelite.api.gameval.ItemID.MINDRUNE;
            case 6: return net.runelite.api.gameval.ItemID.CHAOSRUNE;
            case 7: return net.runelite.api.gameval.ItemID.DEATHRUNE;
            case 8: return net.runelite.api.gameval.ItemID.BLOODRUNE;
            case 9: return net.runelite.api.gameval.ItemID.COSMICRUNE;
            case 10: return net.runelite.api.gameval.ItemID.NATURERUNE;
            case 11: return net.runelite.api.gameval.ItemID.LAWRUNE;
            case 12: return net.runelite.api.gameval.ItemID.BODYRUNE;
            case 13: return net.runelite.api.gameval.ItemID.SOULRUNE;
            case 14: return net.runelite.api.gameval.ItemID.ASTRALRUNE;
            case 15: return net.runelite.api.gameval.ItemID.MISTRUNE;
            case 16: return net.runelite.api.gameval.ItemID.MUDRUNE;
            case 17: return net.runelite.api.gameval.ItemID.DUSTRUNE;
            case 18: return net.runelite.api.gameval.ItemID.LAVARUNE;
            case 19: return net.runelite.api.gameval.ItemID.STEAMRUNE;
            case 20: return net.runelite.api.gameval.ItemID.SMOKERUNE;
            case 21: return net.runelite.api.gameval.ItemID.WRATHRUNE;
            case 22: return net.runelite.api.gameval.ItemID.SUNFIRERUNE;
            case 23: return net.runelite.api.gameval.ItemID.AETHERRUNE;
            default: return -1;
        }
    }

    /** Track ammunition stored inside Dizana's quiver via RuneLite's quiver varps.
     * The equipment ammo slot does not reflect these counts.
     * Assumes losses of 1-2 are fired, rather than a manual withdrawal.
     */
    void quiverChanged(int id, int quantity)
    {
        if (!tracking) return;
        quantity = Math.max(0, quantity);
        if (id == quiverAmmoId && id > 0 && quiverAmmoQuantity > quantity)
        {
            int lost = quiverAmmoQuantity - quantity;
            if (lost <= 2 && isAmmo(itemName(id))) accrue(id, itemName(id), lost, price(id));
        }
        else if (id < 0 && quiverAmmoId > 0 && quiverAmmoQuantity > 0
            && quiverAmmoQuantity <= 2 && isAmmo(itemName(quiverAmmoId)))
        {
            accrue(quiverAmmoId, itemName(quiverAmmoId), quiverAmmoQuantity, price(quiverAmmoId));
        }
        quiverAmmoId = id;
        quiverAmmoQuantity = quantity;
    }

    void snapshotQuiver(int id, int count)
    {
        quiverAmmoId = id;
        quiverAmmoQuantity = Math.max(0, count);
    }

    /** The projectile identifies the dart type held internally by Blowpipe. */
    void blowpipeDartObserved(int dartId)
    {
        if (!tracking || dartId <= 0) return;
        blowpipeDartId = dartId;
        if (pendingBlowpipeDarts > 0)
        {
            accrue(blowpipeDartId, itemName(blowpipeDartId), pendingBlowpipeDarts,
                price(blowpipeDartId));
            pendingBlowpipeDarts = 0;
        }
    }

    /** Charge scales from observed shots even before the dart type is known.
     * Dart type is learned from the player's projectile and retroactively
     * applied to accumulated expected dart losses.
     */
    void blowpipeShot(double dartLossRate)
    {
        if (!tracking) return;
        int[] lost = blowpipeCosts.shot(dartLossRate);
        if (lost[0] > 0)
        {
            if (blowpipeDartId > 0)
                accrue(blowpipeDartId, itemName(blowpipeDartId), lost[0], price(blowpipeDartId));
            else
                pendingBlowpipeDarts += lost[0];
        }
        if (lost[1] > 0)
            accrue(ItemID.ZULRAHS_SCALES, "Zulrah's scales", lost[1], price(ItemID.ZULRAHS_SCALES));
    }

    /** Eye of Ayak: always value each observed cast as one Demon Tear. */
    void ayakCast()
    {
        if (!tracking) return;
        accrue(DEMON_TEAR_ID, "Demon tear (Eye of Ayak)", 1, price(DEMON_TEAR_ID));
    }

    /**
     * Estimated scythe charge usage: animation count, rather than verified
     * successful hits. Scythe only consumes charges if at least one hit damages.
     * Keep the estimate visible in the Current Run item tile.
     */
    void scytheAttack()
    {
        if (!tracking) return;
        long blood = price(ItemID.BLOOD_RUNE);
        long vial = price(ItemID.VIAL_OF_BLOOD_22446);
        if (blood <= 0 || vial <= 0) return;
        // Avoid per-hit integer truncation by recording a rounded total per hit.
        long each = ChargedWeaponCosts.scythe(blood, vial);
        accrue(SCYTHE_ICON_ID, "Scythe charges (attack estimate)", 1, each);
    }

    /** Tumeken's Shadow: two soul and five chaos runes per charged cast. */
    void shadowCast()
    {
        if (!tracking) return;
        long soul = price(ItemID.SOUL_RUNE);
        long chaos = price(ItemID.CHAOS_RUNE);
        if (soul <= 0 || chaos <= 0) return;
        accrue(SHADOW_ICON_ID, "Tumeken's shadow charges", 1, ChargedWeaponCosts.shadow(soul, chaos));
    }

    /** Venator Bow: one Ancient Essence per fired arrow, never per ricochet.
     * Arrow consumption is tracked separately by quiver/equipment changes.
     */
    void venatorBowAttack()
    {
        if (!tracking) return;
        accrue(VENATOR_BOW_ICON_ID, "Venator bow charges (Ancient essence)",
            1, ChargedWeaponCosts.venatorBow(price(ItemID.ANCIENT_ESSENCE)));
    }

    private void accrue(int id, String name, int amount, long unitPrice)
    {
        if (amount <= 0 || unitPrice <= 0) return;
        long gp = unitPrice * (long) amount;
        accumulator.add(gp, amount);
        currentCost += gp;
        ItemUsage old = usage.get(id);
        usage.put(id, new ItemUsage(id, name, (old == null ? 0 : old.count) + amount,
            (old == null ? 0 : old.cost) + gp));
        onChange.run();
    }

    private long price(int id)
    {
        return itemManager == null ? 0 : Math.max(0, itemManager.getItemPrice(id));
    }

    private String itemName(int id)
    {
        return itemManager == null ? "" : itemManager.getItemComposition(id).getName().toLowerCase(Locale.ROOT);
    }

    private static int potionDoses(String name)
    {
        if (name.endsWith("(1)")) return 1;
        if (name.endsWith("(2)")) return 2;
        if (name.endsWith("(3)")) return 3;
        if (name.endsWith("(4)")) return 4;
        return 0;
    }

    private static boolean isAmmo(String name)
    {
        return name.contains(" arrow") || name.endsWith("arrow") || name.contains(" bolt")
            || name.endsWith("bolts") || name.contains(" dart") || name.endsWith("darts");
    }

    private void snapshotAmmo(ItemContainer equipment)
    {
        if (equipment == null) return;
        Item[] slots = equipment.getItems();
        Item ammo = slots.length > 13 ? slots[13] : null;
        ammoId = ammo == null ? -1 : ammo.getId();
        ammoQuantity = ammo == null ? 0 : Math.max(0, ammo.getQuantity());
    }

    private static Map<Integer, Integer> countItems(ItemContainer container)
    {
        Map<Integer, Integer> quantities = new HashMap<>();
        if (container == null) return quantities;
        for (Item item : container.getItems())
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return quantities;
    }
}
