package dev.saicoremake.headhunting.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class DatabaseMigrations {
    static final int CURRENT_VERSION = 2;

    private DatabaseMigrations() {
    }

    static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int version = readVersion(connection);
        if (version > CURRENT_VERSION) {
            String message = "Database schema " + version
                    + " is newer than supported version " + CURRENT_VERSION;
            throw new SQLException(message);
        }
        if (version == 0) {
            applyVersionOne(connection);
            version = 1;
        }
        if (version == 1) {
            applyVersionTwo(connection);
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void applyVersionOne(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE profiles (
                        player_uuid TEXT PRIMARY KEY,
                        last_name TEXT NOT NULL,
                        detected_locale TEXT NOT NULL,
                        locale_override TEXT,
                        level INTEGER NOT NULL DEFAULT 1 CHECK (level >= 1),
                        completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
                        progress INTEGER NOT NULL DEFAULT 0 CHECK (progress >= 0),
                        balance_minor INTEGER NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
                        souls INTEGER NOT NULL DEFAULT 0 CHECK (souls >= 0),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE head_batches (
                        batch_id TEXT PRIMARY KEY,
                        head_key TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        owner_uuid TEXT,
                        unit_value_minor INTEGER NOT NULL CHECK (unit_value_minor >= 0),
                        progress_points INTEGER NOT NULL CHECK (progress_points >= 0),
                        minted_bucket INTEGER NOT NULL CHECK (minted_bucket >= 0),
                        minted_quantity INTEGER NOT NULL CHECK (minted_quantity >= 0),
                        reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
                        redeemed_quantity INTEGER NOT NULL DEFAULT 0 CHECK (redeemed_quantity >= 0),
                        created_at INTEGER NOT NULL,
                        CHECK (reserved_quantity + redeemed_quantity <= minted_quantity)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE head_sales (
                        sale_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL REFERENCES profiles(player_uuid),
                        status TEXT NOT NULL CHECK (status IN ('RESERVED', 'FINALIZED', 'CANCELLED')),
                        gross_minor INTEGER NOT NULL CHECK (gross_minor >= 0),
                        progress_delta INTEGER NOT NULL CHECK (progress_delta >= 0),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE head_sale_lines (
                        sale_id TEXT NOT NULL REFERENCES head_sales(sale_id) ON DELETE CASCADE,
                        batch_id TEXT NOT NULL REFERENCES head_batches(batch_id),
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        credit_progress INTEGER NOT NULL CHECK (credit_progress IN (0, 1)),
                        PRIMARY KEY (sale_id, batch_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE progress_events (
                        event_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL REFERENCES profiles(player_uuid),
                        event_kind TEXT NOT NULL,
                        head_key TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK (amount >= 0),
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE head_exchanges (
                        exchange_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL REFERENCES profiles(player_uuid),
                        recipe_key TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('RESERVED', 'FINALIZED', 'CANCELLED')),
                        soul_cost INTEGER NOT NULL CHECK (soul_cost >= 0),
                        reward_id TEXT NOT NULL,
                        reward_type TEXT NOT NULL,
                        reward_value TEXT NOT NULL,
                        reward_amount INTEGER NOT NULL CHECK (reward_amount >= 0),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE head_exchange_lines (
                        exchange_id TEXT NOT NULL REFERENCES head_exchanges(exchange_id) ON DELETE CASCADE,
                        batch_id TEXT NOT NULL REFERENCES head_batches(batch_id),
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        PRIMARY KEY (exchange_id, batch_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE mint_deliveries (
                        delivery_id TEXT PRIMARY KEY,
                        batch_id TEXT NOT NULL REFERENCES head_batches(batch_id),
                        recipient_uuid TEXT NOT NULL,
                        owner_name TEXT,
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        world_uuid TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('PENDING', 'DELIVERED')),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE player_head_claims (
                        death_id TEXT PRIMARY KEY,
                        killer_uuid TEXT NOT NULL,
                        victim_uuid TEXT NOT NULL,
                        head_value_minor INTEGER NOT NULL CHECK (head_value_minor >= 0),
                        claimed_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE kill_counters (
                        player_uuid TEXT NOT NULL REFERENCES profiles(player_uuid),
                        level INTEGER NOT NULL,
                        head_key TEXT NOT NULL,
                        amount INTEGER NOT NULL DEFAULT 0 CHECK (amount >= 0),
                        PRIMARY KEY (player_uuid, level, head_key)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE reward_outbox (
                        reward_key TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL REFERENCES profiles(player_uuid),
                        level INTEGER NOT NULL,
                        reward_id TEXT NOT NULL,
                        reward_type TEXT NOT NULL,
                        reward_value TEXT NOT NULL,
                        reward_amount INTEGER NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('PENDING', 'DELIVERING', 'COMPLETED', 'FAILED')),
                        attempts INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX idx_sales_player_status ON head_sales(player_uuid, status)");
            statement.executeUpdate(
                    "CREATE INDEX idx_exchanges_player_status ON head_exchanges(player_uuid, status)"
            );
            statement.executeUpdate("CREATE INDEX idx_rewards_status ON reward_outbox(status, created_at)");
            statement.executeUpdate("CREATE INDEX idx_deliveries_status ON mint_deliveries(status, created_at)");
            statement.executeUpdate(
                    "CREATE INDEX idx_claim_pair ON player_head_claims(killer_uuid, victim_uuid, claimed_at)"
            );
            statement.executeUpdate("CREATE INDEX idx_claim_victim ON player_head_claims(victim_uuid, claimed_at)");
            statement.executeUpdate("INSERT INTO schema_version(version) VALUES (1)");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void applyVersionTwo(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            List<BuiltInReward> rewards = readUndeliveredBuiltInRewards(connection);
            long now = System.currentTimeMillis();
            for (BuiltInReward reward : rewards) {
                applyBuiltInReward(connection, reward, now);
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO schema_version(version) VALUES (2)");
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static List<BuiltInReward> readUndeliveredBuiltInRewards(Connection connection) throws SQLException {
        List<BuiltInReward> rewards = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT reward_key, player_uuid, reward_type, reward_amount
                FROM reward_outbox
                WHERE reward_type IN ('BALANCE', 'SOULS') AND status <> 'COMPLETED'
                ORDER BY created_at, reward_key
                """); var result = statement.executeQuery()) {
            while (result.next()) {
                rewards.add(new BuiltInReward(
                        result.getString("reward_key"),
                        result.getString("player_uuid"),
                        result.getString("reward_type"),
                        result.getLong("reward_amount")
                ));
            }
        }
        return List.copyOf(rewards);
    }

    private static void applyBuiltInReward(Connection connection, BuiltInReward reward, long now)
            throws SQLException {
        if (reward.amount() < 0) {
            throw new SQLException("Built-in reward amount cannot be negative: " + reward.rewardKey());
        }
        String column = reward.type().equals("BALANCE") ? "balance_minor" : "souls";
        long current;
        try (var statement = connection.prepareStatement(
                "SELECT " + column + " FROM profiles WHERE player_uuid = ?"
        )) {
            statement.setString(1, reward.playerId());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Reward profile does not exist: " + reward.playerId());
                }
                current = result.getLong(1);
            }
        }
        long updated;
        try {
            updated = Math.addExact(current, reward.amount());
        } catch (ArithmeticException exception) {
            throw new SQLException("Built-in reward would overflow profile counter", exception);
        }
        try (var statement = connection.prepareStatement(
                "UPDATE profiles SET " + column + " = ?, updated_at = ? WHERE player_uuid = ?"
        )) {
            statement.setLong(1, updated);
            statement.setLong(2, now);
            statement.setString(3, reward.playerId());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                UPDATE reward_outbox
                SET status = 'COMPLETED', last_error = NULL, updated_at = ?
                WHERE reward_key = ?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, reward.rewardKey());
            statement.executeUpdate();
        }
    }

    private record BuiltInReward(String rewardKey, String playerId, String type, long amount) {
    }
}
