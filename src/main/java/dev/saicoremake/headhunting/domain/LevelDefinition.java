package dev.saicoremake.headhunting.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LevelDefinition(
        int number,
        boolean terminal,
        String tier,
        long requiredProgress,
        Money rankUpCost,
        Map<String, Long> killRequirements,
        List<String> progressHeadKeys,
        List<String> unlockedHeadKeys,
        List<RewardDefinition> rewards
) {
    public LevelDefinition {
        if (number < 1) {
            throw new IllegalArgumentException("Level number must be positive");
        }
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(rankUpCost, "rankUpCost");
        killRequirements = Map.copyOf(killRequirements);
        progressHeadKeys = List.copyOf(progressHeadKeys);
        unlockedHeadKeys = List.copyOf(unlockedHeadKeys);
        rewards = List.copyOf(rewards);
        if (requiredProgress < 0 || killRequirements.values().stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("Level requirements cannot be negative");
        }
    }

    public boolean isComplete(long progress, Map<String, Long> kills) {
        if (progress < requiredProgress) {
            return false;
        }
        return killRequirements.entrySet().stream()
                .allMatch(entry -> kills.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }
}
