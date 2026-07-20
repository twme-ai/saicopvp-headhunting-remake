package dev.saicoremake.headhunting.api.event;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import java.util.Objects;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class HeadMintEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player recipient;
    private final LivingEntity source;
    private final HeadDefinition definition;
    private boolean cancelled;
    private int quantity;

    public HeadMintEvent(Player recipient, LivingEntity source, HeadDefinition definition, int quantity) {
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.source = Objects.requireNonNull(source, "source");
        this.definition = Objects.requireNonNull(definition, "definition");
        setQuantity(quantity);
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player recipient() {
        return recipient;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public LivingEntity source() {
        return source;
    }

    public HeadDefinition definition() {
        return definition;
    }

    public int quantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        if (quantity < 1 || quantity > 64) {
            throw new IllegalArgumentException("Mint quantity must be between 1 and 64");
        }
        this.quantity = quantity;
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
