package dev.saicoremake.headhunting.listener;

import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.service.HeadSaleService;
import dev.saicoremake.headhunting.service.InventoryTransactionLock;
import dev.saicoremake.headhunting.service.SaleScope;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class SaleProtectionListener implements Listener {
    private final HeadSaleService saleService;
    private final ConfigurationService configuration;
    private final InventoryTransactionLock transactionLock;

    public SaleProtectionListener(
            HeadSaleService saleService,
            ConfigurationService configuration,
            InventoryTransactionLock transactionLock
    ) {
        this.saleService = Objects.requireNonNull(saleService, "saleService");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.transactionLock = Objects.requireNonNull(transactionLock, "transactionLock");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (transactionLock.isLocked(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (transactionLock.isLocked(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (transactionLock.isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (transactionLock.isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && transactionLock.isLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (!configuration.current().rightClickSells() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!event.getAction().isRightClick() || !saleService.isAuthentic(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        saleService.sell(event.getPlayer(), SaleScope.HELD);
    }
}
