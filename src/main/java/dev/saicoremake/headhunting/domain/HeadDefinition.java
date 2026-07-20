package dev.saicoremake.headhunting.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record HeadDefinition(
        String key,
        HeadKind kind,
        String entityType,
        String material,
        String textureUrl,
        String displayKey,
        Money unitValue,
        long progressPoints,
        double dropChance,
        int minimumLevel,
        long soulReward,
        Money directMoneyReward
) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    public HeadDefinition {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid head key: " + key);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(textureUrl, "textureUrl");
        Objects.requireNonNull(displayKey, "displayKey");
        Objects.requireNonNull(unitValue, "unitValue");
        Objects.requireNonNull(directMoneyReward, "directMoneyReward");
        if (progressPoints < 0 || soulReward < 0) {
            throw new IllegalArgumentException("Head rewards cannot be negative");
        }
        if (!Double.isFinite(dropChance) || dropChance < 0 || dropChance > 1) {
            throw new IllegalArgumentException("Drop chance must be between zero and one");
        }
        if (minimumLevel < 1) {
            throw new IllegalArgumentException("Minimum level must be positive");
        }
    }
}
