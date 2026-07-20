package dev.saicoremake.headhunting.api.event;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import dev.saicoremake.headhunting.security.HeadPayload;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AuthenticatedHeadMintedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player recipient;
    private final HeadPayload payload;
    private final int quantity;

    public AuthenticatedHeadMintedEvent(Player recipient, HeadPayload payload, int quantity) {
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.quantity = quantity;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player recipient() {
        return recipient;
    }

    public HeadPayload payload() {
        return payload;
    }

    public int quantity() {
        return quantity;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
