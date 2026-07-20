package dev.saicoremake.headhunting.service;

import dev.saicoremake.headhunting.domain.RewardType;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.PendingReward;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardDeliveryService {
    private final JavaPlugin plugin;
    private final HeadStore store;
    private final PlayerSessionService sessions;
    private final TranslationService translations;
    private final InventoryTransactionLock transactionLock;
    private final NamespacedKey rewardKey;
    private final Logger logger;

    public RewardDeliveryService(
            JavaPlugin plugin,
            HeadStore store,
            PlayerSessionService sessions,
            TranslationService translations,
            InventoryTransactionLock transactionLock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.transactionLock = Objects.requireNonNull(transactionLock, "transactionLock");
        this.rewardKey = new NamespacedKey(plugin, "reward_delivery");
        this.logger = plugin.getLogger();
    }

    public CompletableFuture<Void> deliverPending(Player player) {
        InventoryTransactionLock.Lease lease = transactionLock.tryAcquire(player.getUniqueId());
        if (lease == null) {
            return CompletableFuture.completedFuture(null);
        }
        return deliverPending(player, lease);
    }

    public CompletableFuture<Void> deliverPending(Player player, InventoryTransactionLock.Lease lease) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        UUID playerId = player.getUniqueId();
        store.findPendingRewards(playerId).whenComplete((rewards, failure) -> schedule(() -> {
            if (failure != null) {
                finishFailure(player, lease, completion, "Could not load pending rewards", failure);
                return;
            }
            processNext(player, rewards, 0, lease, completion);
        }));
        return completion;
    }

    private void processNext(
            Player player,
            List<PendingReward> rewards,
            int index,
            InventoryTransactionLock.Lease lease,
            CompletableFuture<Void> completion
    ) {
        if (!player.isOnline() || index >= rewards.size()) {
            lease.close();
            completion.complete(null);
            return;
        }
        PendingReward reward = rewards.get(index);
        if (reward.status().equals("PENDING")) {
            store.claimReward(reward.rewardKey(), reward.playerId()).whenComplete((claimed, failure) -> schedule(() -> {
                if (failure != null) {
                    finishFailure(player, lease, completion, "Could not claim reward", failure);
                } else {
                    deliverClaimed(player, rewards, index, claimed, lease, completion);
                }
            }));
        } else {
            deliverClaimed(player, rewards, index, reward, lease, completion);
        }
    }

    private void deliverClaimed(
            Player player,
            List<PendingReward> rewards,
            int index,
            PendingReward reward,
            InventoryTransactionLock.Lease lease,
            CompletableFuture<Void> completion
    ) {
        if (reward.type() == RewardType.ITEM) {
            deliverItem(player, rewards, index, reward, lease, completion);
        } else if (reward.type() == RewardType.COMMAND) {
            completeBeforeCommand(player, rewards, index, reward, lease, completion);
        } else {
            String error = "Unexpected outbox reward type: " + reward.type();
            store.failReward(reward.rewardKey(), reward.playerId(), error).whenComplete((ignored, failure) ->
                    schedule(() -> finishFailure(player, lease, completion, error, failure))
            );
        }
    }

    private void deliverItem(
            Player player,
            List<PendingReward> rewards,
            int index,
            PendingReward reward,
            InventoryTransactionLock.Lease lease,
            CompletableFuture<Void> completion
    ) {
        if (hasRewardToken(player, reward.rewardKey())) {
            completeAndContinue(player, rewards, index, reward, lease, completion);
            return;
        }
        Material material = Material.matchMaterial(reward.value());
        if (material == null || !material.isItem()) {
            String error = "Invalid reward material: " + reward.value();
            store.failReward(reward.rewardKey(), reward.playerId(), error).whenComplete((ignored, failure) ->
                    schedule(() -> finishFailure(player, lease, completion, error, failure))
            );
            return;
        }
        List<ItemStack> items = createRewardItems(player, reward, material);
        if (!canFit(player, items)) {
            store.resetReward(reward.rewardKey(), reward.playerId()).whenComplete((ignored, failure) -> schedule(() -> {
                lease.close();
                if (failure == null) {
                    player.sendMessage(translations.render(sessions.locale(player), "reward.inventory-full"));
                    completion.complete(null);
                } else {
                    finishFailure(player, lease, completion, "Could not reset full-inventory reward", failure);
                }
            }));
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.toArray(ItemStack[]::new));
        for (ItemStack item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        completeAndContinue(player, rewards, index, reward, lease, completion);
    }

    private void completeBeforeCommand(
            Player player,
            List<PendingReward> rewards,
            int index,
            PendingReward reward,
            InventoryTransactionLock.Lease lease,
            CompletableFuture<Void> completion
    ) {
        store.completeReward(reward.rewardKey(), reward.playerId()).whenComplete((ignored, failure) -> schedule(() -> {
            if (failure != null) {
                finishFailure(player, lease, completion, "Could not complete command reward", failure);
                return;
            }
            String command = reward.value().replace("{player}", player.getName());
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) {
                logger.warning("Reward command was not handled: " + command);
            }
            sendRewardMessage(player, reward);
            processNext(player, rewards, index + 1, lease, completion);
        }));
    }

    private void completeAndContinue(
            Player player,
            List<PendingReward> rewards,
            int index,
            PendingReward reward,
            InventoryTransactionLock.Lease lease,
            CompletableFuture<Void> completion
    ) {
        store.completeReward(reward.rewardKey(), reward.playerId()).whenComplete((ignored, failure) -> schedule(() -> {
            if (failure != null) {
                finishFailure(player, lease, completion, "Could not complete item reward", failure);
                return;
            }
            sendRewardMessage(player, reward);
            processNext(player, rewards, index + 1, lease, completion);
        }));
    }

    private List<ItemStack> createRewardItems(Player player, PendingReward reward, Material material) {
        List<ItemStack> items = new ArrayList<>();
        long remaining = reward.amount();
        int maximumStack = material.getMaxStackSize();
        while (remaining > 0) {
            int amount = (int) Math.min(maximumStack, remaining);
            ItemStack item = ItemStack.of(material, amount);
            boolean edited = item.editPersistentDataContainer(container ->
                    container.set(rewardKey, PersistentDataType.STRING, reward.rewardKey())
            );
            if (!edited) {
                throw new IllegalStateException("Could not tag reward item");
            }
            boolean metadataEdited = item.editMeta(meta -> meta.displayName(translations.render(
                    sessions.locale(player),
                    "item.reward-name",
                    Placeholder.unparsed("level", Integer.toString(reward.level()))
            )));
            if (!metadataEdited) {
                throw new IllegalStateException("Could not render reward item metadata");
            }
            items.add(item);
            remaining -= amount;
        }
        return List.copyOf(items);
    }

    private boolean hasRewardToken(Player player, String token) {
        ItemStack[] contents = Objects.requireNonNull(player.getInventory().getStorageContents());
        for (ItemStack item : contents) {
            if (item != null && token.equals(
                    item.getPersistentDataContainer().get(rewardKey, PersistentDataType.STRING)
            )) {
                return true;
            }
        }
        return false;
    }

    private static boolean canFit(Player player, List<ItemStack> items) {
        int required = items.stream().mapToInt(ItemStack::getAmount).sum();
        ItemStack prototype = Objects.requireNonNull(items.getFirst());
        int capacity = 0;
        ItemStack[] contents = Objects.requireNonNull(player.getInventory().getStorageContents());
        for (ItemStack existing : contents) {
            if (existing == null || existing.getType().isAir()) {
                capacity += prototype.getMaxStackSize();
            } else if (existing.isSimilar(prototype)) {
                capacity += Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (capacity >= required) {
                return true;
            }
        }
        return false;
    }

    private void sendRewardMessage(Player player, PendingReward reward) {
        player.sendMessage(translations.render(
                sessions.locale(player),
                "reward.received",
                Placeholder.unparsed("level", Integer.toString(reward.level())),
                Placeholder.unparsed("reward", reward.rewardId())
        ));
    }

    private void finishFailure(
            Player player,
            InventoryTransactionLock.Lease lease,
            CompletableFuture<Void> completion,
            String message,
            Throwable failure
    ) {
        lease.close();
        if (failure == null) {
            logger.severe(message);
        } else {
            logger.log(Level.SEVERE, message, failure);
        }
        if (player.isOnline()) {
            player.sendMessage(translations.render(sessions.locale(player), "reward.failed"));
        }
        completion.completeExceptionally(failure == null ? new IllegalStateException(message) : failure);
    }

    private void schedule(Runnable runnable) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
