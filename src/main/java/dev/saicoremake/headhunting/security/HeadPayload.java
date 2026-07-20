package dev.saicoremake.headhunting.security;

import dev.saicoremake.headhunting.domain.HeadKind;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record HeadPayload(
        int schemaVersion,
        UUID batchId,
        String headKey,
        HeadKind kind,
        UUID ownerId,
        long unitValueMinor,
        long progressPoints,
        long mintedBucket,
        byte[] signature
) {
    public static final int CURRENT_SCHEMA = 1;
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    public HeadPayload {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(headKey, "headKey");
        Objects.requireNonNull(kind, "kind");
        signature = signature == null ? new byte[0] : Arrays.copyOf(signature, signature.length);
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported head schema: " + schemaVersion);
        }
        if (!KEY_PATTERN.matcher(headKey).matches()
                || unitValueMinor < 0 || progressPoints < 0 || mintedBucket < 0) {
            throw new IllegalArgumentException("Invalid signed head fields");
        }
        if (kind == HeadKind.PLAYER && ownerId == null) {
            throw new IllegalArgumentException("Player heads require an owner");
        }
    }

    @Override
    public byte[] signature() {
        return Arrays.copyOf(signature, signature.length);
    }

    public HeadPayload withSignature(byte[] newSignature) {
        return new HeadPayload(
                schemaVersion,
                batchId,
                headKey,
                kind,
                ownerId,
                unitValueMinor,
                progressPoints,
                mintedBucket,
                newSignature
        );
    }
}
