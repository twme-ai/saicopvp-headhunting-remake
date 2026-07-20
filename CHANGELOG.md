# Changelog

All notable changes to this project are documented here.

## 1.0.1 - 2026-07-20

- Made configuration and translations activate through one atomic runtime snapshot on the Paper main thread.
- Closed the database bootstrap/shutdown race and suppressed intentional cancellation noise during disable.
- Added schema version 2 to recover undelivered built-in exchange rewards from version 1.0.0 exactly once.
- Made `BALANCE` and `SOULS` exchange rewards commit atomically instead of entering the item/command outbox.
- Applied kill requirements, Soul rewards, and direct-money rewards in both progression modes.
- Added exact overflow guards for sale, progress, rank-up, exchange reward, and exchange cancellation counters.
- Rejected destructive level-list shrinkage, duplicate mob mappings and reward IDs, inaccessible progress heads, and
  missing required translations.
- Ensured sell-sign creation respects later event cancellation or modification by protection plugins.
- Expanded automated coverage from 18 to 31 tests.

## 1.0.0 - 2026-07-20

- Added a configurable 25-level OG-inspired HeadHunting progression and optional direct-kill mode.
- Added authenticated mob and player heads with HMAC signatures and a SQLite mint/redemption ledger.
- Added crash-recoverable sales, exchanges, mint delivery, level rewards, player-head escrow, and cooldowns.
- Added internal integer-cent economy, Souls, kill requirements, exchange recipes, GUI flows, sell signs, commands,
  permissions, administration tools, and Bukkit service/events API.
- Added automatic and manual per-player localization with `en_US` and `zh_TW` MiniMessage bundles.
- Added Java 21 build checks, JUnit and SQLite integration coverage, static analysis, Paper restart validation, and
  Mineflayer player-flow tests.
