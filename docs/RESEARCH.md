# Research and Gameplay Decisions

Research was performed on 2026-07-20 before implementation and repeated during the 1.0.1 hardening audit. The built-in
search provider was retried again and returned `503 auth_not_found`; official pages, repository source, Maven metadata,
GitHub APIs, and directly retrieved public video metadata and captions remained accessible. Historical captions are
imperfect, so gameplay claims from videos
are treated as observations from a particular realm and season rather than universal rules.

## Current technical sources

- [Paper project setup](https://docs.papermc.io/paper/dev/project-setup) documents Gradle Kotlin DSL and the Paper
  Maven repository. Paper Maven metadata confirms the exact `1.21.11-R0.1-SNAPSHOT` API. The retrieved API JAR uses
  Java class-file major version 65, so this project targets Java 21.
- [Paper PDC documentation](https://docs.papermc.io/paper/dev/pdc/) recommends persistent data containers instead of
  internal NBT access. Paper 1.21.11 exposes read-only PDC views and direct PDC editing on `ItemStack`; PDC does not
  automatically copy between holders.
- [Paper scheduler documentation](https://docs.papermc.io/paper/dev/scheduler/) says file and database work should be
  asynchronous and warns that most Bukkit world state is unsafe off the main thread.
- The [Paper documentation repository](https://github.com/PaperMC/docs) was checked directly after search failed; its
  Paper development documentation had updates through 2026-07-19 during the maintenance audit.
- [Paper custom inventory holder documentation](https://docs.papermc.io/paper/dev/custom-inventory-holder/) recommends
  custom `InventoryHolder` implementations instead of inventory-title matching.
- [Paper database documentation](https://docs.papermc.io/paper/dev/databases/) confirms that Paper bundles the SQLite
  JDBC driver. The plugin uses one ordered database executor and SQLite WAL transactions.
- [Paper lifecycle documentation](https://docs.papermc.io/paper/dev/lifecycle/) documents lifecycle registration and
  reload restrictions. The plugin provides its own validated configuration reload and does not support unsafe
  `/reload`-style class unloading.
- [Adventure localization documentation](https://docs.papermc.io/adventure/localization/) documents client locale
  translation behavior and notes that Paper does not automatically translate item components.
- [MiniMessage API](https://docs.papermc.io/adventure/minimessage/api/) and
  [dynamic replacements](https://docs.papermc.io/adventure/minimessage/dynamic-replacements/) document reusable
  MiniMessage instances and safe unparsed placeholders. Every player-facing plugin message and item component is
  parsed from MiniMessage; untrusted names are inserted as components or unparsed placeholders.

PacketEvents is intentionally not included. Death events, item PDC, inventories, signs, locale changes, commands,
and schedulers are all available through Paper's server API, so packet interception would add risk without enabling a
required mechanic.

## Historical gameplay sources

- [The Ultimate SaicoPvP Factions Tutorial & Tips Guide](https://www.youtube.com/watch?v=lsYleS4kWl0), published
  2019-03-19, has dedicated HeadHunting OG and HeadHunting 2.0 sections. The OG footage describes 25 sequential
  levels, level-gated spawners, sellable mob heads, paid rank-ups, and current-tier head sales as the progression
  source. Lower unlocked heads still produce money. It also shows stacked spawners, natural mob eligibility, Souls,
  the Faction Merchant, masks, boosters, and end-tier unlocks. The 2.0 section instead advances progress on personal
  kills and shows Basic, Advanced, and Extreme tiers with money, soul, automatic-drop, and horde mob roles.
- [SaicoPvP Factions - HEADHUNTING](https://www.youtube.com/watch?v=RB0lTLdDyVc), published 2019-06-20, shows kill
  feedback, a level-up flow, the `/level` task and reward inventory, spawner use, mob heads, and Souls.
- [All level rewards in 3 minutes](https://www.youtube.com/watch?v=4tPw9yxOVb8), published 2017-11-06, shows a
  25-level reward inventory, tier rewards, `/rankup`, Head Exchange, charms, chests, Legendary Masks, and final
  Extreme-tier completion.
- [Basic Tier is cleared](https://www.youtube.com/watch?v=Rm7S24KQKRY), published 2017-07-25, and
  [completed a HeadHunting tier](https://www.youtube.com/watch?v=jc-JCmwKtvo), published 2020-01-13, show tier
  completion as a substantial grinding objective.
- [Ranking Up Fast](https://www.youtube.com/watch?v=QO3_jUaqSjs), published 2016-07-24, shows tradable stacks of mob
  heads with sell values and level-gated spawners. This confirms that trading or raiding heads was part of the OG
  economic loop rather than every point requiring a personal kill.
- [How to make a sell head sign](https://www.youtube.com/watch?v=2a1DcxMDyZc), published 2015-08-16, confirms a sign
  sale interaction existed.
- [Selling a rich player head](https://www.youtube.com/watch?v=oeZ2reQwAts), published 2017-11-01, shows a victim at
  about $15.7 million and a dropped head redeemed for $3 million. Other period videos show player heads being kept,
  traded, raided, and sold. The observed value is close to 20 percent, but values varied between realms and seasons.
- [SaicoPvP](https://saicopvp.com/) continues to describe HeadHunting as a network feature, but does not publish a
  complete historical ruleset or source implementation.

## Implemented interpretation

The default preset recreates OG's sell-to-progress loop with 25 configurable levels. A second direct-kill mode covers
the documented HeadHunting 2.0 behavior. Head definitions, level requirements, prices, rank-up costs, drops, rewards,
world rules, and role-specific money or Soul grants are data-driven.

Mob head batches are minted durably before they are dropped. A batch is shared for a configurable time window so
equivalent heads stack normally. Each item carries signed PDC data, while SQLite tracks the batch's minted, reserved,
and redeemed quantities. A copied authentic stack therefore cannot redeem more economic value than the server minted.
Sales use durable reservation records and per-player locks so GUI actions, repeated clicks, disconnects, or crashes do
not count the same quantity twice.

Player-head value is a configurable fraction of the victim's internal balance. The default is deliberately lower than
the observed 2017 value, and operators may select the period-accurate 20 percent behavior. Pair cooldowns, victim
cooldowns, minimum balance, allowed causes, and optional same-address detection limit farming.

Masks, Souls, tier rewards, Head Exchange, horde requirements, role-specific mobs, merchant-style exchanges, and
stacked spawner compatibility are included as configurable systems or integration points. Server-specific faction
commands, proprietary mask effects, custom horde spawning, faction flight, and gambling games are not inferred as
portable core behavior. They can be invoked through reward commands or the public API, with their integration limits
documented.

## Explicit assumptions

- The exact mob order, costs, required quantities, and rewards changed across realms and seasons. Defaults are a
  coherent 25-level OG-inspired preset, not a claim that one historical season used every value verbatim.
- Heads from natural and spawner-origin mobs are enabled by default. Other spawn reasons and indirect kill causes are
  configurable because period footage demonstrates multiple grinding strategies.
- Built-in integer minor-unit balances are authoritative by default, allowing the head ledger, rank-up, and reward
  state to commit atomically. External economy bridges cannot provide the same crash-atomic guarantee and are not the
  default.
- Paper's standard plugin reload mechanism is unsafe for database executors and lifecycle registrations. The admin
  reload command only validates and swaps this plugin's configuration and translations.
