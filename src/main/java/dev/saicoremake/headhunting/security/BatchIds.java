package dev.saicoremake.headhunting.security;

import dev.saicoremake.headhunting.domain.HeadDefinition;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BatchIds {
    private BatchIds() {
    }

    public static UUID mobBatch(UUID recipient, HeadDefinition definition, long bucket) {
        String canonical = recipient + "\n" + definition.key() + "\n"
                + definition.unitValue().minorUnits() + "\n" + definition.progressPoints() + "\n" + bucket;
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID playerBatch(UUID deathId) {
        return deathId;
    }
}
