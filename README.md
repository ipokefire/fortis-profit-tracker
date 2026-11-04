## v1.4.13 — Simplified automated controls

- Removed obsolete estimated death fee and supplies-per-manual-start configuration.
- Removed manual start, clear, death, and cash-out buttons; automatic event detection remains enabled through its setting.
- Renamed Add GP entry to Manual entry.
- Added Edit entry in Today and History to modify an existing record's type, GP, wave, and note without changing its account or timestamp.
- Retained Quiver turn-in, Delete entry, and CSV export.

# Colosseum Profit Tracker

Colosseum Profit Tracker is a third-party RuneLite plugin for Old School RuneScape's Fortis Colosseum. It keeps a local ledger of run outcomes, loot, expenses, and estimated profit or loss.

## Features

- Tracks Colosseum completions and early cash-outs.
- Detects wave changes from game messages and attributes deaths to waves.
- Records Colosseum death events and can apply an observed bank-funded gravestone recovery payment to the original death entry.
- Displays daily GP profit/loss with K/M formatting.
- Provides Today, History, and All-Time tabs with scrolling. The All-Time tab aggregates every recorded date for the active in-game account, including total GP, clears, cash-outs, and deaths.
- Saves history to a local JSON ledger and supports CSV export.
- Supports manual entries and correction controls.

## Important limitations

- Recovery fee detection currently relies on a particular game chat message: `Payment has been taken from your bank: 10,000 x Coins` (the amount varies). This is **not** proof that a particular payment is a grave fee. The plugin assigns a matching payment to the most recent unpaid Colosseum death for that character within 30 minutes. Review the ledger if you make an unrelated bank payment within that window.
- Payments through Death's Coffer, delayed recovery, and some other reclamation paths may not be detected. A free reclamation can remain at 0 GP. Review all P&L figures rather than treating them as authoritative.
- Detection depends on RuneLite and OSRS game events/messages, which may change. Some outcomes may require manual correction.

## Data and privacy

The plugin stores ledger entries locally under the RuneLite configuration directory in `fortis-profit-tracker/ledger.json`. The entries include your in-game character name, timestamps, run outcomes, wave numbers, notes, and GP amounts. CSV export writes to the destination you select. This version does not implement upload to an external server.

Back up `ledger.json` before upgrading or making manual changes. Do not publicly commit your personal ledger or exported CSV files.

## Development

This is a Gradle Java 11 RuneLite plugin. Open the repository in IntelliJ IDEA, refresh Gradle, run `gradle test`, and launch the provided `run` task to test in a development client. RuneLite requires its documented Jagex Account development-client setup if you log in with a Jagex Account.

The release metadata is in `runelite-plugin.properties` and the main plugin class is `com.fortisprofit.FortisProfitPlugin`.

## Plugin Hub submission

1. Publish this source as a **public** GitHub repository.
2. Run the tests and confirm it starts correctly in a RuneLite development client.
3. Commit and push the repository, then copy the **full 40-character Git commit SHA**.
4. Fork https://github.com/runelite/plugin-hub and create `plugins/fortis-profit-tracker` containing:

   ```properties
   repository=https://github.com/YOUR-USERNAME/fortis-profit-tracker.git
   commit=YOUR_FULL_40_CHARACTER_COMMIT_SHA
   ```

5. Open a pull request to `runelite/plugin-hub`, inspect its CI checks, and respond to reviewer feedback.

The plugin is not an official RuneLite plugin until accepted through the Plugin Hub review process.

## License

BSD 2-Clause License. See [LICENSE](LICENSE).

## Local data storage (v1.3.1)

The JSON ledger is stored locally in RuneLite’s plugin data directory (`.runelite/plugin-data/fortis-profit-tracker/ledger.json`). This uses RuneLite’s Filepath API and **does not sync between computers**. Existing pre-v1.3.1 ledgers in `.runelite/fortis-profit-tracker/` are **not migrated**. Use Export CSV to save a separate copy.

### v1.3.3 — manual quiver exchange and adjustments

- **Quiver turn-in** records one Dizana's quiver exchanged for **4,000 Sunfire splinters** as a positive GP credit. The unit GP price is taken from RuneLite's item price cache at confirmation time; historical entry values do not subsequently change.
- **Adj.** is available as a Wave choice for manual supply and other adjustment entries, and is displayed for quiver turn-ins.
- Removed the legacy **Mark cash-out** and one-off **Mark Sol clear** correction controls. These changes do not alter previously saved ledger records.


### v1.3.4: ledger display compatibility

- Quiver turn-ins use the existing ADJUSTMENT ledger type and display as Quiver turn-in.
- Prior quiver records with a missing event type are recognized when loading.
- Unknown event types no longer interrupt Today or History table rendering.


## v1.4 experimental automatic supply-cost tracking

Enable **Track supplies by wave** in the plugin configuration (on by default).
During a detected Colosseum attempt, the plugin records an expense when the
wave changes or the attempt ends. Each expense appears as a **Supplies** row
with its numeric wave, and affects Today, History and All-Time NET.

Initial coverage: food and potions consumed using Eat/Drink inventory menu
actions, inventory runes removed during the attempt, and single-unit ammo
decreases in the equipped ammunition slot. RuneLite item prices are used
when available. **Not yet included:** rune pouch consumption, charged
weapons (e.g. scythe, shadow, blowpipe), blood fury charges and special
consumables. Inventory rune decreases caused by transfers can be mistaken
for consumption; check your expenses after restocking in the arena.
The plugin does not rewrite historical attempts. Existing manual supply
estimates remain intact; with automatic tracking enabled, the legacy flat
manual-start estimate is suppressed to avoid double-counting.

Do not use automatic supply tracking and a separate manual entry for the
same expense. Test on the development client before publication.


### v1.4.2: Colosseum-area supply tracking
- Supply consumption is tracked only when the Colosseum lobby's Minimus is loaded, or during an active Colosseum arena instance. It resets on leaving these areas.
- Lobby consumables before starting a wave are recorded as `Adj.` with a `pre-wave lobby` note when wave 1 starts; in-arena expenses retain their wave number.
- The detector depends on NPC visibility/instance state; confirm its behavior by entering the lobby, drinking a potion, entering wave 1, and then leaving the Colosseum. It does not attempt to charge consumables used elsewhere.

### v1.4.2: Consolidated automatic supply expenses

Automatic supply tracking now combines qualifying lobby preparation and all arena wave
consumption into **one Supplies entry** at the end of each completed attempt, with
Wave **Adj.** (`-1` internally). Deaths, early cash-outs, and full clears each
finalize the running expense once. Leaving the Colosseum without starting an attempt
discards unfinalized lobby consumption. Manual supply adjustments remain separate.
Existing saved per-wave supply rows are not retroactively merged.


### v1.4.3 development: Current Run supplies
- A **Current Run** tab next to All-Time shows consumed supplies using RuneLite item sprites, quantities, and running GP totals. This temporary breakdown resets after the attempt; only one consolidated **Supplies / Adj.** expense is saved to the ledger at completion.
- **Eye of Ayak:** normal casts and special attacks are valued using **one Demon Tear per detected cast** (always assumed, regardless of actual loaded charge type).
- Existing food, potion, inventory-rune, and equipped-ammo estimates remain. Other charged weapons and rune-pouch charges are **not yet tracked**. Usage can be missed during client transitions and should be checked in-game.
- The Current Run tab is temporary and not persisted; an interrupted RuneLite session discards unfinalized consumption.

## v1.4.5 charged weapons (experimental)

- Eye of Ayak casts are valued using Demon Tears.
- Scythe of Vitur attack animations while the charged weapon is equipped are valued at **two blood runes plus 1/100 vial of blood** each. This is an **estimate**: missed attacks do not consume charges, and the client cannot confirm all successful hits via animation alone.
- Tumeken's Shadow cast animations while the charged weapon is equipped are valued at **two soul runes plus five chaos runes** each.
- Costs appear as weapon icons in Current Run and are included in one consolidated `Supplies / Adj.` ledger entry at the end of the attempt.
- Prices come from RuneLite and are captured during the attempt. Avoid manually entering those same charges again.

Detection adapted from SuppliesTrackerExternal under the BSD 2-Clause license; see `THIRD_PARTY_NOTICES.md`.

### v1.4.5 ammo and potion improvements (experimental)

- Equipment-slot arrows/bolts: track decreases of one or two; also track last-stack disappearance.
- Dizana's quiver: track ammo quantity varp changes (not the normal equipment ammo slot).
- Toxic blowpipe: identify loaded dart from player's projectile, then estimate darts used using Ava's cape saving rate and Zulrah's scales at 2/3 per shot. Averages may differ from actual RNG.
- Potions: group (1)–(4) doses as one Current Run tile and value at the four-dose price / 4 when the full-dose item ID is resolvable.
- Costs still roll up to one Supplies/Adj. entry per completed attempt.

**Limitations:** Dizana's quiver changes may occasionally reflect an ammo withdrawal.
Blowpipe costs are estimates, not exact random charge readings.
Runes or charges already priced via direct stack changes are not tracked again from blowpipe shots.

## v1.4.6 Venator Bow charge tracking (experimental)

- When a **charged Venator Bow** is equipped, each observed Venator Bow attack animation (9858, as used by SuppliesTrackerExternal) adds **one Ancient Essence** at RuneLite's item price to the Current Run tab. The two possible ricochets do **not** add extra charges.
- Arrows are still accounted for separately from actual equipment/quiver stack decreases. An uncharged Venator Bow is not charged Ancient Essence.
- As with other animation-based charged-weapon tracking, missed client events may undercount usage. Confirm the observed charge total during a Colosseum run before publishing.
- Supplies are still consolidated into one `Supplies / Adj.` entry per attempt.
