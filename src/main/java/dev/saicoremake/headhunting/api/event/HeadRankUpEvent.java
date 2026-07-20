package dev.saicoremake.headhunting.api.event;

import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class HeadRankUpEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final PlayerProfile profile;
    private final LevelDefinition level;
    private boolean cancelled;

    public HeadRankUpEvent(Player player, PlayerProfile profile, LevelDefinition level) {
        this.player = Objects.requireNonNull(player, "player");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.level = Objects.requireNonNull(level, "level");
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player player() {
        return player;
    }

    public PlayerProfile profile() {
        return profile;
    }

    public LevelDefinition level() {
        return level;
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
