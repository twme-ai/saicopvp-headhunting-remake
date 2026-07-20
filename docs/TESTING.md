# Testing and Validation

The 1.0.1 release was validated on 2026-07-20 with Temurin Java 21.0.11 and Paper 1.21.11 build 132.

Release JAR SHA-256:

```text
57341a12a42b94e029e3b6564d6dfafbe4b49dbaaa9b356ae410508144672695
```

## Automated build

```bash
./gradlew clean build
```

The build treats every Java compiler warning as an error and runs:

- 31 JUnit tests with zero failures and zero errors.
- Configuration resource tests for the 25-level preset and equal `en_US`/`zh_TW` translation key sets.
- HMAC signature, tamper rejection, key persistence, and missing-key fail-closed tests.
- SQLite integration tests for mint limits, duplicate-stack rejection, idempotent sale finalization, reservation
  cancellation, delivery outboxes, player-head escrow and cooldowns, rank-up rewards, exchange recovery, built-in
  exchange rewards, overflow rollback, and schema v1-to-v2 reward recovery.
- Policy tests proving that sell mode still records kill requirements and per-kill Soul rewards while direct mode also
  credits immediate progress.
- Reload validation tests for atomic settings/translation snapshots, stored-level capacity, required translation keys,
  duplicate reward IDs, and inaccessible progress heads.
- Checkstyle for main and test sources with zero warnings.
- SpotBugs for main and test bytecode.

## Paper runtime validation

The release JAR was started twice on Paper with an existing version 1 database. Both starts reached the plugin readiness
message, registered commands and services, reloaded configuration, reused the generated secret, migrated the database to
schema version 2, and stopped cleanly. The plugin produced no warnings, errors, severe messages, or exceptions.

The test environment itself emitted Paper warnings for being executed as root and for 1.21.11 no longer being the
newest Minecraft line. The controlled Mineflayer test additionally used offline mode. These were server-environment
warnings, not plugin diagnostics; production servers should run under an unprivileged account with online mode or a
correctly secured proxy.

## Player workflow evidence

The tracked scripts under `testing/mineflayer` pin Mineflayer 4.37.1. Against the Paper server, they verified:

1. A new player started with balance 0 and level 1 progress 0/128.
2. `zh_TW`, `en_US`, and persisted locale changes rendered correctly.
3. The level and exchange custom inventories opened and protected their top inventories.
4. An administrator minted ten authentic pig heads, the bot dropped and picked one up, and `/headhunt sell all`
   credited $100.00 and 10 progress.
5. `/rankup` rejected the incomplete level with the correct remaining requirement.
6. After a full server restart, balance $100.00 and progress 10/128 persisted.
7. A pig death credited to the bot through Paper's actual `EntityDeathEvent` dropped an authenticated head; its sale
   credited another $10.00 and one progress, ending at $110.00 and 11/128.
8. In `SELL_HEADS` mode, an unlocked zombie kill credited eight Souls without granting immediate progress, confirming
   that per-kill rewards are independent from the configured progression mode.

The death tests temporarily permitted the `COMMAND` spawn reason and used Minecraft's `/damage ...
minecraft:player_attack by HeadTest` command so the test stayed deterministic. The Soul test also temporarily lowered
the zombie unlock level for its fresh profile. These exceptions were limited to the ignored test-server configuration,
were restored after validation, and are not present in the shipped default.

## Reproducing the bot checks

```bash
cd testing/mineflayer
npm ci
npm run smoke
npm run persistence
npm run kill-flow
```

These scripts expect Paper at `127.0.0.1:25565`, a test-only offline-mode server, and an operator named `HeadTest`.
Start the server before each required phase and stop it normally. Never use these offline-mode settings on a public
server. Unit and integration tests do not require a Minecraft server or Node.js.
