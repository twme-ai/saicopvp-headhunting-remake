package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.security.HeadPayload;
import java.util.Objects;

public record PendingDelivery(HeadPayload payload, int quantity, DeliveryTarget target) {
    public PendingDelivery {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(target, "target");
        if (quantity < 1) {
            throw new IllegalArgumentException("Delivery quantity must be positive");
        }
    }
}
