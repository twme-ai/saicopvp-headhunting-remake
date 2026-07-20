package dev.saicoremake.headhunting.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretKeyManagerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void createsAndReusesAThirtyTwoByteSecret() throws IOException {
        Path secretPath = temporaryDirectory.resolve("secret.key");
        Path databasePath = temporaryDirectory.resolve("data.db");
        SecretKeyManager manager = new SecretKeyManager();

        byte[] first = manager.loadOrCreate(secretPath, databasePath);
        byte[] second = manager.loadOrCreate(secretPath, databasePath);

        assertEquals(32, first.length);
        assertArrayEquals(first, second);
    }

    @Test
    void refusesToReplaceAMissingSecretForAnExistingDatabase() throws IOException {
        Path databasePath = temporaryDirectory.resolve("data.db");
        Files.writeString(databasePath, "existing database marker");

        assertThrows(
                IOException.class,
                () -> new SecretKeyManager().loadOrCreate(temporaryDirectory.resolve("secret.key"), databasePath)
        );
    }
}
