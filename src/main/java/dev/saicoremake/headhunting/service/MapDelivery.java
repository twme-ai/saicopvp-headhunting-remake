package dev.saicoremake.headhunting.service;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class MapDelivery {
    private MapDelivery() {
    }

    static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> remaining = player.getInventory().addItem(item);
        for (ItemStack overflow : remaining.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }
}
