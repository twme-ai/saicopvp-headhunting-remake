package dev.saicoremake.headhunting.service;

import dev.saicoremake.headhunting.api.event.HeadExchangePrepareEvent;
import dev.saicoremake.headhunting.api.event.HeadExchangedEvent;
import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.config.ExchangeRecipe;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.item.DecodedHead;
import dev.saicoremake.headhunting.item.HeadDecodeStatus;
import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.ExchangeReservation;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.SaleLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadExchangeService {
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final HeadStore store;
    private final PlayerSessionService sessions;
    private final HeadItemCodec itemCodec;
    private final TranslationService translations;
    private final InventoryTransactionLock transactionLock;
    private final RewardDeliveryService rewards;
    private final Logger logger;

    public HeadExchangeService(
            JavaPlugin plugin,
            ConfigurationService configuration,
            HeadStore store,
            PlayerSessionService sessions,
            HeadItemCodec itemCodec,
            TranslationService translations,
            InventoryTransactionLock transactionLock,
            RewardDeliveryService rewards
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.transactionLock = Objects.requireNonNull(transactionLock, "transactionLock");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.logger = plugin.getLogger();
    }

    public void exchange(Player player, String recipeKey) {
        ExchangeRecipe recipe = configuration.current().exchanges().get(recipeKey);
        if (recipe == null) {
            send(player, "error.internal");
            return;
        }
        InventoryTransactionLock.Lease lease = transactionLock.tryAcquire(player.getUniqueId());
        if (lease == null) {
            send(player, "error.busy");
            return;
        }
        PlayerProfile profile = sessions.profile(player.getUniqueId());
        if (profile == null) {
            sessions.ensureLoaded(player).whenComplete((loaded, failure) -> schedule(() -> {
                if (failure != null || !player.isOnline()) {
                    lease.close();
                    if (failure != null) {
                        reportFailure(player, "Could not load exchange profile", failure);
                    }
                } else {
                    exchangeLoaded(player, loaded, recipe, lease);
                }
            }));
        } else {
            exchangeLoaded(player, profile, recipe, lease);
        }
    }

    public java.util.concurrent.CompletableFuture<Void> recover(Player player) {
        java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
        InventoryTransactionLock.Lease lease = transactionLock.tryAcquire(player.getUniqueId());
        if (lease == null) {
            completion.complete(null);
            return completion;
        }
        store.findReservedExchanges(player.getUniqueId()).whenComplete((exchanges, failure) -> schedule(() -> {
            if (failure != null) {
                lease.close();
                reportFailure(player, "Could not load pending exchanges", failure);
                completion.completeExceptionally(failure);
            } else {
                recoverNext(player, exchanges, 0, lease, completion);
            }
        }));
        return completion;
    }

    private void exchangeLoaded(
            Player player,
            PlayerProfile profile,
            ExchangeRecipe recipe,
            InventoryTransactionLock.Lease lease
    ) {
        if (profile.souls() < recipe.soulCost()) {
            lease.close();
            send(
                    player,
                    "exchange.insufficient-souls",
                    Placeholder.unparsed("amount", Long.toString(recipe.soulCost()))
            );
            return;
        }
        ExchangePlan plan = select(player, recipe);
        if (plan == null) {
            lease.close();
            send(player, "exchange.insufficient-heads");
            return;
        }
        HeadExchangePrepareEvent event = new HeadExchangePrepareEvent(player, recipe, plan.lines());
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            lease.close();
            return;
        }
        UUID exchangeId = UUID.randomUUID();
        store.reserveExchange(exchangeId, player.getUniqueId(), recipe, plan.lines())
                .whenComplete((reservation, failure) -> schedule(() -> {
                    if (failure != null) {
                        lease.close();
                        if (isSupplyFailure(failure)) {
                            send(player, "error.duplicate-head");
                        } else {
                            reportFailure(player, "Could not reserve head exchange", failure);
                        }
                        return;
                    }
                    removeAndFinalize(player, plan, reservation, lease);
                }));
    }

    private void removeAndFinalize(
            Player player,
            ExchangePlan plan,
            ExchangeReservation reservation,
            InventoryTransactionLock.Lease lease
    ) {
        if (!player.isOnline() || !unchanged(player, plan.slots())) {
            store.cancelExchange(reservation.exchangeId()).whenComplete((profile, failure) -> schedule(() -> {
                lease.close();
                if (failure == null) {
                    sessions.update(profile);
                } else {
                    reportFailure(player, "Could not cancel changed exchange", failure);
                }
            }));
            return;
        }
        for (SelectedSlot selected : plan.slots()) {
            ItemStack current = Objects.requireNonNull(player.getInventory().getItem(selected.slot()));
            if (current.getAmount() == selected.quantity()) {
                player.getInventory().setItem(selected.slot(), null);
            } else {
                current.setAmount(current.getAmount() - selected.quantity());
                player.getInventory().setItem(selected.slot(), current);
            }
        }
        store.finalizeExchange(reservation.exchangeId()).whenComplete((profile, failure) -> schedule(() -> {
            if (failure != null) {
                lease.close();
                reportFailure(player, "Could not finalize exchange; recovery is pending", failure);
                return;
            }
            sessions.update(profile);
            send(
                    player,
                    "exchange.success",
                    Placeholder.unparsed("reward", reservation.reward().id())
            );
            plugin.getServer().getPluginManager().callEvent(new HeadExchangedEvent(player, reservation));
            rewards.deliverPending(player, lease).exceptionally(rewardFailure -> null);
        }));
    }

    private ExchangePlan select(Player player, ExchangeRecipe recipe) {
        ItemStack[] contents = Objects.requireNonNull(player.getInventory().getStorageContents());
        List<SelectedSlot> selected = new ArrayList<>();
        List<SaleLine> lines = new ArrayList<>();
        for (Map.Entry<String, Long> cost : recipe.headCosts().entrySet()) {
            long remaining = cost.getValue();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack item = contents[slot];
                if (item == null) {
                    continue;
                }
                DecodedHead decoded = itemCodec.decode(item);
                if (decoded.status() != HeadDecodeStatus.VALID
                        || !decoded.payload().headKey().equals(cost.getKey())) {
                    continue;
                }
                int quantity = (int) Math.min(item.getAmount(), remaining);
                selected.add(new SelectedSlot(slot, item.clone(), decoded, quantity));
                lines.add(new SaleLine(decoded.payload(), quantity, false));
                remaining -= quantity;
            }
            if (remaining > 0) {
                return null;
            }
        }
        return new ExchangePlan(selected, lines);
    }

    private boolean unchanged(Player player, List<SelectedSlot> slots) {
        for (SelectedSlot selected : slots) {
            ItemStack current = player.getInventory().getItem(selected.slot());
            if (current == null
                    || current.getAmount() != selected.snapshot().getAmount()
                    || !current.isSimilar(selected.snapshot())) {
                return false;
            }
            DecodedHead decoded = itemCodec.decode(current);
            if (decoded.status() != HeadDecodeStatus.VALID
                    || !decoded.payload().batchId().equals(selected.decoded().payload().batchId())) {
                return false;
            }
        }
        return true;
    }

    private void recoverNext(
            Player player,
            List<ExchangeReservation> exchanges,
            int index,
            InventoryTransactionLock.Lease lease,
            java.util.concurrent.CompletableFuture<Void> completion
    ) {
        if (!player.isOnline() || index >= exchanges.size()) {
            rewards.deliverPending(player, lease).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
            });
            return;
        }
        ExchangeReservation exchange = exchanges.get(index);
        if (containsEveryReservedItem(player, exchange)) {
            store.cancelExchange(exchange.exchangeId()).whenComplete((profile, failure) -> schedule(() -> {
                if (failure != null) {
                    lease.close();
                    reportFailure(player, "Could not cancel recovered exchange", failure);
                    completion.completeExceptionally(failure);
                    return;
                }
                sessions.update(profile);
                send(player, "exchange.recovery-cancelled");
                recoverNext(player, exchanges, index + 1, lease, completion);
            }));
        } else {
            store.finalizeExchange(exchange.exchangeId()).whenComplete((profile, failure) -> schedule(() -> {
                if (failure != null) {
                    lease.close();
                    reportFailure(player, "Could not finalize recovered exchange", failure);
                    completion.completeExceptionally(failure);
                    return;
                }
                sessions.update(profile);
                send(player, "exchange.recovery-finalized");
                recoverNext(player, exchanges, index + 1, lease, completion);
            }));
        }
    }

    private boolean containsEveryReservedItem(Player player, ExchangeReservation exchange) {
        Map<UUID, Integer> quantities = new HashMap<>();
        ItemStack[] contents = Objects.requireNonNull(player.getInventory().getStorageContents());
        for (ItemStack item : contents) {
            if (item == null) {
                continue;
            }
            DecodedHead decoded = itemCodec.decode(item);
            if (decoded.status() == HeadDecodeStatus.VALID) {
                quantities.merge(decoded.payload().batchId(), item.getAmount(), Math::addExact);
            }
        }
        return exchange.lines().stream().allMatch(line ->
                quantities.getOrDefault(line.payload().batchId(), 0) >= line.quantity()
        );
    }

    private void send(Player player, String key, TagResolver... tags) {
        if (player.isOnline()) {
            player.sendMessage(translations.render(sessions.locale(player), key, tags));
        }
    }

    private void reportFailure(Player player, String message, Throwable failure) {
        logger.log(Level.SEVERE, message, failure);
        send(player, "error.internal");
    }

    private void schedule(Runnable runnable) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private static boolean isSupplyFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("unredeemed quantity")
                    || message.contains("never minted")
                    || message.contains("metadata does not match"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record SelectedSlot(int slot, ItemStack snapshot, DecodedHead decoded, int quantity) {
    }

    private record ExchangePlan(List<SelectedSlot> slots, List<SaleLine> lines) {
        private ExchangePlan {
            slots = List.copyOf(slots);
            lines = List.copyOf(lines);
        }
    }
}
