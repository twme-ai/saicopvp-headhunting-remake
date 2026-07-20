package dev.saicoremake.headhunting.api.event;

import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class HeadRankedUpEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final PlayerProfile previousProfile;
    private final PlayerProfile currentProfile;
    private final LevelDefinition completedLevel;

    public HeadRankedUpEvent(
            Player player,
            PlayerProfile previousProfile,
            PlayerProfile currentProfile,
            LevelDefinition completedLevel
    ) {
        this.player = Objects.requireNonNull(player, "player");
        this.previousProfile = Objects.requireNonNull(previousProfile, "previousProfile");
        this.currentProfile = Objects.requireNonNull(currentProfile, "currentProfile");
        this.completedLevel = Objects.requireNonNull(completedLevel, "completedLevel");
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Bukkit events expose live server objects by design")
    public Player player() {
        return player;
    }

    public PlayerProfile previousProfile() {
        return previousProfile;
    }

    public PlayerProfile currentProfile() {
        return currentProfile;
    }

    public LevelDefinition completedLevel() {
        return completedLevel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
