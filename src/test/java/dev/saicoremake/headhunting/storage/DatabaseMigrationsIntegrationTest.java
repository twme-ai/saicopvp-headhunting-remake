package dev.saicoremake.headhunting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseMigrationsIntegrationTest {
    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void versionTwoCreditsStrandedBuiltInRewardsExactlyOnce() throws Exception {
        Path path = temporaryDirectory.resolve("migration.db");
        createVersionOneFixture(path);

        PlayerSnapshot first = migrateAndRead(path);
        PlayerSnapshot second = migrateAndRead(path);

        assertEquals(new PlayerSnapshot(1_250, 575, 0), first);
        assertEquals(first, second);
    }

    private static PlayerSnapshot migrateAndRead(Path path) {
        SqliteDatabase database = new SqliteDatabase(path, Logger.getAnonymousLogger());
        database.start().join();
        try {
            HeadStore store = new HeadStore(database, CLOCK);
            var profile = store.findProfile(PLAYER_ID).join();
            int pendingRewards = store.findPendingRewards(PLAYER_ID).join().size();
            return new PlayerSnapshot(
                    profile.balance().minorUnits(),
                    profile.souls(),
                    pendingRewards
            );
        } finally {
            database.close();
        }
    }

    private static void createVersionOneFixture(Path path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE schema_version (version INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO schema_version(version) VALUES (1)");
            statement.executeUpdate("""
                    CREATE TABLE profiles (
                        player_uuid TEXT PRIMARY KEY,
                        last_name TEXT NOT NULL,
                        detected_locale TEXT NOT NULL,
                        locale_override TEXT,
                        level INTEGER NOT NULL,
                        completed INTEGER NOT NULL,
                        progress INTEGER NOT NULL,
                        balance_minor INTEGER NOT NULL,
                        souls INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE reward_outbox (
                        reward_key TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        level INTEGER NOT NULL,
                        reward_id TEXT NOT NULL,
                        reward_type TEXT NOT NULL,
                        reward_value TEXT NOT NULL,
                        reward_amount INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        last_error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO profiles VALUES (
                        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Player', 'en-US', NULL,
                        1, 0, 0, 1000, 500, 1, 1
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reward_outbox VALUES (
                        'balance', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 1,
                        'balance', 'BALANCE', '', 250, 'PENDING', 0, NULL, 1, 1
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reward_outbox VALUES (
                        'souls', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 1,
                        'souls', 'SOULS', '', 75, 'FAILED', 1, 'Unexpected outbox reward type', 1, 1
                    )
                    """);
        }
    }

    private record PlayerSnapshot(long balance, long souls, int pendingRewards) {
    }
}
