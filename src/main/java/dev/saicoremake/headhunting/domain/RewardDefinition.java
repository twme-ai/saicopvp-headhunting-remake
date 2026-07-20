package dev.saicoremake.headhunting.domain;

import java.util.Objects;

public record RewardDefinition(String id, RewardType type, String value, long amount) {
    public RewardDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Reward id cannot be blank");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("Reward amount cannot be negative");
        }
    }
}
