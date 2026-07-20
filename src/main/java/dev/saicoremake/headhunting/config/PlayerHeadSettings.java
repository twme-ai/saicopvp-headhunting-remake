package dev.saicoremake.headhunting.config;

import java.math.BigDecimal;

public record PlayerHeadSettings(
        boolean enabled,
        BigDecimal balanceFraction,
        long minimumVictimBalanceMinor,
        long maximumValueMinor,
        boolean deductFromVictim,
        double dropChance,
        long pairCooldownSeconds,
        long victimCooldownSeconds,
        boolean blockSameAddress
) {
    private static final long MAXIMUM_COOLDOWN_SECONDS = 315_360_000L;

    public PlayerHeadSettings {
        if (balanceFraction == null || balanceFraction.signum() < 0 || balanceFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Player head balance fraction must be between zero and one");
        }
        if (minimumVictimBalanceMinor < 0 || maximumValueMinor < 0) {
            throw new IllegalArgumentException("Player head value limits cannot be negative");
        }
        if (!Double.isFinite(dropChance) || dropChance < 0 || dropChance > 1) {
            throw new IllegalArgumentException("Player head drop chance must be between zero and one");
        }
        if (pairCooldownSeconds < 0 || victimCooldownSeconds < 0) {
            throw new IllegalArgumentException("Player head cooldowns cannot be negative");
        }
        if (pairCooldownSeconds > MAXIMUM_COOLDOWN_SECONDS
                || victimCooldownSeconds > MAXIMUM_COOLDOWN_SECONDS) {
            throw new IllegalArgumentException("Player head cooldowns cannot exceed ten years");
        }
    }
}
