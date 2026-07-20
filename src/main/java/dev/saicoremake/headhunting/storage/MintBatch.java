package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.security.HeadPayload;
import java.util.Objects;

public record MintBatch(HeadPayload payload, int quantity) {
    public MintBatch {
        Objects.requireNonNull(payload, "payload");
        if (quantity < 1) {
            throw new IllegalArgumentException("Mint quantity must be positive");
        }
    }
}
