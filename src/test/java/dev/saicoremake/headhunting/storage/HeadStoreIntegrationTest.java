package dev.saicoremake.headhunting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.saicoremake.headhunting.domain.HeadKind;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.domain.Money;
import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.RewardDefinition;
import dev.saicoremake.headhunting.domain.RewardType;
import dev.saicoremake.headhunting.config.PlayerHeadSettings;
import dev.saicoremake.headhunting.config.ExchangeRecipe;
import dev.saicoremake.headhunting.security.HeadPayload;
import dev.saicoremake.headhunting.security.HeadSigner;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadStoreIntegrationTest {
    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BATCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    private Path temporaryDirectory;
    private SqliteDatabase database;
    private HeadStore store;
    private HeadPayload payload;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabase(temporaryDirectory.resolve("test.db"), Logger.getAnonymousLogger());
        database.start().join();
        store = new HeadStore(database, CLOCK);
        HeadSigner signer = new HeadSigner(new byte[32]);
        payload = signer.sign(new HeadPayload(
                HeadPayload.CURRENT_SCHEMA,
                BATCH_ID,
                "zombie",
                HeadKind.MOB,
                null,
                100,
                2,
                42,
                new byte[0]
        ));
        store.loadOrCreateProfile(PLAYER_ID, "TestPlayer", Locale.US).join();
        store.recordMint(new MintBatch(payload, 5)).join();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void copiedItemsCannotRedeemBeyondMintedQuantity() {
        UUID firstSale = UUID.fromString("33333333-3333-3333-3333-333333333333");
        store.reserveSale(firstSale, PLAYER_ID, List.of(new SaleLine(payload, 3, true))).join();
        PlayerProfile paid = store.finalizeSale(firstSale).join();

        assertEquals(300, paid.balance().minorUnits());
        assertEquals(6, paid.progress());

        PlayerProfile idempotent = store.finalizeSale(firstSale).join();
        assertEquals(300, idempotent.balance().minorUnits());
        assertEquals(6, idempotent.progress());

        UUID copiedSale = UUID.fromString("44444444-4444-4444-4444-444444444444");
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> store.reserveSale(copiedSale, PLAYER_ID, List.of(new SaleLine(payload, 3, true))).join()
        );
        assertEquals(DatabaseException.class, failure.getCause().getClass());
    }

    @Test
    void cancellationReturnsReservedSupply() {
        UUID cancelledSale = UUID.fromString("55555555-5555-5555-5555-555555555555");
        store.reserveSale(cancelledSale, PLAYER_ID, List.of(new SaleLine(payload, 5, false))).join();
        assertEquals(1, store.findReservedSales(PLAYER_ID).join().size());

        store.cancelSale(cancelledSale).join();

        UUID replacementSale = UUID.fromString("66666666-6666-6666-6666-666666666666");
        store.reserveSale(replacementSale, PLAYER_ID, List.of(new SaleLine(payload, 5, false))).join();
        PlayerProfile paid = store.finalizeSale(replacementSale).join();
        assertEquals(500, paid.balance().minorUnits());
        assertEquals(0, paid.progress());
    }

    @Test
    void mintMetadataCollisionIsRejected() {
        HeadPayload changed = new HeadPayload(
                payload.schemaVersion(),
                payload.batchId(),
                payload.headKey(),
                payload.kind(),
                payload.ownerId(),
                101,
                payload.progressPoints(),
                payload.mintedBucket(),
                payload.signature()
        );

        assertThrows(CompletionException.class, () -> store.recordMint(new MintBatch(changed, 1)).join());
    }

    @Test
    void deliveryOutboxRemainsPendingUntilAcknowledged() {
        UUID deliveryId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        DeliveryTarget target = new DeliveryTarget(
                deliveryId,
                PLAYER_ID,
                null,
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                1.5,
                64,
                -2.5
        );
        store.recordMintDelivery(new MintBatch(payload, 1), target).join();

        assertEquals(1, store.findPendingDeliveries().join().size());

        store.markDeliveryComplete(deliveryId).join();
        assertEquals(0, store.findPendingDeliveries().join().size());
    }

    @Test
    void playerHeadValueIsEscrowedAndCooldownIsDurable() {
        UUID victimId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        store.loadOrCreateProfile(victimId, "Victim", Locale.US).join();
        store.applyProgressEvent(
                UUID.randomUUID(),
                victimId,
                "TEST_BALANCE",
                "player",
                0,
                0,
                new Money(1_000_000)
        ).join();
        PlayerHeadSettings settings = new PlayerHeadSettings(
                true,
                new BigDecimal("0.20"),
                0,
                0,
                true,
                1.0,
                3600,
                3600,
                true
        );
        DeliveryTarget target = new DeliveryTarget(
                UUID.randomUUID(),
                PLAYER_ID,
                "Victim",
                UUID.randomUUID(),
                0,
                64,
                0
        );

        PlayerHeadMintResult minted = store.preparePlayerHeadMint(
                UUID.randomUUID(),
                PLAYER_ID,
                victimId,
                123,
                settings,
                target
        ).join();

        assertEquals(PlayerHeadMintStatus.MINTED, minted.status());
        assertEquals(200_000, minted.delivery().payload().unitValueMinor());
        assertEquals(800_000, minted.victimProfile().balance().minorUnits());

        PlayerHeadMintResult blocked = store.preparePlayerHeadMint(
                UUID.randomUUID(),
                PLAYER_ID,
                victimId,
                124,
                settings,
                new DeliveryTarget(UUID.randomUUID(), PLAYER_ID, "Victim", UUID.randomUUID(), 0, 64, 0)
        ).join();
        assertEquals(PlayerHeadMintStatus.PAIR_COOLDOWN, blocked.status());
    }

    @Test
    void rankUpAndBuiltInRewardsCommitAtomically() {
        store.applyProgressEvent(
                UUID.randomUUID(),
                PLAYER_ID,
                "TEST_LEVEL",
                "pig",
                100,
                0,
                new Money(2_000)
        ).join();
        LevelDefinition level = new LevelDefinition(
                1,
                false,
                "Basic",
                100,
                new Money(1_000),
                Map.of("pig", 1L),
                List.of("pig"),
                List.of("pig"),
                List.of(
                        new RewardDefinition("balance", RewardType.BALANCE, "", 500),
                        new RewardDefinition("souls", RewardType.SOULS, "", 20),
                        new RewardDefinition("item", RewardType.ITEM, "DIAMOND", 1)
                )
        );

        RankUpResult result = store.rankUp(PLAYER_ID, level).join();

        assertEquals(RankUpStatus.SUCCESS, result.status());
        assertEquals(2, result.profile().level());
        assertEquals(1_500, result.profile().balance().minorUnits());
        assertEquals(20, result.profile().souls());
        assertEquals(1, store.findPendingRewards(PLAYER_ID).join().size());

        PendingReward claimed = store.claimReward("" + PLAYER_ID + ":1:item", PLAYER_ID).join();
        assertEquals("DELIVERING", claimed.status());
        store.completeReward(claimed.rewardKey(), PLAYER_ID).join();
        assertEquals(0, store.findPendingRewards(PLAYER_ID).join().size());
    }

    @Test
    void exchangeCancellationRestoresSoulsAndFinalizationConsumesSupply() {
        store.applyProgressEvent(
                UUID.randomUUID(),
                PLAYER_ID,
                "TEST_SOULS",
                "zombie",
                0,
                1_000,
                Money.ZERO
        ).join();
        ExchangeRecipe recipe = new ExchangeRecipe(
                "test_exchange",
                "exchange.charm",
                Map.of("zombie", 4L),
                100,
                new RewardDefinition("exchange_item", RewardType.ITEM, "DIAMOND", 1)
        );
        UUID cancelledId = UUID.randomUUID();
        store.reserveExchange(
                cancelledId,
                PLAYER_ID,
                recipe,
                List.of(new SaleLine(payload, 4, false))
        ).join();
        assertEquals(900, store.findProfile(PLAYER_ID).join().souls());

        PlayerProfile restored = store.cancelExchange(cancelledId).join();
        assertEquals(1_000, restored.souls());

        UUID completedId = UUID.randomUUID();
        store.reserveExchange(
                completedId,
                PLAYER_ID,
                recipe,
                List.of(new SaleLine(payload, 4, false))
        ).join();
        PlayerProfile finalized = store.finalizeExchange(completedId).join();

        assertEquals(900, finalized.souls());
        assertEquals(1, store.findPendingRewards(PLAYER_ID).join().size());
        assertThrows(
                CompletionException.class,
                () -> store.reserveExchange(
                        UUID.randomUUID(),
                        PLAYER_ID,
                        recipe,
                        List.of(new SaleLine(payload, 2, false))
                ).join()
        );
    }

    @Test
    void builtInExchangeRewardsCommitWithoutUsingTheDeliveryOutbox() {
        ExchangeRecipe balanceRecipe = new ExchangeRecipe(
                "balance_exchange",
                "exchange.charm",
                Map.of("zombie", 1L),
                0,
                new RewardDefinition("balance", RewardType.BALANCE, "", 250)
        );
        UUID balanceExchange = UUID.randomUUID();
        store.reserveExchange(
                balanceExchange,
                PLAYER_ID,
                balanceRecipe,
                List.of(new SaleLine(payload, 1, false))
        ).join();

        PlayerProfile afterBalance = store.finalizeExchange(balanceExchange).join();

        assertEquals(250, afterBalance.balance().minorUnits());
        assertEquals(0, store.findPendingRewards(PLAYER_ID).join().size());

        ExchangeRecipe soulRecipe = new ExchangeRecipe(
                "soul_exchange",
                "exchange.charm",
                Map.of("zombie", 1L),
                0,
                new RewardDefinition("souls", RewardType.SOULS, "", 75)
        );
        UUID soulExchange = UUID.randomUUID();
        store.reserveExchange(
                soulExchange,
                PLAYER_ID,
                soulRecipe,
                List.of(new SaleLine(payload, 1, false))
        ).join();

        PlayerProfile afterSouls = store.finalizeExchange(soulExchange).join();

        assertEquals(75, afterSouls.souls());
        assertEquals(0, store.findPendingRewards(PLAYER_ID).join().size());
    }

    @Test
    void profileCounterOverflowRollsBackTheProgressEvent() {
        store.applyProgressEvent(
                UUID.randomUUID(),
                PLAYER_ID,
                "TEST_BALANCE_MAX",
                "zombie",
                0,
                0,
                new Money(Long.MAX_VALUE)
        ).join();

        assertThrows(
                CompletionException.class,
                () -> store.applyProgressEvent(
                        UUID.randomUUID(),
                        PLAYER_ID,
                        "TEST_BALANCE_OVERFLOW",
                        "zombie",
                        0,
                        0,
                        new Money(1)
                ).join()
        );
        assertEquals(Long.MAX_VALUE, store.findProfile(PLAYER_ID).join().balance().minorUnits());
    }

    @Test
    void saleOverflowLeavesTheReservationRecoverable() {
        store.applyProgressEvent(
                UUID.randomUUID(),
                PLAYER_ID,
                "TEST_BALANCE_MAX",
                "zombie",
                0,
                0,
                new Money(Long.MAX_VALUE)
        ).join();
        UUID saleId = UUID.randomUUID();
        store.reserveSale(saleId, PLAYER_ID, List.of(new SaleLine(payload, 1, false))).join();

        assertThrows(CompletionException.class, () -> store.finalizeSale(saleId).join());

        assertEquals(Long.MAX_VALUE, store.findProfile(PLAYER_ID).join().balance().minorUnits());
        assertEquals(1, store.findReservedSales(PLAYER_ID).join().size());
    }

    @Test
    void maximumProfileLevelReflectsStoredProfiles() {
        assertEquals(1, store.findMaximumProfileLevel().join());

        store.setLevel(PLAYER_ID, 12, false).join();

        assertEquals(12, store.findMaximumProfileLevel().join());
    }
}
