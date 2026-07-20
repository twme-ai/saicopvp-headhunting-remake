package dev.saicoremake.headhunting.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.saicoremake.headhunting.api.event.AuthenticatedHeadMintedEvent;
import dev.saicoremake.headhunting.api.event.HeadMintEvent;
import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.config.PlayerHeadSettings;
import dev.saicoremake.headhunting.config.PluginSettings;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.HeadKind;
import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.security.BatchIds;
import dev.saicoremake.headhunting.security.HeadPayload;
import dev.saicoremake.headhunting.security.HeadSigner;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.DeliveryTarget;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.MintBatch;
import dev.saicoremake.headhunting.storage.PendingDelivery;
import dev.saicoremake.headhunting.storage.PlayerHeadMintResult;
import dev.saicoremake.headhunting.storage.PlayerHeadMintStatus;
import dev.saicoremake.headhunting.storage.ProgressUpdate;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadMintService {
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final HeadStore store;
    private final PlayerSessionService sessions;
    private final HeadItemCodec itemCodec;
    private final HeadSigner signer;
    private final TranslationService translations;
    private final Clock clock;
    private final Logger logger;
    private final AtomicInteger pendingMints = new AtomicInteger();

    public HeadMintService(
            JavaPlugin plugin,
            ConfigurationService configuration,
            HeadStore store,
            PlayerSessionService sessions,
            HeadItemCodec itemCodec,
            HeadSigner signer,
            TranslationService translations,
            Clock clock
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = plugin.getLogger();
    }

    public void processMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        Player killer = entity.getKiller();
        PluginSettings settings = configuration.current();
        if (killer == null || !settings.isWorldAllowed(entity.getWorld().getName())) {
            return;
        }
        EntityDamageEvent lastDamage = entity.getLastDamageCause();
        if (!settings.allowedSpawnReasons().contains(entity.getEntitySpawnReason())
                || lastDamage == null
                || !settings.allowedDamageCauses().contains(lastDamage.getCause())) {
            return;
        }
        HeadDefinition definition = settings.heads().values().stream()
                .filter(candidate -> candidate.kind() == HeadKind.MOB)
                .filter(candidate -> entity.getType().name().equals(candidate.entityType()))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            return;
        }
        processKillCredit(killer, definition);
        if (ThreadLocalRandom.current().nextDouble() >= definition.dropChance()) {
            return;
        }
        HeadMintEvent mintEvent = new HeadMintEvent(killer, entity, definition, 1);
        plugin.getServer().getPluginManager().callEvent(mintEvent);
        if (mintEvent.isCancelled()) {
            return;
        }
        Location location = entity.getLocation().clone();
        long bucket = clock.instant().getEpochSecond() / settings.batchWindowSeconds();
        UUID batchId = BatchIds.mobBatch(killer.getUniqueId(), definition, bucket);
        HeadPayload payload = signer.sign(new HeadPayload(
                HeadPayload.CURRENT_SCHEMA,
                batchId,
                definition.key(),
                HeadKind.MOB,
                null,
                definition.unitValue().minorUnits(),
                definition.progressPoints(),
                bucket,
                new byte[0]
        ));
        DeliveryTarget target = target(killer, location, null);
        enqueueMint(killer, new MintBatch(payload, mintEvent.quantity()), target, null);
    }

    public void processPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        PluginSettings settings = configuration.current();
        PlayerHeadSettings playerHeads = settings.playerHeads();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId()) || !playerHeads.enabled()
                || !settings.isWorldAllowed(victim.getWorld().getName())) {
            return;
        }
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        if (lastDamage == null
                || !settings.allowedDamageCauses().contains(lastDamage.getCause())
                || ThreadLocalRandom.current().nextDouble() >= playerHeads.dropChance()
                || playerHeads.blockSameAddress() && sameAddress(killer.getAddress(), victim.getAddress())) {
            return;
        }
        if (!acquireQueueSlot(killer)) {
            return;
        }
        UUID deathId = UUID.randomUUID();
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        long bucket = clock.instant().getEpochSecond();
        Location location = victim.getLocation().clone();
        DeliveryTarget target = target(killer, location, victim.getName());
        PlayerProfile ownerProfile = victim.getPlayerProfile().clone();
        CompletableFuture<?> killerLoad = sessions.ensureLoaded(killer);
        CompletableFuture<?> victimLoad = sessions.ensureLoaded(victim);
        killerLoad.thenCombine(victimLoad, (killerProfile, victimProfile) -> killerProfile)
                .thenCompose(ignored -> store.preparePlayerHeadMint(
                        deathId,
                        killerId,
                        victimId,
                        bucket,
                        playerHeads,
                        target
                ))
                .whenComplete((result, failure) -> {
                    pendingMints.decrementAndGet();
                    if (failure != null) {
                        reportFailure(killerId, "Could not mint player head", failure);
                        return;
                    }
                    sessions.update(result.victimProfile());
                    if (result.status() != PlayerHeadMintStatus.MINTED) {
                        return;
                    }
                    PendingDelivery signedDelivery = sign(result.delivery());
                    scheduleDelivery(signedDelivery, ownerProfile, false);
                });
    }

    public void recoverPendingDeliveries() {
        store.findPendingDeliveries().whenComplete((deliveries, failure) -> {
            if (failure != null) {
                logger.log(Level.SEVERE, "Could not load pending head deliveries", failure);
                return;
            }
            for (PendingDelivery delivery : deliveries) {
                scheduleDelivery(sign(delivery), null, true);
            }
        });
    }

    public CompletableFuture<Void> mintAdministrative(Player recipient, String headKey, int quantity) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Administrative mint must be initiated on the main thread")
            );
        }
        if (quantity < 1 || quantity > 64) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Mint quantity must be from 1 to 64"));
        }
        HeadDefinition definition = configuration.current().heads().get(headKey);
        if (definition == null || definition.kind() != HeadKind.MOB) {
            IllegalArgumentException failure = new IllegalArgumentException(
                    "Unknown mintable mob head: " + headKey
            );
            return CompletableFuture.failedFuture(failure);
        }
        long bucket = clock.instant().getEpochSecond() / configuration.current().batchWindowSeconds();
        HeadPayload payload = signer.sign(new HeadPayload(
                HeadPayload.CURRENT_SCHEMA,
                BatchIds.mobBatch(recipient.getUniqueId(), definition, bucket),
                definition.key(),
                HeadKind.MOB,
                null,
                definition.unitValue().minorUnits(),
                definition.progressPoints(),
                bucket,
                new byte[0]
        ));
        DeliveryTarget target = target(recipient, recipient.getLocation().clone(), null);
        return enqueueMint(recipient, new MintBatch(payload, quantity), target, null);
    }

    private void processKillCredit(Player killer, HeadDefinition definition) {
        PluginSettings settings = configuration.current();
        UUID eventId = UUID.randomUUID();
        UUID killerId = killer.getUniqueId();
        sessions.ensureLoaded(killer).thenCompose(profile -> {
            KillCredit credit = KillCreditPolicy.calculate(settings, profile, definition);
            if (!credit.recordsEvent()) {
                return CompletableFuture.completedFuture(new ProgressUpdate(false, profile));
            }
            return store.applyProgressEvent(
                    eventId,
                    profile.playerId(),
                    "MOB_KILL",
                    definition.key(),
                    credit.progress(),
                    credit.souls(),
                    credit.money()
            );
        }).whenComplete((update, failure) -> {
            if (failure != null) {
                reportFailure(killerId, "Could not apply mob kill credit", failure);
            } else {
                sessions.update(update.profile());
            }
        });
    }

    private CompletableFuture<Void> enqueueMint(
            Player recipient,
            MintBatch mint,
            DeliveryTarget target,
            PlayerProfile ownerProfile
    ) {
        if (!acquireQueueSlot(recipient)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Mint queue is full"));
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        UUID recipientId = recipient.getUniqueId();
        store.recordMintDelivery(mint, target).whenComplete((delivery, failure) -> {
            pendingMints.decrementAndGet();
            if (failure != null) {
                reportFailure(recipientId, "Could not record head mint", failure);
                completion.completeExceptionally(failure);
                return;
            }
            scheduleDelivery(delivery, ownerProfile, false);
            completion.complete(null);
        });
        return completion;
    }

    private boolean acquireQueueSlot(Player player) {
        int pending = pendingMints.incrementAndGet();
        if (pending <= configuration.current().maximumPendingMints()) {
            return true;
        }
        pendingMints.decrementAndGet();
        player.sendMessage(translations.render(sessions.locale(player), "drop.queue-full"));
        return false;
    }

    private void scheduleDelivery(PendingDelivery delivery, PlayerProfile ownerProfile, boolean recovery) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> deliver(delivery, ownerProfile, recovery));
    }

    private void deliver(PendingDelivery delivery, PlayerProfile ownerProfile, boolean recovery) {
        if (!plugin.isEnabled()) {
            return;
        }
        DeliveryTarget target = delivery.target();
        Player recipient = plugin.getServer().getPlayer(target.recipientId());
        World world = plugin.getServer().getWorld(target.worldId());
        if (world == null && recipient == null) {
            return;
        }
        Locale locale = recipient == null
                ? configuration.current().defaultLocale() : sessions.locale(recipient);
        ItemStack item = itemCodec.create(
                delivery.payload(),
                delivery.quantity(),
                locale,
                target.ownerName(),
                ownerProfile
        );
        if (recovery && recipient != null) {
            MapDelivery.giveOrDrop(recipient, item);
        } else if (world != null) {
            world.dropItemNaturally(new Location(world, target.x(), target.y(), target.z()), item);
        } else {
            MapDelivery.giveOrDrop(Objects.requireNonNull(recipient), item);
        }
        if (recipient != null) {
            plugin.getServer().getPluginManager().callEvent(new AuthenticatedHeadMintedEvent(
                    recipient,
                    delivery.payload(),
                    delivery.quantity()
            ));
            String key = delivery.payload().kind() == HeadKind.PLAYER ? "drop.player" : "drop.mob";
            if (delivery.payload().kind() == HeadKind.PLAYER) {
                recipient.sendMessage(translations.render(
                        locale,
                        key,
                        Placeholder.unparsed("victim", target.ownerName()),
                        Placeholder.unparsed(
                                "value",
                                translations.formatMoney(locale, delivery.payload().unitValueMinor())
                        )
                ));
            } else {
                recipient.sendMessage(translations.render(
                        locale,
                        key,
                        Placeholder.component(
                                "head",
                                translations.render(locale, headDisplayKey(delivery.payload().headKey()))
                        )
                ));
            }
        }
        store.markDeliveryComplete(target.deliveryId()).exceptionally(failure -> {
            logger.log(Level.SEVERE, "Could not mark head delivery complete: " + target.deliveryId(), failure);
            return null;
        });
    }

    private PendingDelivery sign(PendingDelivery delivery) {
        return new PendingDelivery(signer.sign(delivery.payload()), delivery.quantity(), delivery.target());
    }

    private String headDisplayKey(String key) {
        HeadDefinition definition = configuration.current().heads().get(key);
        return definition == null ? "head." + key : definition.displayKey();
    }

    private DeliveryTarget target(Player recipient, Location location, String ownerName) {
        return new DeliveryTarget(
                UUID.randomUUID(),
                recipient.getUniqueId(),
                ownerName,
                Objects.requireNonNull(location.getWorld()).getUID(),
                location.getX(),
                location.getY(),
                location.getZ()
        );
    }

    private void reportFailure(UUID recipientId, String message, Throwable failure) {
        logger.log(Level.SEVERE, message, failure);
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(recipientId);
            if (player != null) {
                player.sendMessage(translations.render(sessions.locale(player), "error.internal"));
            }
        });
    }

    private static boolean sameAddress(InetSocketAddress left, InetSocketAddress right) {
        if (left == null || right == null) {
            return false;
        }
        InetAddress leftAddress = left.getAddress();
        InetAddress rightAddress = right.getAddress();
        return leftAddress != null && leftAddress.equals(rightAddress);
    }
}
