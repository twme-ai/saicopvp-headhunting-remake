package dev.saicoremake.headhunting.listener;

import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.service.RecoveryCoordinator;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerLifecycleListener implements Listener {
    private final JavaPlugin plugin;
    private final PlayerSessionService sessions;
    private final HeadItemCodec itemCodec;
    private final RecoveryCoordinator recovery;

    public PlayerLifecycleListener(
            JavaPlugin plugin,
            PlayerSessionService sessions,
            HeadItemCodec itemCodec,
            RecoveryCoordinator recovery
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        initializePlayer(event.getPlayer());
    }

    public void initializePlayer(Player player) {
        sessions.ensureLoaded(player).whenComplete((profile, failure) -> schedule(() -> {
            if (failure == null && player.isOnline()) {
                relocalize(player, sessions.locale(player));
                recovery.recover(player);
            }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocaleChange(PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        sessions.updateDetectedLocale(player, event.locale()).whenComplete((profile, failure) -> schedule(() -> {
            if (failure == null && player.isOnline()) {
                relocalize(player, sessions.locale(player));
            }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.unload(event.getPlayer().getUniqueId());
    }

    public void relocalize(Player player, Locale locale) {
        ItemStack[] contents = Objects.requireNonNull(player.getInventory().getContents());
        for (ItemStack item : contents) {
            if (item != null) {
                itemCodec.relocalize(item, locale);
            }
        }
    }

    private void schedule(Runnable runnable) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
