package dev.saicoremake.headhunting.listener;

import dev.saicoremake.headhunting.service.HeadMintService;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class DeathListener implements Listener {
    private final HeadMintService mintService;

    public DeathListener(HeadMintService mintService) {
        this.mintService = Objects.requireNonNull(mintService, "mintService");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        mintService.processMobDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        mintService.processPlayerDeath(event);
    }
}
