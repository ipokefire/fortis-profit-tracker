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
