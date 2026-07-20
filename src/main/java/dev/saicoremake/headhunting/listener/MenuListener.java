package dev.saicoremake.headhunting.listener;

import dev.saicoremake.headhunting.gui.HeadMenuHolder;
import dev.saicoremake.headhunting.gui.HeadMenuService;
import dev.saicoremake.headhunting.gui.MenuButton;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {
    private final HeadMenuService menus;

    public MenuListener(HeadMenuService menus) {
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof HeadMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.viewerId())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        MenuButton button = holder.button(event.getRawSlot());
        if (button != null) {
            menus.handleButton(player, button);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof HeadMenuHolder) {
            event.setCancelled(true);
        }
    }
}
