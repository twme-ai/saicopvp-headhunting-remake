package dev.saicoremake.headhunting.service;

import dev.saicoremake.headhunting.api.event.HeadSalePrepareEvent;
import dev.saicoremake.headhunting.api.event.HeadSoldEvent;
import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.domain.ProgressionMode;
import dev.saicoremake.headhunting.item.DecodedHead;
import dev.saicoremake.headhunting.item.HeadDecodeStatus;
import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.SaleLine;
import dev.saicoremake.headhunting.storage.SaleReservation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadSaleService {
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final HeadStore store;
    private final PlayerSessionService sessions;
    private final HeadItemCodec itemCodec;
    private final TranslationService translations;
    private final Logger logger;
    private final InventoryTransactionLock transactionLock;
    private final Map<UUID, InventoryTransactionLock.Lease> leases = new ConcurrentHashMap<>();

    public HeadSaleService(
            JavaPlugin plugin,
            ConfigurationService configuration,
            HeadStore store,
            PlayerSessionService sessions,
            HeadItemCodec itemCodec,
            TranslationService translations,
            InventoryTransactionLock transactionLock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.transactionLock = Objects.requireNonNull(transactionLock, "transactionLock");
        this.logger = plugin.getLogger();
    }

    public void sell(Player player, SaleScope scope) {
        UUID playerId = player.getUniqueId();
        if (!acquire(playerId)) {
            send(player, "error.busy");
            return;
        }
        PlayerProfile profile = sessions.profile(playerId);
        if (profile != null) {
            prepare(player, profile, scope);
            return;
        }
        sessions.ensureLoaded(player).whenComplete((loaded, failure) -> schedule(() -> {
            if (failure != null || !player.isOnline()) {
                releaseLease(playerId);
                if (failure != null) {
                    reportFailure(playerId, "Could not load profile for sale", failure);
                }
                return;
            }
            prepare(player, loaded, scope);
        }));
    }

    public boolean isActive(UUID playerId) {
        return leases.containsKey(playerId);
    }

    public boolean isAuthentic(ItemStack item) {
        return itemCodec.decode(item).status() == HeadDecodeStatus.VALID;
    }

    public CompletableFuture<Void> recover(Player player) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        UUID playerId = player.getUniqueId();
        if (!acquire(playerId)) {
            completion.complete(null);
            return completion;
        }
        store.findReservedSales(playerId).whenComplete((sales, failure) -> schedule(() -> {
            if (failure != null) {
                releaseLease(playerId);
                reportFailure(playerId, "Could not recover pending sales", failure);
                completion.completeExceptionally(failure);
                return;
            }
            if (!player.isOnline() || sales.isEmpty()) {
                releaseLease(playerId);
                completion.complete(null);
                return;
            }
            List<CompletableFuture<?>> recoveries = new ArrayList<>();
            for (SaleReservation sale : sales) {
                if (containsEveryReservedItem(player, sale)) {
                    recoveries.add(store.cancelSale(sale.saleId()).whenComplete((ignored, cancelFailure) -> {
                        if (cancelFailure == null) {
                            schedule(() -> send(player, "sell.recovery-cancelled"));
                        }
                    }));
                } else {
                    recoveries.add(store.finalizeSale(sale.saleId()).whenComplete((profile, finalizeFailure) -> {
                        if (finalizeFailure == null) {
                            sessions.update(profile);
                            schedule(() -> send(player, "sell.recovery-finalized"));
                        }
                    }));
                }
            }
            CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, recoveryFailure) -> {
                        releaseLease(playerId);
                        if (recoveryFailure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(recoveryFailure);
                        }
                    });
        }));
        return completion;
    }

    public void release(UUID playerId) {
        releaseLease(playerId);
    }

    private boolean acquire(UUID playerId) {
        InventoryTransactionLock.Lease lease = transactionLock.tryAcquire(playerId);
        if (lease == null) {
            return false;
        }
        leases.put(playerId, lease);
        return true;
    }

    private void releaseLease(UUID playerId) {
        InventoryTransactionLock.Lease lease = leases.remove(playerId);
        if (lease != null) {
            lease.close();
        }
    }

    private void prepare(Player player, PlayerProfile profile, SaleScope scope) {
        SalePlan plan = select(player, profile, scope);
        if (plan.selectedSlots().isEmpty()) {
            releaseLease(player.getUniqueId());
            return;
        }
        HeadSalePrepareEvent event = new HeadSalePrepareEvent(player, plan.lines());
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            releaseLease(player.getUniqueId());
            return;
        }
        send(
                player,
                "sell.preparing",
                Placeholder.unparsed("quantity", translations.formatDecimal(sessions.locale(player), plan.quantity()))
        );
        UUID saleId = UUID.randomUUID();
        UUID playerId = player.getUniqueId();
        store.reserveSale(saleId, playerId, plan.lines()).whenComplete((reservation, failure) -> schedule(() -> {
            if (failure != null) {
                releaseLease(playerId);
                if (isSupplyFailure(failure)) {
                    send(player, "error.duplicate-head");
                } else {
                    reportFailure(playerId, "Could not reserve head sale", failure);
                }
                return;
            }
            removeAndFinalize(player, plan, reservation);
        }));
    }

    private SalePlan select(Player player, PlayerProfile profile, SaleScope scope) {
        List<SelectedSlot> selected = new ArrayList<>();
        boolean foundInvalid = false;
        boolean foundLocked = false;
        int lockedLevel = 1;
        int firstSlot = scope == SaleScope.HELD ? player.getInventory().getHeldItemSlot() : 0;
        ItemStack[] storageContents = Objects.requireNonNull(player.getInventory().getStorageContents());
        int lastSlot = scope == SaleScope.HELD ? firstSlot + 1 : storageContents.length;
        for (int slot = firstSlot; slot < lastSlot; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            DecodedHead decoded = itemCodec.decode(item);
            if (decoded.status() == HeadDecodeStatus.NOT_A_HEAD) {
                continue;
            }
            if (decoded.status() == HeadDecodeStatus.INVALID) {
                foundInvalid = true;
                continue;
            }
            HeadDefinition definition = configuration.current().heads().get(decoded.payload().headKey());
            int minimumLevel = definition == null ? 1 : definition.minimumLevel();
            if (profile.level() < minimumLevel) {
                foundLocked = true;
                lockedLevel = Math.max(lockedLevel, minimumLevel);
                continue;
            }
            ItemStack snapshot = Objects.requireNonNull(item).clone();
            selected.add(new SelectedSlot(slot, snapshot, decoded, item.getAmount()));
        }
        if (selected.isEmpty()) {
            if (foundInvalid) {
                send(player, "error.forged-head");
            } else if (foundLocked) {
                send(player, "error.locked-head", Placeholder.unparsed("level", Integer.toString(lockedLevel)));
            } else {
                send(player, scope == SaleScope.HELD ? "sell.nothing-hand" : "sell.nothing-all");
            }
            return new SalePlan(List.of(), List.of(), 0);
        }
        List<SaleLine> lines = selected.stream().map(slot -> new SaleLine(
                slot.decoded().payload(),
                slot.quantity(),
                earnsProgress(profile, slot.decoded().payload().headKey())
        )).toList();
        int quantity = selected.stream().mapToInt(SelectedSlot::quantity).sum();
        return new SalePlan(selected, lines, quantity);
    }

    private boolean earnsProgress(PlayerProfile profile, String headKey) {
        return configuration.current().progressionMode() == ProgressionMode.SELL_HEADS
                && !profile.completed()
                && configuration.current().level(profile.level()).progressHeadKeys().contains(headKey);
    }

    private void removeAndFinalize(Player player, SalePlan plan, SaleReservation reservation) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || !slotsAreUnchanged(player, plan.selectedSlots())) {
            store.cancelSale(reservation.saleId()).whenComplete((ignored, failure) -> schedule(() -> {
                releaseLease(playerId);
                if (failure != null) {
                    reportFailure(playerId, "Could not cancel changed head sale", failure);
                } else if (player.isOnline()) {
                    send(player, "sell.failed-unchanged");
                }
            }));
            return;
        }
        for (SelectedSlot selected : plan.selectedSlots()) {
            player.getInventory().setItem(selected.slot(), null);
        }
        store.finalizeSale(reservation.saleId()).whenComplete((profile, failure) -> schedule(() -> {
            releaseLease(playerId);
            if (failure != null) {
                reportFailure(playerId, "Could not finalize head sale; recovery is pending", failure);
                return;
            }
            sessions.update(profile);
            if (player.isOnline()) {
                send(
                        player,
                        "sell.success",
                        Placeholder.unparsed(
                                "quantity",
                                translations.formatDecimal(sessions.locale(player), plan.quantity())
                        ),
                        Placeholder.unparsed(
                                "value",
                                translations.formatMoney(sessions.locale(player), reservation.gross().minorUnits())
                        ),
                        Placeholder.unparsed(
                                "progress",
                                translations.formatDecimal(sessions.locale(player), reservation.progressDelta())
                        )
                );
                plugin.getServer().getPluginManager().callEvent(new HeadSoldEvent(player, reservation));
            }
        }));
    }

    private boolean slotsAreUnchanged(Player player, List<SelectedSlot> selectedSlots) {
        for (SelectedSlot selected : selectedSlots) {
            ItemStack current = player.getInventory().getItem(selected.slot());
            if (current == null
                    || current.getAmount() != selected.quantity()
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

    private boolean containsEveryReservedItem(Player player, SaleReservation reservation) {
        Map<UUID, Integer> inventoryQuantities = new HashMap<>();
        ItemStack[] contents = Objects.requireNonNull(player.getInventory().getStorageContents());
        for (ItemStack item : contents) {
            if (item == null) {
                continue;
            }
            DecodedHead decoded = itemCodec.decode(item);
            if (decoded.status() == HeadDecodeStatus.VALID) {
                inventoryQuantities.merge(decoded.payload().batchId(), item.getAmount(), Math::addExact);
            }
        }
        return reservation.lines().stream().allMatch(line ->
                inventoryQuantities.getOrDefault(line.payload().batchId(), 0) >= line.quantity()
        );
    }

    private void send(Player player, String key, TagResolver... tags) {
        player.sendMessage(translations.render(sessions.locale(player), key, tags));
    }

    private void schedule(Runnable runnable) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private void reportFailure(UUID playerId, String message, Throwable failure) {
        logger.log(Level.SEVERE, message, failure);
        if (!plugin.isEnabled()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            send(player, "error.internal");
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

    private record SalePlan(List<SelectedSlot> selectedSlots, List<SaleLine> lines, int quantity) {
        private SalePlan {
            selectedSlots = List.copyOf(selectedSlots);
            lines = List.copyOf(lines);
        }
    }
}
