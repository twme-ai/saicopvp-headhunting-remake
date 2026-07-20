package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.HeadKind;
import dev.saicoremake.headhunting.domain.Money;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.domain.RewardDefinition;
import dev.saicoremake.headhunting.domain.RewardType;
import dev.saicoremake.headhunting.config.PlayerHeadSettings;
import dev.saicoremake.headhunting.config.ExchangeRecipe;
import dev.saicoremake.headhunting.security.HeadPayload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HeadStore {
    private final SqliteDatabase database;
    private final Clock clock;

    public HeadStore(SqliteDatabase database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<PlayerProfile> loadOrCreateProfile(
            UUID playerId,
            String playerName,
            Locale detectedLocale
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(detectedLocale, "detectedLocale");
        return database.submit(connection -> {
            long now = clock.millis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO profiles(
                        player_uuid, last_name, detected_locale, locale_override,
                        level, completed, progress, balance_minor, souls, created_at, updated_at
                    ) VALUES (?, ?, ?, NULL, 1, 0, 0, 0, 0, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        last_name = excluded.last_name,
                        detected_locale = excluded.detected_locale,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, playerName);
                statement.setString(3, detectedLocale.toLanguageTag());
                statement.setLong(4, now);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            return readProfile(connection, playerId);
        });
    }

    public CompletableFuture<PlayerProfile> findProfile(UUID playerId) {
        return database.submit(connection -> readProfile(connection, playerId));
    }

    public CompletableFuture<PlayerProfile> setLocaleOverride(UUID playerId, Locale localeOverride) {
        return database.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE profiles SET locale_override = ?, updated_at = ? WHERE player_uuid = ?
                    """)) {
                if (localeOverride == null) {
                    statement.setNull(1, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(1, localeOverride.toLanguageTag());
                }
                statement.setLong(2, clock.millis());
                statement.setString(3, playerId.toString());
                requireOneRow(statement.executeUpdate(), "Profile does not exist");
            }
            return readProfile(connection, playerId);
        });
    }

    public CompletableFuture<Void> recordMint(MintBatch mint) {
        return database.submit(connection -> {
            insertMint(connection, mint);
            return null;
        });
    }

    public CompletableFuture<PendingDelivery> recordMintDelivery(
            MintBatch mint,
            DeliveryTarget target
    ) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            insertMint(transaction, mint);
            insertDelivery(transaction, mint.payload(), mint.quantity(), target);
            return new PendingDelivery(mint.payload(), mint.quantity(), target);
        }));
    }

    public CompletableFuture<PlayerHeadMintResult> preparePlayerHeadMint(
            UUID deathId,
            UUID killerId,
            UUID victimId,
            long mintedBucket,
            PlayerHeadSettings settings,
            DeliveryTarget target
    ) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            ensureProfileExists(transaction, killerId);
            PlayerProfile victim = readProfile(transaction, victimId);
            long now = clock.millis();
            if (victim.balance().minorUnits() < settings.minimumVictimBalanceMinor()) {
                return new PlayerHeadMintResult(PlayerHeadMintStatus.BELOW_MINIMUM_BALANCE, null, victim);
            }
            if (lastClaim(transaction, killerId, victimId, false)
                    > now - settings.pairCooldownSeconds() * 1000) {
                return new PlayerHeadMintResult(PlayerHeadMintStatus.PAIR_COOLDOWN, null, victim);
            }
            if (lastClaim(transaction, killerId, victimId, true)
                    > now - settings.victimCooldownSeconds() * 1000) {
                return new PlayerHeadMintResult(PlayerHeadMintStatus.VICTIM_COOLDOWN, null, victim);
            }
            long calculatedValue = java.math.BigDecimal.valueOf(victim.balance().minorUnits())
                    .multiply(settings.balanceFraction())
                    .setScale(0, RoundingMode.FLOOR)
                    .longValueExact();
            long value = settings.maximumValueMinor() == 0
                    ? calculatedValue : Math.min(calculatedValue, settings.maximumValueMinor());
            if (value < 1) {
                return new PlayerHeadMintResult(PlayerHeadMintStatus.BELOW_MINIMUM_BALANCE, null, victim);
            }
            if (settings.deductFromVictim()) {
                try (PreparedStatement statement = transaction.prepareStatement("""
                        UPDATE profiles SET balance_minor = balance_minor - ?, updated_at = ?
                        WHERE player_uuid = ? AND balance_minor >= ?
                        """)) {
                    statement.setLong(1, value);
                    statement.setLong(2, now);
                    statement.setString(3, victimId.toString());
                    statement.setLong(4, value);
                    requireOneRow(statement.executeUpdate(), "Victim balance changed during head mint");
                }
            }
            try (PreparedStatement statement = transaction.prepareStatement("""
                    INSERT INTO player_head_claims(
                        death_id, killer_uuid, victim_uuid, head_value_minor, claimed_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, deathId.toString());
                statement.setString(2, killerId.toString());
                statement.setString(3, victimId.toString());
                statement.setLong(4, value);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            HeadPayload payload = new HeadPayload(
                    HeadPayload.CURRENT_SCHEMA,
                    deathId,
                    "player",
                    HeadKind.PLAYER,
                    victimId,
                    value,
                    0,
                    mintedBucket,
                    new byte[0]
            );
            insertMint(transaction, new MintBatch(payload, 1));
            insertDelivery(transaction, payload, 1, target);
            PendingDelivery delivery = new PendingDelivery(payload, 1, target);
            return new PlayerHeadMintResult(
                    PlayerHeadMintStatus.MINTED,
                    delivery,
                    readProfile(transaction, victimId)
            );
        }));
    }

    public CompletableFuture<List<PendingDelivery>> findPendingDeliveries() {
        return database.submit(connection -> {
            List<PendingDelivery> deliveries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT d.delivery_id, d.recipient_uuid, d.owner_name, d.quantity,
                           d.world_uuid, d.x, d.y, d.z,
                           b.batch_id, b.head_key, b.kind, b.owner_uuid,
                           b.unit_value_minor, b.progress_points, b.minted_bucket
                    FROM mint_deliveries d JOIN head_batches b ON b.batch_id = d.batch_id
                    WHERE d.status = 'PENDING' ORDER BY d.created_at
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String owner = result.getString("owner_uuid");
                        HeadPayload payload = new HeadPayload(
                                HeadPayload.CURRENT_SCHEMA,
                                UUID.fromString(result.getString("batch_id")),
                                result.getString("head_key"),
                                HeadKind.valueOf(result.getString("kind")),
                                owner == null ? null : UUID.fromString(owner),
                                result.getLong("unit_value_minor"),
                                result.getLong("progress_points"),
                                result.getLong("minted_bucket"),
                                new byte[0]
                        );
                        DeliveryTarget target = new DeliveryTarget(
                                UUID.fromString(result.getString("delivery_id")),
                                UUID.fromString(result.getString("recipient_uuid")),
                                result.getString("owner_name"),
                                UUID.fromString(result.getString("world_uuid")),
                                result.getDouble("x"),
                                result.getDouble("y"),
                                result.getDouble("z")
                        );
                        deliveries.add(new PendingDelivery(payload, result.getInt("quantity"), target));
                    }
                }
            }
            return List.copyOf(deliveries);
        });
    }

    public CompletableFuture<Void> markDeliveryComplete(UUID deliveryId) {
        return database.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE mint_deliveries SET status = 'DELIVERED', updated_at = ?
                    WHERE delivery_id = ? AND status = 'PENDING'
                    """)) {
                statement.setLong(1, clock.millis());
                statement.setString(2, deliveryId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<SaleReservation> reserveSale(
            UUID saleId,
            UUID playerId,
            List<SaleLine> requestedLines
    ) {
        List<SaleLine> lines = consolidate(requestedLines);
        return database.submit(connection -> inTransaction(connection, transaction -> {
            ensureProfileExists(transaction, playerId);
            long grossMinor = 0;
            long progressDelta = 0;
            for (SaleLine line : lines) {
                verifyBatchAndAvailability(transaction, line);
                grossMinor = Math.addExact(
                        grossMinor,
                        Math.multiplyExact(line.payload().unitValueMinor(), line.quantity())
                );
                if (line.creditProgress()) {
                    progressDelta = Math.addExact(
                            progressDelta,
                            Math.multiplyExact(line.payload().progressPoints(), line.quantity())
                    );
                }
            }

            long now = clock.millis();
            try (PreparedStatement sale = transaction.prepareStatement("""
                    INSERT INTO head_sales(
                        sale_id, player_uuid, status, gross_minor, progress_delta, created_at, updated_at
                    ) VALUES (?, ?, 'RESERVED', ?, ?, ?, ?)
                    """)) {
                sale.setString(1, saleId.toString());
                sale.setString(2, playerId.toString());
                sale.setLong(3, grossMinor);
                sale.setLong(4, progressDelta);
                sale.setLong(5, now);
                sale.setLong(6, now);
                sale.executeUpdate();
            }

            for (SaleLine line : lines) {
                reserveLine(transaction, saleId, line);
            }
            return new SaleReservation(saleId, playerId, lines, new Money(grossMinor), progressDelta);
        }));
    }

    public CompletableFuture<PlayerProfile> finalizeSale(UUID saleId) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            SaleRow sale = readSale(transaction, saleId);
            if (sale.status().equals("FINALIZED")) {
                return readProfile(transaction, sale.playerId());
            }
            if (!sale.status().equals("RESERVED")) {
                throw new SQLException("Sale is not reserved: " + saleId);
            }
            for (StoredSaleLine line : readStoredSaleLines(transaction, saleId)) {
                try (PreparedStatement statement = transaction.prepareStatement("""
                        UPDATE head_batches
                        SET reserved_quantity = reserved_quantity - ?,
                            redeemed_quantity = redeemed_quantity + ?
                        WHERE batch_id = ? AND reserved_quantity >= ?
                        """)) {
                    statement.setInt(1, line.quantity());
                    statement.setInt(2, line.quantity());
                    statement.setString(3, line.batchId().toString());
                    statement.setInt(4, line.quantity());
                    requireOneRow(statement.executeUpdate(), "Reserved batch quantity is inconsistent");
                }
            }
            try (PreparedStatement profile = transaction.prepareStatement("""
                    UPDATE profiles SET
                        balance_minor = balance_minor + ?,
                        progress = progress + ?,
                        updated_at = ?
                    WHERE player_uuid = ?
                    """)) {
                profile.setLong(1, sale.grossMinor());
                profile.setLong(2, sale.progressDelta());
                profile.setLong(3, clock.millis());
                profile.setString(4, sale.playerId().toString());
                requireOneRow(profile.executeUpdate(), "Sale profile does not exist");
            }
            updateSaleStatus(transaction, saleId, "FINALIZED");
            return readProfile(transaction, sale.playerId());
        }));
    }

    public CompletableFuture<Void> cancelSale(UUID saleId) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            SaleRow sale = readSale(transaction, saleId);
            if (sale.status().equals("CANCELLED")) {
                return null;
            }
            if (sale.status().equals("FINALIZED")) {
                throw new SQLException("A finalized sale cannot be cancelled");
            }
            for (StoredSaleLine line : readStoredSaleLines(transaction, saleId)) {
                try (PreparedStatement statement = transaction.prepareStatement("""
                        UPDATE head_batches SET reserved_quantity = reserved_quantity - ?
                        WHERE batch_id = ? AND reserved_quantity >= ?
                        """)) {
                    statement.setInt(1, line.quantity());
                    statement.setString(2, line.batchId().toString());
                    statement.setInt(3, line.quantity());
                    requireOneRow(statement.executeUpdate(), "Reserved batch quantity is inconsistent");
                }
            }
            updateSaleStatus(transaction, saleId, "CANCELLED");
            return null;
        }));
    }

    public CompletableFuture<ExchangeReservation> reserveExchange(
            UUID exchangeId,
            UUID playerId,
            ExchangeRecipe recipe,
            List<SaleLine> requestedLines
    ) {
        List<SaleLine> lines = consolidate(requestedLines);
        return database.submit(connection -> inTransaction(connection, transaction -> {
            PlayerProfile profile = readProfile(transaction, playerId);
            if (profile.souls() < recipe.soulCost()) {
                throw new SQLException("Profile does not have enough Souls for exchange");
            }
            for (SaleLine line : lines) {
                verifyBatchAndAvailability(transaction, line);
            }
            insertExchange(transaction, exchangeId, playerId, recipe);
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE profiles SET souls = souls - ?, updated_at = ?
                    WHERE player_uuid = ? AND souls >= ?
                    """)) {
                statement.setLong(1, recipe.soulCost());
                statement.setLong(2, clock.millis());
                statement.setString(3, playerId.toString());
                statement.setLong(4, recipe.soulCost());
                requireOneRow(statement.executeUpdate(), "Profile Souls changed during exchange");
            }
            for (SaleLine line : lines) {
                reserveExchangeLine(transaction, exchangeId, line);
            }
            return new ExchangeReservation(
                    exchangeId,
                    playerId,
                    recipe.key(),
                    lines,
                    recipe.soulCost(),
                    recipe.reward()
            );
        }));
    }

    public CompletableFuture<PlayerProfile> finalizeExchange(UUID exchangeId) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            ExchangeRow exchange = readExchange(transaction, exchangeId);
            if (exchange.status().equals("FINALIZED")) {
                return readProfile(transaction, exchange.playerId());
            }
            if (!exchange.status().equals("RESERVED")) {
                throw new SQLException("Exchange is not reserved: " + exchangeId);
            }
            for (StoredSaleLine line : readStoredExchangeLines(transaction, exchangeId)) {
                consumeReservedBatch(transaction, line);
            }
            PlayerProfile profile = readProfile(transaction, exchange.playerId());
            String rewardKey = "exchange:" + exchangeId + ":" + exchange.reward().id();
            insertRewardOutbox(
                    transaction,
                    exchange.playerId(),
                    profile.level(),
                    exchange.reward(),
                    rewardKey
            );
            updateExchangeStatus(transaction, exchangeId, "FINALIZED");
            return profile;
        }));
    }

    public CompletableFuture<PlayerProfile> cancelExchange(UUID exchangeId) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            ExchangeRow exchange = readExchange(transaction, exchangeId);
            if (exchange.status().equals("CANCELLED")) {
                return readProfile(transaction, exchange.playerId());
            }
            if (exchange.status().equals("FINALIZED")) {
                throw new SQLException("A finalized exchange cannot be cancelled");
            }
            for (StoredSaleLine line : readStoredExchangeLines(transaction, exchangeId)) {
                releaseReservedBatch(transaction, line);
            }
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE profiles SET souls = souls + ?, updated_at = ? WHERE player_uuid = ?
                    """)) {
                statement.setLong(1, exchange.soulCost());
                statement.setLong(2, clock.millis());
                statement.setString(3, exchange.playerId().toString());
                requireOneRow(statement.executeUpdate(), "Exchange profile does not exist");
            }
            updateExchangeStatus(transaction, exchangeId, "CANCELLED");
            return readProfile(transaction, exchange.playerId());
        }));
    }

    public CompletableFuture<List<ExchangeReservation>> findReservedExchanges(UUID playerId) {
        return database.submit(connection -> {
            List<ExchangeReservation> exchanges = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT exchange_id FROM head_exchanges
                    WHERE player_uuid = ? AND status = 'RESERVED' ORDER BY created_at
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID exchangeId = UUID.fromString(result.getString("exchange_id"));
                        exchanges.add(toReservation(connection, exchangeId));
                    }
                }
            }
            return List.copyOf(exchanges);
        });
    }

    public CompletableFuture<List<SaleReservation>> findReservedSales(UUID playerId) {
        return database.submit(connection -> {
            List<SaleReservation> reservations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT sale_id, gross_minor, progress_delta
                    FROM head_sales WHERE player_uuid = ? AND status = 'RESERVED'
                    ORDER BY created_at
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID saleId = UUID.fromString(result.getString("sale_id"));
                        reservations.add(new SaleReservation(
                                saleId,
                                playerId,
                                readSaleLines(connection, saleId),
                                new Money(result.getLong("gross_minor")),
                                result.getLong("progress_delta")
                        ));
                    }
                }
            }
            return List.copyOf(reservations);
        });
    }

    public CompletableFuture<ProgressUpdate> applyProgressEvent(
            UUID eventId,
            UUID playerId,
            String eventKind,
            String headKey,
            long progress,
            long souls,
            Money money
    ) {
        if (progress < 0 || souls < 0) {
            throw new IllegalArgumentException("Progress rewards cannot be negative");
        }
        return database.submit(connection -> inTransaction(connection, transaction -> {
            PlayerProfile current = readProfile(transaction, playerId);
            int inserted;
            try (PreparedStatement statement = transaction.prepareStatement("""
                    INSERT OR IGNORE INTO progress_events(
                        event_id, player_uuid, event_kind, head_key, amount, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, eventId.toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, eventKind);
                statement.setString(4, headKey);
                statement.setLong(5, progress);
                statement.setLong(6, clock.millis());
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                return new ProgressUpdate(false, current);
            }
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE profiles SET
                        progress = progress + ?, souls = souls + ?, balance_minor = balance_minor + ?, updated_at = ?
                    WHERE player_uuid = ?
                    """)) {
                statement.setLong(1, progress);
                statement.setLong(2, souls);
                statement.setLong(3, money.minorUnits());
                statement.setLong(4, clock.millis());
                statement.setString(5, playerId.toString());
                requireOneRow(statement.executeUpdate(), "Progress profile does not exist");
            }
            try (PreparedStatement statement = transaction.prepareStatement("""
                    INSERT INTO kill_counters(player_uuid, level, head_key, amount)
                    VALUES (?, ?, ?, 1)
                    ON CONFLICT(player_uuid, level, head_key) DO UPDATE SET amount = amount + 1
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, current.level());
                statement.setString(3, headKey);
                statement.executeUpdate();
            }
            return new ProgressUpdate(true, readProfile(transaction, playerId));
        }));
    }

    public CompletableFuture<RankUpResult> rankUp(UUID playerId, LevelDefinition levelDefinition) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            PlayerProfile current = readProfile(transaction, playerId);
            if (current.completed()) {
                return new RankUpResult(RankUpStatus.ALREADY_COMPLETED, current);
            }
            if (current.level() != levelDefinition.number()) {
                return new RankUpResult(RankUpStatus.LEVEL_CHANGED, current);
            }
            Map<String, Long> kills = readKillCounters(transaction, playerId, current.level());
            if (!levelDefinition.isComplete(current.progress(), kills)) {
                return new RankUpResult(RankUpStatus.INCOMPLETE, current);
            }
            if (current.balance().compareTo(levelDefinition.rankUpCost()) < 0) {
                return new RankUpResult(RankUpStatus.INSUFFICIENT_FUNDS, current);
            }

            long balanceReward = 0;
            long soulReward = 0;
            for (RewardDefinition reward : levelDefinition.rewards()) {
                if (reward.type() == RewardType.BALANCE) {
                    balanceReward = Math.addExact(balanceReward, reward.amount());
                } else if (reward.type() == RewardType.SOULS) {
                    soulReward = Math.addExact(soulReward, reward.amount());
                } else {
                    insertRewardOutbox(transaction, playerId, levelDefinition.number(), reward);
                }
            }
            long newBalance = Math.addExact(
                    Math.subtractExact(current.balance().minorUnits(), levelDefinition.rankUpCost().minorUnits()),
                    balanceReward
            );
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE profiles SET
                        level = level + ?,
                        completed = ?,
                        progress = 0,
                        balance_minor = ?,
                        souls = souls + ?,
                        updated_at = ?
                    WHERE player_uuid = ? AND level = ?
                    """)) {
                statement.setInt(1, levelDefinition.terminal() ? 0 : 1);
                statement.setInt(2, levelDefinition.terminal() ? 1 : 0);
                statement.setLong(3, newBalance);
                statement.setLong(4, soulReward);
                statement.setLong(5, clock.millis());
                statement.setString(6, playerId.toString());
                statement.setInt(7, current.level());
                requireOneRow(statement.executeUpdate(), "Profile level changed during rank-up");
            }
            return new RankUpResult(RankUpStatus.SUCCESS, readProfile(transaction, playerId));
        }));
    }

    public CompletableFuture<List<PendingReward>> findPendingRewards(UUID playerId) {
        return database.submit(connection -> {
            List<PendingReward> rewards = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT reward_key, player_uuid, level, reward_id, reward_type,
                           reward_value, reward_amount, status, attempts
                    FROM reward_outbox
                    WHERE player_uuid = ? AND status IN ('PENDING', 'DELIVERING')
                    ORDER BY created_at, reward_key
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rewards.add(readReward(result));
                    }
                }
            }
            return List.copyOf(rewards);
        });
    }

    public CompletableFuture<PendingReward> claimReward(String rewardKey, UUID playerId) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE reward_outbox SET status = 'DELIVERING', attempts = attempts + 1, updated_at = ?
                    WHERE reward_key = ? AND player_uuid = ? AND status = 'PENDING'
                    """)) {
                statement.setLong(1, clock.millis());
                statement.setString(2, rewardKey);
                statement.setString(3, playerId.toString());
                statement.executeUpdate();
            }
            return readReward(transaction, rewardKey, playerId);
        }));
    }

    public CompletableFuture<PlayerProfile> setLevel(UUID playerId, int level, boolean completed) {
        if (level < 1) {
            throw new IllegalArgumentException("Level must be positive");
        }
        return database.submit(connection -> inTransaction(connection, transaction -> {
            try (PreparedStatement statement = transaction.prepareStatement("""
                    UPDATE profiles SET level = ?, completed = ?, progress = 0, updated_at = ?
                    WHERE player_uuid = ?
                    """)) {
                statement.setInt(1, level);
                statement.setInt(2, completed ? 1 : 0);
                statement.setLong(3, clock.millis());
                statement.setString(4, playerId.toString());
                requireOneRow(statement.executeUpdate(), "Profile does not exist");
            }
            try (PreparedStatement statement = transaction.prepareStatement("""
                    DELETE FROM kill_counters WHERE player_uuid = ?
                    """)) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            }
            return readProfile(transaction, playerId);
        }));
    }

    public CompletableFuture<PlayerProfile> addCounter(UUID playerId, ProfileCounter counter, long delta) {
        return database.submit(connection -> inTransaction(connection, transaction -> {
            PlayerProfile profile = readProfile(transaction, playerId);
            long current = switch (counter) {
                case PROGRESS -> profile.progress();
                case SOULS -> profile.souls();
                case BALANCE -> profile.balance().minorUnits();
                default -> throw new IllegalStateException("Unknown profile counter: " + counter);
            };
            long updated = Math.addExact(current, delta);
            if (updated < 0) {
                throw new SQLException("Profile counter cannot become negative");
            }
            String column = switch (counter) {
                case PROGRESS -> "progress";
                case SOULS -> "souls";
                case BALANCE -> "balance_minor";
                default -> throw new IllegalStateException("Unknown profile counter: " + counter);
            };
            try (PreparedStatement statement = transaction.prepareStatement(
                    "UPDATE profiles SET " + column + " = ?, updated_at = ? WHERE player_uuid = ?"
            )) {
                statement.setLong(1, updated);
                statement.setLong(2, clock.millis());
                statement.setString(3, playerId.toString());
                requireOneRow(statement.executeUpdate(), "Profile does not exist");
            }
            return readProfile(transaction, playerId);
        }));
    }

    public CompletableFuture<Void> completeReward(String rewardKey, UUID playerId) {
        return updateRewardStatus(rewardKey, playerId, "COMPLETED", null);
    }

    public CompletableFuture<Void> resetReward(String rewardKey, UUID playerId) {
        return updateRewardStatus(rewardKey, playerId, "PENDING", null);
    }

    public CompletableFuture<Void> failReward(String rewardKey, UUID playerId, String error) {
        return updateRewardStatus(rewardKey, playerId, "FAILED", error);
    }

    private static List<SaleLine> consolidate(List<SaleLine> requestedLines) {
        if (requestedLines == null || requestedLines.isEmpty()) {
            throw new IllegalArgumentException("A sale must contain at least one line");
        }
        Map<UUID, SaleLine> consolidated = new LinkedHashMap<>();
        for (SaleLine line : requestedLines) {
            consolidated.merge(line.payload().batchId(), line, (left, right) -> {
                if (!samePayload(left.payload(), right.payload())
                        || left.creditProgress() != right.creditProgress()) {
                    throw new IllegalArgumentException("A batch cannot have conflicting sale metadata");
                }
                int quantity = Math.addExact(left.quantity(), right.quantity());
                return new SaleLine(left.payload(), quantity, left.creditProgress());
            });
        }
        return List.copyOf(consolidated.values());
    }

    private static void verifyBatchAndAvailability(Connection connection, SaleLine line) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT head_key, kind, owner_uuid, unit_value_minor, progress_points, minted_bucket,
                       minted_quantity, reserved_quantity, redeemed_quantity
                FROM head_batches WHERE batch_id = ?
                """)) {
            statement.setString(1, line.payload().batchId().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Head batch was never minted: " + line.payload().batchId());
                }
                HeadPayload payload = line.payload();
                String owner = result.getString("owner_uuid");
                String expectedOwner = payload.ownerId() == null ? null : payload.ownerId().toString();
                boolean metadataMatches = payload.headKey().equals(result.getString("head_key"))
                        && payload.kind().name().equals(result.getString("kind"))
                        && Objects.equals(expectedOwner, owner)
                        && payload.unitValueMinor() == result.getLong("unit_value_minor")
                        && payload.progressPoints() == result.getLong("progress_points")
                        && payload.mintedBucket() == result.getLong("minted_bucket");
                if (!metadataMatches) {
                    throw new SQLException("Signed item metadata does not match its mint batch");
                }
                long available = result.getLong("minted_quantity")
                        - result.getLong("reserved_quantity")
                        - result.getLong("redeemed_quantity");
                if (available < line.quantity()) {
                    throw new SQLException("Head batch does not have enough unredeemed quantity");
                }
            }
        }
    }

    private static void reserveLine(Connection connection, UUID saleId, SaleLine line) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE head_batches SET reserved_quantity = reserved_quantity + ?
                WHERE batch_id = ? AND minted_quantity - reserved_quantity - redeemed_quantity >= ?
                """)) {
            statement.setInt(1, line.quantity());
            statement.setString(2, line.payload().batchId().toString());
            statement.setInt(3, line.quantity());
            requireOneRow(statement.executeUpdate(), "Head batch became unavailable");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO head_sale_lines(sale_id, batch_id, quantity, credit_progress) VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, saleId.toString());
            statement.setString(2, line.payload().batchId().toString());
            statement.setInt(3, line.quantity());
            statement.setInt(4, line.creditProgress() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static List<SaleLine> readSaleLines(Connection connection, UUID saleId) throws SQLException {
        List<SaleLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT b.batch_id, b.head_key, b.kind, b.owner_uuid, b.unit_value_minor,
                       b.progress_points, b.minted_bucket, l.quantity, l.credit_progress
                FROM head_sale_lines l JOIN head_batches b ON b.batch_id = l.batch_id
                WHERE l.sale_id = ? ORDER BY b.batch_id
                """)) {
            statement.setString(1, saleId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String owner = result.getString("owner_uuid");
                    HeadPayload payload = new HeadPayload(
                            HeadPayload.CURRENT_SCHEMA,
                            UUID.fromString(result.getString("batch_id")),
                            result.getString("head_key"),
                            dev.saicoremake.headhunting.domain.HeadKind.valueOf(result.getString("kind")),
                            owner == null ? null : UUID.fromString(owner),
                            result.getLong("unit_value_minor"),
                            result.getLong("progress_points"),
                            result.getLong("minted_bucket"),
                            new byte[0]
                    );
                    lines.add(new SaleLine(payload, result.getInt("quantity"), result.getInt("credit_progress") == 1));
                }
            }
        }
        return List.copyOf(lines);
    }

    private static List<StoredSaleLine> readStoredSaleLines(Connection connection, UUID saleId) throws SQLException {
        List<StoredSaleLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT batch_id, quantity FROM head_sale_lines WHERE sale_id = ?
                """)) {
            statement.setString(1, saleId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    lines.add(new StoredSaleLine(
                            UUID.fromString(result.getString("batch_id")),
                            result.getInt("quantity")
                    ));
                }
            }
        }
        return lines;
    }

    private void insertExchange(
            Connection connection,
            UUID exchangeId,
            UUID playerId,
            ExchangeRecipe recipe
    ) throws SQLException {
        RewardDefinition reward = recipe.reward();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO head_exchanges(
                    exchange_id, player_uuid, recipe_key, status, soul_cost,
                    reward_id, reward_type, reward_value, reward_amount, created_at, updated_at
                ) VALUES (?, ?, ?, 'RESERVED', ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, exchangeId.toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, recipe.key());
            statement.setLong(4, recipe.soulCost());
            statement.setString(5, reward.id());
            statement.setString(6, reward.type().name());
            statement.setString(7, reward.value());
            statement.setLong(8, reward.amount());
            statement.setLong(9, clock.millis());
            statement.setLong(10, clock.millis());
            statement.executeUpdate();
        }
    }

    private static void reserveExchangeLine(Connection connection, UUID exchangeId, SaleLine line)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE head_batches SET reserved_quantity = reserved_quantity + ?
                WHERE batch_id = ? AND minted_quantity - reserved_quantity - redeemed_quantity >= ?
                """)) {
            statement.setInt(1, line.quantity());
            statement.setString(2, line.payload().batchId().toString());
            statement.setInt(3, line.quantity());
            requireOneRow(statement.executeUpdate(), "Head batch became unavailable for exchange");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO head_exchange_lines(exchange_id, batch_id, quantity) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, exchangeId.toString());
            statement.setString(2, line.payload().batchId().toString());
            statement.setInt(3, line.quantity());
            statement.executeUpdate();
        }
    }

    private static List<StoredSaleLine> readStoredExchangeLines(Connection connection, UUID exchangeId)
            throws SQLException {
        List<StoredSaleLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT batch_id, quantity FROM head_exchange_lines WHERE exchange_id = ?
                """)) {
            statement.setString(1, exchangeId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    lines.add(new StoredSaleLine(
                            UUID.fromString(result.getString("batch_id")),
                            result.getInt("quantity")
                    ));
                }
            }
        }
        return lines;
    }

    private static void consumeReservedBatch(Connection connection, StoredSaleLine line) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE head_batches
                SET reserved_quantity = reserved_quantity - ?, redeemed_quantity = redeemed_quantity + ?
                WHERE batch_id = ? AND reserved_quantity >= ?
                """)) {
            statement.setInt(1, line.quantity());
            statement.setInt(2, line.quantity());
            statement.setString(3, line.batchId().toString());
            statement.setInt(4, line.quantity());
            requireOneRow(statement.executeUpdate(), "Reserved exchange batch quantity is inconsistent");
        }
    }

    private static void releaseReservedBatch(Connection connection, StoredSaleLine line) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE head_batches SET reserved_quantity = reserved_quantity - ?
                WHERE batch_id = ? AND reserved_quantity >= ?
                """)) {
            statement.setInt(1, line.quantity());
            statement.setString(2, line.batchId().toString());
            statement.setInt(3, line.quantity());
            requireOneRow(statement.executeUpdate(), "Reserved exchange batch quantity is inconsistent");
        }
    }

    private ExchangeRow readExchange(Connection connection, UUID exchangeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, recipe_key, status, soul_cost,
                       reward_id, reward_type, reward_value, reward_amount
                FROM head_exchanges WHERE exchange_id = ?
                """)) {
            statement.setString(1, exchangeId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Exchange does not exist: " + exchangeId);
                }
                RewardDefinition reward = new RewardDefinition(
                        result.getString("reward_id"),
                        RewardType.valueOf(result.getString("reward_type")),
                        result.getString("reward_value"),
                        result.getLong("reward_amount")
                );
                return new ExchangeRow(
                        UUID.fromString(result.getString("player_uuid")),
                        result.getString("recipe_key"),
                        result.getString("status"),
                        result.getLong("soul_cost"),
                        reward
                );
            }
        }
    }

    private ExchangeReservation toReservation(Connection connection, UUID exchangeId) throws SQLException {
        ExchangeRow row = readExchange(connection, exchangeId);
        List<SaleLine> lines = readExchangeLines(connection, exchangeId);
        return new ExchangeReservation(
                exchangeId,
                row.playerId(),
                row.recipeKey(),
                lines,
                row.soulCost(),
                row.reward()
        );
    }

    private static List<SaleLine> readExchangeLines(Connection connection, UUID exchangeId) throws SQLException {
        List<SaleLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT b.batch_id, b.head_key, b.kind, b.owner_uuid, b.unit_value_minor,
                       b.progress_points, b.minted_bucket, l.quantity
                FROM head_exchange_lines l JOIN head_batches b ON b.batch_id = l.batch_id
                WHERE l.exchange_id = ? ORDER BY b.batch_id
                """)) {
            statement.setString(1, exchangeId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String owner = result.getString("owner_uuid");
                    HeadPayload payload = new HeadPayload(
                            HeadPayload.CURRENT_SCHEMA,
                            UUID.fromString(result.getString("batch_id")),
                            result.getString("head_key"),
                            HeadKind.valueOf(result.getString("kind")),
                            owner == null ? null : UUID.fromString(owner),
                            result.getLong("unit_value_minor"),
                            result.getLong("progress_points"),
                            result.getLong("minted_bucket"),
                            new byte[0]
                    );
                    lines.add(new SaleLine(payload, result.getInt("quantity"), false));
                }
            }
        }
        return List.copyOf(lines);
    }

    private void updateExchangeStatus(Connection connection, UUID exchangeId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE head_exchanges SET status = ?, updated_at = ? WHERE exchange_id = ?
                """)) {
            statement.setString(1, status);
            statement.setLong(2, clock.millis());
            statement.setString(3, exchangeId.toString());
            requireOneRow(statement.executeUpdate(), "Exchange does not exist");
        }
    }

    private static SaleRow readSale(Connection connection, UUID saleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, status, gross_minor, progress_delta FROM head_sales WHERE sale_id = ?
                """)) {
            statement.setString(1, saleId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Sale does not exist: " + saleId);
                }
                return new SaleRow(
                        UUID.fromString(result.getString("player_uuid")),
                        result.getString("status"),
                        result.getLong("gross_minor"),
                        result.getLong("progress_delta")
                );
            }
        }
    }

    private void updateSaleStatus(Connection connection, UUID saleId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE head_sales SET status = ?, updated_at = ? WHERE sale_id = ?
                """)) {
            statement.setString(1, status);
            statement.setLong(2, clock.millis());
            statement.setString(3, saleId.toString());
            requireOneRow(statement.executeUpdate(), "Sale does not exist");
        }
    }

    private void insertRewardOutbox(
            Connection connection,
            UUID playerId,
            int level,
            RewardDefinition reward
    ) throws SQLException {
        String rewardKey = playerId + ":" + level + ":" + reward.id();
        insertRewardOutbox(connection, playerId, level, reward, rewardKey);
    }

    private void insertRewardOutbox(
            Connection connection,
            UUID playerId,
            int level,
            RewardDefinition reward,
            String rewardKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO reward_outbox(
                    reward_key, player_uuid, level, reward_id, reward_type, reward_value,
                    reward_amount, status, attempts, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """)) {
            statement.setString(1, rewardKey);
            statement.setString(2, playerId.toString());
            statement.setInt(3, level);
            statement.setString(4, reward.id());
            statement.setString(5, reward.type().name());
            statement.setString(6, reward.value());
            statement.setLong(7, reward.amount());
            statement.setLong(8, clock.millis());
            statement.setLong(9, clock.millis());
            statement.executeUpdate();
        }
    }

    private static Map<String, Long> readKillCounters(Connection connection, UUID playerId, int level)
            throws SQLException {
        Map<String, Long> counters = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT head_key, amount FROM kill_counters WHERE player_uuid = ? AND level = ?
                """)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, level);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    counters.put(result.getString("head_key"), result.getLong("amount"));
                }
            }
        }
        return Map.copyOf(counters);
    }

    private static PlayerProfile readProfile(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT last_name, detected_locale, locale_override, level, completed,
                       progress, balance_minor, souls
                FROM profiles WHERE player_uuid = ?
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Profile does not exist: " + playerId);
                }
                String localeOverride = result.getString("locale_override");
                return new PlayerProfile(
                        playerId,
                        result.getString("last_name"),
                        Locale.forLanguageTag(result.getString("detected_locale")),
                        localeOverride == null ? null : Locale.forLanguageTag(localeOverride),
                        result.getInt("level"),
                        result.getInt("completed") == 1,
                        result.getLong("progress"),
                        new Money(result.getLong("balance_minor")),
                        result.getLong("souls")
                );
            }
        }
    }

    private CompletableFuture<Void> updateRewardStatus(
            String rewardKey,
            UUID playerId,
            String status,
            String error
    ) {
        return database.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_outbox SET status = ?, last_error = ?, updated_at = ?
                    WHERE reward_key = ? AND player_uuid = ?
                    """)) {
                statement.setString(1, status);
                if (error == null) {
                    statement.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(2, error);
                }
                statement.setLong(3, clock.millis());
                statement.setString(4, rewardKey);
                statement.setString(5, playerId.toString());
                requireOneRow(statement.executeUpdate(), "Reward does not exist");
            }
            return null;
        });
    }

    private static PendingReward readReward(Connection connection, String rewardKey, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reward_key, player_uuid, level, reward_id, reward_type,
                       reward_value, reward_amount, status, attempts
                FROM reward_outbox WHERE reward_key = ? AND player_uuid = ?
                """)) {
            statement.setString(1, rewardKey);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Reward does not exist: " + rewardKey);
                }
                return readReward(result);
            }
        }
    }

    private static PendingReward readReward(ResultSet result) throws SQLException {
        return new PendingReward(
                result.getString("reward_key"),
                UUID.fromString(result.getString("player_uuid")),
                result.getInt("level"),
                result.getString("reward_id"),
                RewardType.valueOf(result.getString("reward_type")),
                result.getString("reward_value"),
                result.getLong("reward_amount"),
                result.getString("status"),
                result.getInt("attempts")
        );
    }

    private static void ensureProfileExists(Connection connection, UUID playerId) throws SQLException {
        String sql = "SELECT 1 FROM profiles WHERE player_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Profile does not exist: " + playerId);
                }
            }
        }
    }

    private static void bindPayload(PreparedStatement statement, HeadPayload payload) throws SQLException {
        statement.setString(1, payload.batchId().toString());
        statement.setString(2, payload.headKey());
        statement.setString(3, payload.kind().name());
        if (payload.ownerId() == null) {
            statement.setNull(4, java.sql.Types.VARCHAR);
        } else {
            statement.setString(4, payload.ownerId().toString());
        }
        statement.setLong(5, payload.unitValueMinor());
        statement.setLong(6, payload.progressPoints());
        statement.setLong(7, payload.mintedBucket());
    }

    private void insertMint(Connection connection, MintBatch mint) throws SQLException {
        HeadPayload payload = mint.payload();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO head_batches(
                    batch_id, head_key, kind, owner_uuid, unit_value_minor, progress_points,
                    minted_bucket, minted_quantity, reserved_quantity, redeemed_quantity, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
                ON CONFLICT(batch_id) DO UPDATE SET
                    minted_quantity = minted_quantity + excluded.minted_quantity
                WHERE head_key = excluded.head_key
                  AND kind = excluded.kind
                  AND owner_uuid IS excluded.owner_uuid
                  AND unit_value_minor = excluded.unit_value_minor
                  AND progress_points = excluded.progress_points
                  AND minted_bucket = excluded.minted_bucket
                """)) {
            bindPayload(statement, payload);
            statement.setInt(8, mint.quantity());
            statement.setLong(9, clock.millis());
            requireOneRow(statement.executeUpdate(), "Batch id collided with different metadata");
        }
    }

    private void insertDelivery(
            Connection connection,
            HeadPayload payload,
            int quantity,
            DeliveryTarget target
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO mint_deliveries(
                    delivery_id, batch_id, recipient_uuid, owner_name, quantity,
                    world_uuid, x, y, z, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """)) {
            statement.setString(1, target.deliveryId().toString());
            statement.setString(2, payload.batchId().toString());
            statement.setString(3, target.recipientId().toString());
            if (target.ownerName() == null) {
                statement.setNull(4, java.sql.Types.VARCHAR);
            } else {
                statement.setString(4, target.ownerName());
            }
            statement.setInt(5, quantity);
            statement.setString(6, target.worldId().toString());
            statement.setDouble(7, target.x());
            statement.setDouble(8, target.y());
            statement.setDouble(9, target.z());
            statement.setLong(10, clock.millis());
            statement.setLong(11, clock.millis());
            statement.executeUpdate();
        }
    }

    private static long lastClaim(
            Connection connection,
            UUID killerId,
            UUID victimId,
            boolean anyKiller
    ) throws SQLException {
        String sql = anyKiller
                ? "SELECT COALESCE(MAX(claimed_at), 0) FROM player_head_claims WHERE victim_uuid = ?"
                : "SELECT COALESCE(MAX(claimed_at), 0) FROM player_head_claims "
                        + "WHERE killer_uuid = ? AND victim_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (!anyKiller) {
                statement.setString(index++, killerId.toString());
            }
            statement.setString(index, victimId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static boolean samePayload(HeadPayload left, HeadPayload right) {
        return left.schemaVersion() == right.schemaVersion()
                && left.batchId().equals(right.batchId())
                && left.headKey().equals(right.headKey())
                && left.kind() == right.kind()
                && Objects.equals(left.ownerId(), right.ownerId())
                && left.unitValueMinor() == right.unitValueMinor()
                && left.progressPoints() == right.progressPoints()
                && left.mintedBucket() == right.mintedBucket()
                && Arrays.equals(left.signature(), right.signature());
    }

    private static void requireOneRow(int affected, String message) throws SQLException {
        if (affected != 1) {
            throw new SQLException(message);
        }
    }

    private static <T> T inTransaction(Connection connection, SqlFunction<T> operation) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = operation.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private record SaleRow(UUID playerId, String status, long grossMinor, long progressDelta) {
    }

    private record StoredSaleLine(UUID batchId, int quantity) {
    }

    private record ExchangeRow(
            UUID playerId,
            String recipeKey,
            String status,
            long soulCost,
            RewardDefinition reward
    ) {
    }
}
