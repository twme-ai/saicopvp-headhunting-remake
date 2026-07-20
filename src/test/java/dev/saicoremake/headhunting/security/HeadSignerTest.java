package dev.saicoremake.headhunting.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.saicoremake.headhunting.domain.HeadKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HeadSignerTest {
    private final HeadSigner signer = new HeadSigner(new byte[32]);

    @Test
    void verifiesAnUntamperedPayload() {
        HeadPayload signed = signer.sign(unsignedPayload());

        assertTrue(signer.verify(signed));
    }

    @Test
    void rejectsChangedEconomicFields() {
        HeadPayload signed = signer.sign(unsignedPayload());
        HeadPayload changed = new HeadPayload(
                signed.schemaVersion(),
                signed.batchId(),
                signed.headKey(),
                signed.kind(),
                signed.ownerId(),
                signed.unitValueMinor() + 1,
                signed.progressPoints(),
                signed.mintedBucket(),
                signed.signature()
        );

        assertFalse(signer.verify(changed));
    }

    @Test
    void rejectsAChangedOwner() {
        HeadPayload signed = signer.sign(unsignedPayload());
        HeadPayload changed = new HeadPayload(
                signed.schemaVersion(),
                signed.batchId(),
                signed.headKey(),
                signed.kind(),
                UUID.randomUUID(),
                signed.unitValueMinor(),
                signed.progressPoints(),
                signed.mintedBucket(),
                signed.signature()
        );

        assertFalse(signer.verify(changed));
    }

    private static HeadPayload unsignedPayload() {
        return new HeadPayload(
                HeadPayload.CURRENT_SCHEMA,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "zombie",
                HeadKind.PLAYER,
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                10_000,
                5,
                123,
                new byte[0]
        );
    }
}
