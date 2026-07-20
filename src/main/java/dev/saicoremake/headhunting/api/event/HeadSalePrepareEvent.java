package dev.saicoremake.headhunting.api.event;

import dev.saicoremake.headhunting.storage.SaleLine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class HeadSalePrepareEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final List<SaleLine> lines;
    private boolean cancelled;

    public HeadSalePrepareEvent(Player player, List<SaleLine> lines) {
        this.player = Objects.requireNonNull(player, "player");
        this.lines = List.copyOf(lines);
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player player() {
        return player;
    }

    public List<SaleLine> lines() {
        return List.copyOf(lines);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
