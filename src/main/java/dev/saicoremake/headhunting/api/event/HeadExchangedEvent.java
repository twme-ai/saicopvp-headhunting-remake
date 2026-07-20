package dev.saicoremake.headhunting.api.event;

import dev.saicoremake.headhunting.storage.ExchangeReservation;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class HeadExchangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ExchangeReservation exchange;

    public HeadExchangedEvent(Player player, ExchangeReservation exchange) {
        this.player = Objects.requireNonNull(player, "player");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player player() {
        return player;
    }

    public ExchangeReservation exchange() {
        return exchange;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
