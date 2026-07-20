package dev.saicoremake.headhunting.api.event;

import dev.saicoremake.headhunting.storage.SaleReservation;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class HeadSoldEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final SaleReservation sale;

    public HeadSoldEvent(Player player, SaleReservation sale) {
        this.player = Objects.requireNonNull(player, "player");
        this.sale = Objects.requireNonNull(sale, "sale");
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player player() {
        return player;
    }

    public SaleReservation sale() {
        return sale;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
