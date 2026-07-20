package dev.saicoremake.headhunting.config;

import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.ProgressionMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public record PluginSettings(
        ProgressionMode progressionMode,
        long batchWindowSeconds,
        int maximumPendingMints,
        boolean rightClickSells,
        boolean sellSignsEnabled,
        Set<String> allowedWorlds,
        Set<String> blockedWorlds,
        Set<CreatureSpawnEvent.SpawnReason> allowedSpawnReasons,
        Set<EntityDamageEvent.DamageCause> allowedDamageCauses,
        PlayerHeadSettings playerHeads,
        Locale defaultLocale,
        List<Locale> supportedLocales,
        Map<String, HeadDefinition> heads,
        List<LevelDefinition> levels,
        Map<String, ExchangeRecipe> exchanges
) {
    public PluginSettings {
        Objects.requireNonNull(progressionMode, "progressionMode");
        allowedWorlds = normalizedWorlds(allowedWorlds);
        blockedWorlds = normalizedWorlds(blockedWorlds);
        allowedSpawnReasons = Set.copyOf(allowedSpawnReasons);
        allowedDamageCauses = Set.copyOf(allowedDamageCauses);
        Objects.requireNonNull(playerHeads, "playerHeads");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        supportedLocales = List.copyOf(supportedLocales);
        heads = Map.copyOf(heads);
        levels = List.copyOf(levels);
        exchanges = Map.copyOf(exchanges);
        if (batchWindowSeconds < 1 || maximumPendingMints < 1) {
            throw new IllegalArgumentException("Mint queue settings must be positive");
        }
        if (supportedLocales.isEmpty() || !supportedLocales.contains(defaultLocale)) {
            throw new IllegalArgumentException("The default locale must be supported");
        }
        if (heads.isEmpty() || levels.isEmpty()) {
            throw new IllegalArgumentException("Head and level definitions cannot be empty");
        }
    }

    public LevelDefinition level(int levelNumber) {
        if (levelNumber < 1 || levelNumber > levels.size()) {
            throw new IllegalArgumentException("Unknown level: " + levelNumber);
        }
        return levels.get(levelNumber - 1);
    }

    public boolean isWorldAllowed(String worldName) {
        String normalized = worldName.toLowerCase(Locale.ROOT);
        return !blockedWorlds.contains(normalized)
                && (allowedWorlds.isEmpty() || allowedWorlds.contains(normalized));
    }

    @Override
    public Set<String> allowedWorlds() {
        return Set.copyOf(allowedWorlds);
    }

    @Override
    public Set<String> blockedWorlds() {
        return Set.copyOf(blockedWorlds);
    }

    private static Set<String> normalizedWorlds(Set<String> worlds) {
        Set<String> normalized = worlds.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return Set.copyOf(normalized);
    }
}
