# Administrator Guide

## Files and startup

The plugin creates these files under `plugins/SaicoPvPHeadhuntingRemake/`:

| File | Purpose |
| --- | --- |
| `config.yml` | Mechanics, progression mode, spawn and damage rules, worlds, player heads, locales |
| `heads.yml` | Mob mapping, display key, material, texture, value, progress, drops, Souls, direct money |
| `levels.yml` | Sequential levels, tiers, requirements, costs, unlocks, kill requirements, rewards |
| `exchanges.yml` | Signed-head and Soul costs plus rewards |
| `locales/*.yml` | MiniMessage player-facing text |
| `headhunting.db` | Profiles, ledgers, cooldowns, transactions, and delivery outboxes |
| `secret.key` | HMAC key that authenticates issued heads |

Startup loads and validates configuration and runs database migrations off the server thread. Commands report a
localized starting message until initialization completes. A configuration error disables the plugin without
partially registering gameplay listeners.

`/headhunt admin reload` parses and validates all files before activating them. A rejected reload leaves the prior
configuration active. Reloading does not rewrite already signed economic values: a head retains the value and progress
stored in its authenticated payload. Do not use Bukkit `/reload` or a plugman-style unload.

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `headhunting.use` | everyone | Open menus and view status |
| `headhunting.sell` | everyone | Sell authenticated heads |
| `headhunting.exchange` | everyone | Use Head Exchange |
| `headhunting.rankup` | everyone | Rank up |
| `headhunting.language` | everyone | Select a personal locale |
| `headhunting.sign.use` | everyone | Use a HeadHunting sell sign |
| `headhunting.sign.create` | operators | Create a HeadHunting sell sign |
| `headhunting.admin` | operators | All administration commands; includes sign creation |

Administrative profile mutations intentionally require the target to be online so the database update and active
session cannot diverge. Console can run reload and online-player mutation commands. Inspect requires a player's held
item.

## Sell signs

Place a sign with `[HeadHunt]` on the first line while holding `headhunting.sign.create`. The plugin marks the sign in
PDC, writes localized MiniMessage components, and waxes it. A player with `headhunting.sign.use` can right-click it to
sell every eligible authenticated head. Text alone cannot forge a working sign.

## Configuration notes

- `progression-mode` accepts `SELL_HEADS` or `DIRECT_KILLS`.
- `allowed-spawn-reasons` and `allowed-damage-causes` are Paper enum names. Unknown values reject the configuration.
- Empty `worlds.allowed` means every world except `worlds.blocked`.
- Currency strings have exactly two-decimal precision internally. Avoid changing prices without first testing a copy
  of production configuration.
- At most 28 levels are supported because the level GUI reserves controls in its 54-slot top inventory.
- Every referenced head key, display key, material, entity type, locale, reward, and exchange cost is validated.
- `maximum-pending-mints` bounds durable delivery pressure. Reaching it skips new drops rather than exhausting the
  database queue.
- `texture-url` is optional. Use a Mojang texture URL value supported by Paper's player-profile property API.

Player-head `deduct-from-victim: true` makes the bounty an escrow transfer rather than new currency. The default is 10
percent, capped at one million, with a six-hour killer/victim pair cooldown and one-hour victim cooldown. Same-address
blocking is a coarse anti-farming signal, not identity proof; proxies can make address data unsuitable, so configure it
for the network topology.

## Rewards and integrations

Supported level and exchange reward types are `MONEY`, `SOULS`, `ITEM`, and `COMMAND`. Item rewards use a durable token
so reconnect recovery does not duplicate them. Command rewards are marked delivering before dispatch and therefore
run at most once, but no plugin can make a console command and this SQLite database one atomic transaction. Prefer the
built-in reward types for economic state.

There is no built-in Vault bridge in 1.0.0. Use the Bukkit service API and events for integrations that accept the
documented consistency boundary. Proprietary mask effects and custom horde behavior require another plugin.

## Backup and restore

1. Stop the server normally. Shutdown checkpoints WAL and closes the database executor.
2. Copy `headhunting.db` and `secret.key` together. Copy YAML configuration and locale files as the same revision.
3. Restore all files while the server is stopped, then start Paper and inspect the readiness log.

For live filesystem snapshots, use an SQLite-aware backup process and include `headhunting.db-wal` and
`headhunting.db-shm`; copying only the main database during writes is not a valid backup.

Never delete or replace `secret.key` to resolve an authentication problem. Investigate the held item with
`/headhunt admin inspect`, restore the matching key from backup, or retire the old economy deliberately.

## Recovery behavior

Interrupted sales and exchanges are reconciled on player load. If exact reserved items are still present, the
transaction is cancelled and reserved supply or Souls are restored. If they are absent, it is finalized and credit or
reward delivery continues. Pending minted heads and rewards are delivered from outboxes. Inventory mutation is blocked
for the short duration of an active transaction.

If startup fails, read the first `SaicoPvPHeadhuntingRemake` exception in the Paper log. Configuration validation errors
name the invalid path. Database and secret-key errors are fail-closed; restore the matching files rather than starting
with an empty ledger.
