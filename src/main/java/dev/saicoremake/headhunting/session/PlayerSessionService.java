package dev.saicoremake.headhunting.session;

import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.storage.HeadStore;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.bukkit.entity.Player;

public final class PlayerSessionService {
    private final HeadStore store;
    private final ConfigurationService configuration;
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PlayerProfile>> loading = new ConcurrentHashMap<>();
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();

    public PlayerSessionService(HeadStore store, ConfigurationService configuration) {
        this.store = Objects.requireNonNull(store, "store");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public CompletableFuture<PlayerProfile> ensureLoaded(Player player) {
        activePlayers.add(player.getUniqueId());
        PlayerProfile cached = profiles.get(player.getUniqueId());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        Locale detected = supportedLocale(player.locale());
        return loading.computeIfAbsent(playerId, ignored -> store
                .loadOrCreateProfile(playerId, playerName, detected)
                .whenComplete((profile, failure) -> {
                    loading.remove(playerId);
                    if (profile != null && activePlayers.contains(playerId)) {
                        profiles.put(playerId, profile);
                    }
                }));
    }

    public CompletableFuture<PlayerProfile> updateDetectedLocale(Player player) {
        return updateDetectedLocale(player, player.locale());
    }

    public CompletableFuture<PlayerProfile> updateDetectedLocale(Player player, Locale clientLocale) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        Locale detected = supportedLocale(clientLocale);
        return store.loadOrCreateProfile(playerId, playerName, detected)
                .thenApply(profile -> {
                    if (activePlayers.contains(playerId)) {
                        profiles.put(playerId, profile);
                    }
                    return profile;
                });
    }

    public CompletableFuture<PlayerProfile> setLocaleOverride(Player player, Locale locale) {
        UUID playerId = player.getUniqueId();
        return ensureLoaded(player)
                .thenCompose(ignored -> store.setLocaleOverride(playerId, locale))
                .thenApply(profile -> {
                    if (activePlayers.contains(profile.playerId())) {
                        profiles.put(profile.playerId(), profile);
                    }
                    return profile;
                });
    }

    public Locale locale(Player player) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        return profile == null ? supportedLocale(player.locale()) : supportedLocale(profile.effectiveLocale());
    }

    public PlayerProfile profile(UUID playerId) {
        return profiles.get(playerId);
    }

    public void update(PlayerProfile profile) {
        if (activePlayers.contains(profile.playerId())) {
            profiles.put(profile.playerId(), profile);
        }
    }

    public void unload(UUID playerId) {
        profiles.remove(playerId);
        loading.remove(playerId);
        activePlayers.remove(playerId);
    }

    public Locale supportedLocale(Locale requested) {
        return configuration.current().supportedLocales().stream()
                .filter(locale -> locale.equals(requested))
                .findFirst()
                .orElseGet(() -> configuration.current().supportedLocales().stream()
                        .filter(locale -> locale.getLanguage().equalsIgnoreCase(requested.getLanguage()))
                        .findFirst()
                        .orElse(configuration.current().defaultLocale()));
    }
}
