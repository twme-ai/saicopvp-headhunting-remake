package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SaleReservation(UUID saleId, UUID playerId, List<SaleLine> lines, Money gross, long progressDelta) {
    public SaleReservation {
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(playerId, "playerId");
        lines = List.copyOf(lines);
        Objects.requireNonNull(gross, "gross");
        if (lines.isEmpty() || progressDelta < 0) {
            throw new IllegalArgumentException("Sale reservation is invalid");
        }
    }
}
