package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.RewardDefinition;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ExchangeReservation(
        UUID exchangeId,
        UUID playerId,
        String recipeKey,
        List<SaleLine> lines,
        long soulCost,
        RewardDefinition reward
) {
    public ExchangeReservation {
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(recipeKey, "recipeKey");
        lines = List.copyOf(lines);
        Objects.requireNonNull(reward, "reward");
        if (lines.isEmpty() || soulCost < 0) {
            throw new IllegalArgumentException("Exchange reservation is invalid");
        }
    }
}
