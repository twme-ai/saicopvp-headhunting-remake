package dev.saicoremake.headhunting.gui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HeadMenuHolder implements InventoryHolder {
    private final UUID viewerId;
    private final Inventory inventory;
    private final Map<Integer, MenuButton> buttons = new HashMap<>();

    public HeadMenuHolder(UUID viewerId, int size, Component title) {
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public UUID viewerId() {
        return viewerId;
    }

    public void setButton(int slot, org.bukkit.inventory.ItemStack item, MenuButton button) {
        inventory.setItem(slot, item);
        buttons.put(slot, button);
    }

    public MenuButton button(int slot) {
        return buttons.get(slot);
    }

    @Override
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "InventoryHolder must expose its live Bukkit inventory"
    )
    public Inventory getInventory() {
        return inventory;
    }
}
