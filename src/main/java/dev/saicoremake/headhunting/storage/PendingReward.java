package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.RewardType;
import java.util.Objects;
import java.util.UUID;

public record PendingReward(
        String rewardKey,
        UUID playerId,
        int level,
        String rewardId,
        RewardType type,
        String value,
        long amount,
        String status,
        int attempts
) {
    public PendingReward {
        Objects.requireNonNull(rewardKey, "rewardKey");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
