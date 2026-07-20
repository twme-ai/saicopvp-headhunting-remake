package dev.saicoremake.headhunting.config;

import dev.saicoremake.headhunting.domain.RewardDefinition;
import java.util.Map;
import java.util.Objects;

public record ExchangeRecipe(
        String key,
        String displayKey,
        Map<String, Long> headCosts,
        long soulCost,
        RewardDefinition reward
) {
    public ExchangeRecipe {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayKey, "displayKey");
        headCosts = Map.copyOf(headCosts);
        Objects.requireNonNull(reward, "reward");
        if (key.isBlank() || headCosts.isEmpty() || headCosts.values().stream().anyMatch(value -> value < 1)) {
            throw new IllegalArgumentException("Exchange recipe is invalid");
        }
        if (soulCost < 0) {
            throw new IllegalArgumentException("Exchange Soul cost cannot be negative");
        }
    }
}
