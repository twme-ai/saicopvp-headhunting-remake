package dev.saicoremake.headhunting.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record PlayerProfile(
        UUID playerId,
        String lastName,
        Locale detectedLocale,
        Locale localeOverride,
        int level,
        boolean completed,
        long progress,
        Money balance,
        long souls
) {
    public PlayerProfile {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(detectedLocale, "detectedLocale");
        Objects.requireNonNull(balance, "balance");
        if (level < 1 || progress < 0 || souls < 0) {
            throw new IllegalArgumentException("Profile counters are invalid");
        }
    }

    public Locale effectiveLocale() {
        return localeOverride == null ? detectedLocale : localeOverride;
    }
}
