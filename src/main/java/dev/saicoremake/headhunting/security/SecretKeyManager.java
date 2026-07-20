package dev.saicoremake.headhunting.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Set;

public final class SecretKeyManager {
    private static final int KEY_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public byte[] loadOrCreate(Path secretPath, Path databasePath) throws IOException {
        if (Files.exists(secretPath)) {
            byte[] secret = Files.readAllBytes(secretPath);
            if (secret.length != KEY_BYTES) {
                throw new IOException("Signing secret must contain exactly 32 bytes: " + secretPath);
            }
            return secret;
        }
        if (Files.exists(databasePath) && Files.size(databasePath) > 0) {
            throw new IOException("Signing secret is missing while an existing database is present");
        }
        Path parent = secretPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] secret = new byte[KEY_BYTES];
        SECURE_RANDOM.nextBytes(secret);
        Files.write(secretPath, secret, java.nio.file.StandardOpenOption.CREATE_NEW);
        restrictPermissions(secretPath);
        return secret;
    }

    private static void restrictPermissions(Path path) throws IOException {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) == null) {
            return;
        }
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
    }
}
