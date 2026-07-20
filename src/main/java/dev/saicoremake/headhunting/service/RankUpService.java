package dev.saicoremake.headhunting.service;

import dev.saicoremake.headhunting.api.event.HeadRankUpEvent;
import dev.saicoremake.headhunting.api.event.HeadRankedUpEvent;
import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.RankUpResult;
import dev.saicoremake.headhunting.storage.RankUpStatus;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RankUpService {
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final HeadStore store;
    private final PlayerSessionService sessions;
    private final TranslationService translations;
    private final InventoryTransactionLock transactionLock;
    private final RewardDeliveryService rewards;
    private final Logger logger;

    public RankUpService(
            JavaPlugin plugin,
            ConfigurationService configuration,
            HeadStore store,
            PlayerSessionService sessions,
            TranslationService translations,
            InventoryTransactionLock transactionLock,
            RewardDeliveryService rewards
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.transactionLock = Objects.requireNonNull(transactionLock, "transactionLock");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.logger = plugin.getLogger();
    }

    public void rankUp(Player player) {
        UUID playerId = player.getUniqueId();
        InventoryTransactionLock.Lease lease = transactionLock.tryAcquire(playerId);
        if (lease == null) {
            send(player, "error.busy");
            return;
        }
        PlayerProfile profile = sessions.profile(playerId);
        if (profile != null) {
            rankUpLoaded(player, profile, lease);
            return;
        }
        sessions.ensureLoaded(player).whenComplete((loaded, failure) -> schedule(() -> {
            if (failure != null || !player.isOnline()) {
                lease.close();
                if (failure != null) {
                    reportFailure(player, "Could not load rank-up profile", failure);
                }
                return;
            }
            rankUpLoaded(player, loaded, lease);
        }));
    }

    private void rankUpLoaded(Player player, PlayerProfile profile, InventoryTransactionLock.Lease lease) {
        if (profile.completed()) {
            lease.close();
            send(player, "rankup.already-completed");
            return;
        }
        LevelDefinition level = configuration.current().level(profile.level());
        HeadRankUpEvent event = new HeadRankUpEvent(player, profile, level);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            lease.close();
            return;
        }
        store.rankUp(profile.playerId(), level).whenComplete((result, failure) -> schedule(() -> {
            if (failure != null) {
                lease.close();
                reportFailure(player, "Could not rank up player", failure);
                return;
            }
            handleResult(player, profile, level, result, lease);
        }));
    }

    private void handleResult(
            Player player,
            PlayerProfile previous,
            LevelDefinition level,
            RankUpResult result,
            InventoryTransactionLock.Lease lease
    ) {
        if (result.status() != RankUpStatus.SUCCESS) {
            lease.close();
            sendRankUpFailure(player, previous, level, result.status());
            return;
        }
        sessions.update(result.profile());
        if (result.profile().completed()) {
            send(player, "rankup.completed");
        } else {
            send(player, "rankup.success", Placeholder.unparsed("level", Integer.toString(result.profile().level())));
        }
        plugin.getServer().getPluginManager().callEvent(new HeadRankedUpEvent(
                player,
                previous,
                result.profile(),
                level
        ));
        rewards.deliverPending(player, lease).exceptionally(failure -> null);
    }

    private void sendRankUpFailure(
            Player player,
            PlayerProfile profile,
            LevelDefinition level,
            RankUpStatus status
    ) {
        switch (status) {
            case ALREADY_COMPLETED -> send(player, "rankup.already-completed");
            case LEVEL_CHANGED -> send(player, "rankup.changed");
            case INCOMPLETE -> {
                long remaining = Math.max(0, level.requiredProgress() - profile.progress());
                send(player, "rankup.incomplete", Placeholder.unparsed("remaining", Long.toString(remaining)));
            }
            case INSUFFICIENT_FUNDS -> send(
                    player,
                    "rankup.insufficient-funds",
                    Placeholder.unparsed(
                            "cost",
                            translations.formatMoney(sessions.locale(player), level.rankUpCost().minorUnits())
                    ),
                    Placeholder.unparsed(
                            "balance",
                            translations.formatMoney(sessions.locale(player), profile.balance().minorUnits())
                    )
            );
            default -> throw new IllegalStateException("Success must be handled before failure messages");
        }
    }

    private void send(Player player, String key, TagResolver... tags) {
        player.sendMessage(translations.render(sessions.locale(player), key, tags));
    }

    private void reportFailure(Player player, String message, Throwable failure) {
        logger.log(Level.SEVERE, message, failure);
        if (player.isOnline()) {
            send(player, "error.internal");
        }
    }

    private void schedule(Runnable runnable) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
