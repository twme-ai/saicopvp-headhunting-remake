# SaicoPvP HeadHunting Remake

A production-oriented, independently implemented remake of the SaicoPvP HeadHunting progression and economy loop
for Paper 1.21.11. It combines the original sell-heads-to-progress model with an optional direct-kill mode, 25
configurable levels, authenticated tradable heads, player-head bounties, Souls, exchanges, localized GUIs, and
crash-recoverable SQLite transactions.

This is an unofficial community project. It is not affiliated with or endorsed by SaicoPvP.

## Requirements

- Paper 1.21.11
- Java 21 or newer
- No required runtime dependencies

## Install

1. Download `SaicoPvP-Headhunting-Remake-1.0.0.jar` from the
   [v1.0.0 GitHub release](https://github.com/twme-ai/saicopvp-headhunting-remake/releases/tag/v1.0.0).
2. Place it in the server's `plugins` directory.
3. Start Paper once and wait for `SaicoPvPHeadhuntingRemake is ready` in the console.
4. Stop the server and review `config.yml`, `heads.yml`, `levels.yml`, and `exchanges.yml` under the generated plugin
   directory.
5. Start the server again. Use `/headhunt admin reload` for later configuration-only changes.

Do not use Bukkit's `/reload`. Keep `headhunting.db` and `secret.key` together in backups. Losing `secret.key` makes
previously issued heads unverifiable, and the plugin deliberately refuses to generate a new key beside an existing
database.

## Gameplay

- `SELL_HEADS` mode: eligible mob kills mint signed, tradable heads. Selling the current level's head types advances
  progress; lower unlocked heads still pay money.
- `DIRECT_KILLS` mode: eligible personal kills advance current-tier progress immediately and can award configured
  money or Souls. Heads remain authenticated economy items.
- Rank-up checks progress, per-mob kill requirements, and the configured internal-balance cost in one database
  transaction.
- Player heads escrow a configurable fraction of the victim's internal balance. Pair and victim cooldowns, a minimum
  balance, a value cap, same-address blocking, drop chance, damage causes, and world rules are configurable.
- Head Exchange recipes reserve signed head supply and Souls before removing inventory items, then deliver rewards
  through a durable outbox.

The default preset is OG-inspired rather than a claim that every historical realm used the same numbers. See
[research and gameplay decisions](docs/RESEARCH.md).

## Commands

| Command | Purpose |
| --- | --- |
| `/headhunt`, `/heads`, `/hh`, `/level` | Open the 25-level progression menu |
| `/rankup` or `/headhunt rankup` | Complete the current level when requirements are met |
| `/headhunt sell [hand\|all]` | Sell eligible authenticated heads |
| `/headhunt exchange` | Open Head Exchange |
| `/headhunt status` | Show internal balance, Souls, and progress |
| `/headhunt language <locale\|auto>` | Select a persisted locale or follow the client locale |
| `/headhunt admin reload` | Validate and atomically activate plugin configuration |
| `/headhunt admin inspect` | Inspect the signed head held by a player |
| `/headhunt admin give <player> <head> [amount]` | Mint authenticated mob heads |
| `/headhunt admin setlevel <player> <level>` | Set an online player's level |
| `/headhunt admin addprogress <player> <amount>` | Adjust an online player's progress |
| `/headhunt admin addsouls <player> <amount>` | Adjust an online player's Souls |
| `/headhunt admin addbalance <player> <amount>` | Adjust an online player's internal balance |

See the [administrator guide](docs/ADMIN_GUIDE.md) for permissions, signs, configuration, backups, and recovery.

## Security and durability

- Head PDC payloads use HMAC-SHA-256 with a server-local 256-bit key.
- SQLite tracks minted, reserved, and redeemed quantities, so copied valid stacks cannot redeem more than the batch
  the server originally minted.
- Sales and exchanges lease a player's inventory, reserve database supply, validate exact main-thread slot snapshots,
  and recover interrupted operations after reconnect or restart.
- Mint and reward outboxes commit before physical delivery. Duplicate delivery after a crash cannot create additional
  redeemable supply.
- SQLite uses one ordered asynchronous executor, WAL, foreign keys, a busy timeout, and explicit transactions. Bukkit
  entity and inventory access remains on the server thread.
- Player-provided values enter MiniMessage through unparsed placeholders. Every visible plugin message, item name,
  lore line, sign line, and GUI title comes from locale files parsed as MiniMessage.

## Localization

`en_US` and `zh_TW` ship with identical translation keys. New locales can be added under `locales/`, then included in
`localization.supported-locales` in `config.yml`. Player overrides persist in SQLite; `auto` follows Paper's locale
change event and falls back by language, then to the configured default. Existing authenticated heads in an online
player's inventory are relocalized without changing their signed economic payload.

## Integration boundaries

The built-in integer-cent wallet is authoritative because it permits head redemption, player-head escrow, rank-up,
and rewards to commit atomically. Version 1.0.0 does not include a Vault bridge. Server-specific faction commands,
proprietary mask effects, custom horde spawning, faction flight, and head-flip gambling are intentionally outside the
portable core. Exchange items, command rewards, Bukkit events, and the public service API provide integration points.
Command rewards are at-most-once but cannot be crash-atomic with another plugin.

Custom head textures are optional through each head's `texture-url`; entries without one use the configured vanilla
skull material or a generic player head. The supplied exchange masks are named reward items, not effect-bearing
proprietary masks.

## Build and verify

```bash
./gradlew clean build
```

The build compiles with Java 21 `-Xlint:all -Werror`, runs JUnit integration tests, Checkstyle, and SpotBugs. See
[testing](docs/TESTING.md) for the Paper and Mineflayer validation procedure and recorded v1.0.0 results.

The API is described in [docs/API.md](docs/API.md). This project is available under the [MIT License](LICENSE).
