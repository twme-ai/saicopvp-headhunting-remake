package dev.saicoremake.headhunting.storage;

import java.util.Objects;
import java.util.UUID;

public record DeliveryTarget(
        UUID deliveryId,
        UUID recipientId,
        String ownerName,
        UUID worldId,
        double x,
        double y,
        double z
) {
    public DeliveryTarget {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Delivery coordinates must be finite");
        }
    }
}
