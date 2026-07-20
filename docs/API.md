# Integration API

The plugin registers `HeadHuntingApi` in Bukkit's services manager after asynchronous startup is complete and
unregisters it during disable.

```java
HeadHuntingApi api = Bukkit.getServicesManager().load(HeadHuntingApi.class);
if (api == null) {
    // HeadHunting is absent or still starting.
}
```

Add the plugin as a compile-only dependency and declare a soft or hard dependency in the consuming plugin. Public
types are under `dev.saicoremake.headhunting.api` and `dev.saicoremake.headhunting.api.event`.

## Service methods

| Method | Thread and result |
| --- | --- |
| `head(String)` | Immutable configured definition snapshot; synchronous |
| `profile(UUID)` | Asynchronous SQLite profile load through `CompletableFuture` |
| `inspect(ItemStack)` | Synchronous signature and payload decode; does not reserve or redeem ledger supply |
| `mint(Player, String, int)` | Must be invoked on the server thread; durable administrative-style mint |
| `openLevels(Player)` | Server thread; opens the custom-holder level GUI |
| `sellAll(Player)` | Server thread; begins an asynchronous, inventory-leased sale |
| `rankUp(Player)` | Server thread; begins the atomic rank-up flow |

Do not block the server thread with `profile(...).join()` or `get()`. Continue asynchronously, then schedule Bukkit
world or inventory work back onto the server thread. `inspect` confirms the cryptographic payload, but only a sale or
exchange transaction can prove that unredeemed quantity remains in the database ledger.

## Events

Prepare events are synchronous and cancellable. Completion events are fired on the server thread after the durable
operation reaches its documented stage.

- `HeadMintEvent` permits cancellation or a quantity change from 1 through 64 before a kill-derived mob mint.
- `AuthenticatedHeadMintedEvent` reports a committed authenticated batch.
- `HeadSalePrepareEvent` exposes immutable selected sale lines before reservation.
- `HeadSoldEvent` reports a finalized sale and credited totals.
- `HeadRankUpEvent` exposes the current profile and level before rank-up.
- `HeadRankedUpEvent` reports the committed next profile.
- `HeadExchangePrepareEvent` exposes the recipe and immutable selected lines before reservation.
- `HeadExchangedEvent` reports a finalized exchange.

Listeners must not retain Bukkit objects for off-thread access. Event records and domain values can be copied for later
processing. Cancelling an event is not an authorization to mutate the plugin database independently.

## Compatibility contract

The API follows semantic versioning from 1.0.0. Additive methods and events may appear in minor versions; incompatible
signature changes require a major version. Internal storage, service, configuration, and item-codec packages are not
public API. PDC keys and SQL tables are implementation details and must not be written by integrations.

The built-in wallet is the authoritative atomic economy. An external bridge must accept that calls into another
plugin cannot share the same SQLite transaction. Prefer observing completion events and using stable idempotency keys
in the external system.
