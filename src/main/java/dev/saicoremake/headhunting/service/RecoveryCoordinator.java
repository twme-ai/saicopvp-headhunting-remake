package dev.saicoremake.headhunting.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RecoveryCoordinator {
    private final JavaPlugin plugin;
    private final RewardDeliveryService rewards;
    private final HeadExchangeService exchanges;
    private final HeadSaleService sales;

    public RecoveryCoordinator(
            JavaPlugin plugin,
            RewardDeliveryService rewards,
            HeadExchangeService exchanges,
            HeadSaleService sales
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.sales = Objects.requireNonNull(sales, "sales");
    }

    public void recover(Player player) {
        rewards.deliverPending(player)
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> onMain(() -> exchanges.recover(player)))
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> onMain(() -> sales.recover(player)))
                .exceptionally(failure -> null);
    }

    private CompletableFuture<Void> onMain(Supplier<CompletableFuture<Void>> action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            result.complete(null);
            return result;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                action.get().whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }
}
