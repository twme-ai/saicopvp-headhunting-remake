package dev.saicoremake.headhunting.security;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HeadSigner {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;
    private final byte[] secret;

    public HeadSigner(byte[] secret) {
        if (secret == null || secret.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException("The signing secret must contain at least 32 bytes");
        }
        this.secret = Arrays.copyOf(secret, secret.length);
    }

    public HeadPayload sign(HeadPayload unsignedPayload) {
        return unsignedPayload.withSignature(mac(canonicalBytes(unsignedPayload)));
    }

    public boolean verify(HeadPayload payload) {
        byte[] signature = payload.signature();
        return signature.length == 32
                && MessageDigest.isEqual(signature, mac(canonicalBytes(payload)));
    }

    private byte[] mac(byte[] input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("The runtime does not provide HmacSHA256", exception);
        }
    }

    private static byte[] canonicalBytes(HeadPayload payload) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.writeInt(payload.schemaVersion());
                data.writeLong(payload.batchId().getMostSignificantBits());
                data.writeLong(payload.batchId().getLeastSignificantBits());
                writeString(data, payload.headKey());
                data.writeByte(payload.kind().ordinal());
                data.writeBoolean(payload.ownerId() != null);
                if (payload.ownerId() != null) {
                    data.writeLong(payload.ownerId().getMostSignificantBits());
                    data.writeLong(payload.ownerId().getLeastSignificantBits());
                }
                data.writeLong(payload.unitValueMinor());
                data.writeLong(payload.progressPoints());
                data.writeLong(payload.mintedBucket());
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected in-memory serialization failure", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
