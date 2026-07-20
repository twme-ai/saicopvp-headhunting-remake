package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.security.HeadPayload;
import java.util.Objects;

public record SaleLine(HeadPayload payload, int quantity, boolean creditProgress) {
    public SaleLine {
        Objects.requireNonNull(payload, "payload");
        if (quantity < 1) {
            throw new IllegalArgumentException("Sale quantity must be positive");
        }
    }
}
