package dev.saicoremake.headhunting.item;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.HeadKind;
import dev.saicoremake.headhunting.domain.Money;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.security.HeadPayload;
import dev.saicoremake.headhunting.security.HeadSigner;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

public final class HeadItemCodec {
    private static final String MARKER_VALUE = "authenticated-head";
    private final Server server;
    private final ConfigurationService configuration;
    private final TranslationService translations;
    private final HeadSigner signer;
    private final NamespacedKey markerKey;
    private final NamespacedKey schemaKey;
    private final NamespacedKey batchKey;
    private final NamespacedKey headKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey ownerNameKey;
    private final NamespacedKey valueKey;
    private final NamespacedKey progressKey;
    private final NamespacedKey bucketKey;
    private final NamespacedKey signatureKey;

    public HeadItemCodec(
            JavaPlugin plugin,
            ConfigurationService configuration,
            TranslationService translations,
            HeadSigner signer
    ) {
        this.server = plugin.getServer();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.signer = Objects.requireNonNull(signer, "signer");
        markerKey = new NamespacedKey(plugin, "head_marker");
        schemaKey = new NamespacedKey(plugin, "head_schema");
        batchKey = new NamespacedKey(plugin, "head_batch");
        headKey = new NamespacedKey(plugin, "head_type");
        kindKey = new NamespacedKey(plugin, "head_kind");
        ownerKey = new NamespacedKey(plugin, "head_owner");
        ownerNameKey = new NamespacedKey(plugin, "head_owner_name");
        valueKey = new NamespacedKey(plugin, "head_value");
        progressKey = new NamespacedKey(plugin, "head_progress");
        bucketKey = new NamespacedKey(plugin, "head_bucket");
        signatureKey = new NamespacedKey(plugin, "head_signature");
    }

    public ItemStack create(
            HeadPayload payload,
            int quantity,
            Locale locale,
            String ownerName,
            PlayerProfile ownerProfile
    ) {
        if (!signer.verify(payload)) {
            throw new IllegalArgumentException("Cannot create an item from an invalid signature");
        }
        HeadDefinition definition = definition(payload);
        Material material = Objects.requireNonNull(Material.matchMaterial(definition.material()));
        ItemStack item = ItemStack.of(material, quantity);
        boolean edited = item.editPersistentDataContainer(container -> {
            container.set(markerKey, PersistentDataType.STRING, MARKER_VALUE);
            container.set(schemaKey, PersistentDataType.INTEGER, payload.schemaVersion());
            container.set(batchKey, PersistentDataType.STRING, payload.batchId().toString());
            container.set(headKey, PersistentDataType.STRING, payload.headKey());
            container.set(kindKey, PersistentDataType.STRING, payload.kind().name());
            if (payload.ownerId() != null) {
                container.set(ownerKey, PersistentDataType.STRING, payload.ownerId().toString());
            }
            if (ownerName != null && !ownerName.isBlank()) {
                container.set(ownerNameKey, PersistentDataType.STRING, ownerName);
            }
            container.set(valueKey, PersistentDataType.LONG, payload.unitValueMinor());
            container.set(progressKey, PersistentDataType.LONG, payload.progressPoints());
            container.set(bucketKey, PersistentDataType.LONG, payload.mintedBucket());
            container.set(signatureKey, PersistentDataType.BYTE_ARRAY, payload.signature());
        });
        if (!edited) {
            throw new IllegalStateException("Could not attach signed head data to the item");
        }
        applyDisplay(item, payload, locale, ownerName, ownerProfile);
        return item;
    }

    public DecodedHead decode(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return DecodedHead.notAHead();
        }
        PersistentDataContainerView view = item.getPersistentDataContainer();
        if (!MARKER_VALUE.equals(view.get(markerKey, PersistentDataType.STRING))) {
            return DecodedHead.notAHead();
        }
        try {
            Integer schema = view.get(schemaKey, PersistentDataType.INTEGER);
            String batch = view.get(batchKey, PersistentDataType.STRING);
            String head = view.get(headKey, PersistentDataType.STRING);
            String kind = view.get(kindKey, PersistentDataType.STRING);
            String owner = view.get(ownerKey, PersistentDataType.STRING);
            Long value = view.get(valueKey, PersistentDataType.LONG);
            Long progress = view.get(progressKey, PersistentDataType.LONG);
            Long bucket = view.get(bucketKey, PersistentDataType.LONG);
            byte[] signature = view.get(signatureKey, PersistentDataType.BYTE_ARRAY);
            if (schema == null || batch == null || head == null || kind == null
                    || value == null || progress == null || bucket == null || signature == null) {
                return DecodedHead.invalid();
            }
            HeadPayload payload = new HeadPayload(
                    schema,
                    UUID.fromString(batch),
                    head,
                    HeadKind.valueOf(kind),
                    owner == null ? null : UUID.fromString(owner),
                    value,
                    progress,
                    bucket,
                    signature
            );
            if (!signer.verify(payload)) {
                return DecodedHead.invalid();
            }
            return DecodedHead.valid(payload, view.get(ownerNameKey, PersistentDataType.STRING));
        } catch (IllegalArgumentException exception) {
            return DecodedHead.invalid();
        }
    }

    public boolean relocalize(ItemStack item, Locale locale) {
        DecodedHead decoded = decode(item);
        if (decoded.status() != HeadDecodeStatus.VALID) {
            return false;
        }
        applyDisplay(item, decoded.payload(), locale, decoded.ownerName(), null);
        return true;
    }

    private void applyDisplay(
            ItemStack item,
            HeadPayload payload,
            Locale locale,
            String ownerName,
            PlayerProfile ownerProfile
    ) {
        HeadDefinition definition = definition(payload);
        Component translatedHead = translations.render(locale, definition.displayKey());
        Component name;
        if (payload.kind() == HeadKind.PLAYER) {
            String displayOwner = ownerName == null ? payload.ownerId().toString() : ownerName;
            name = translations.render(locale, "item.player-head-name", Placeholder.unparsed("owner", displayOwner));
        } else {
            name = translations.render(locale, "item.head-name", Placeholder.component("head", translatedHead));
        }
        List<Component> lore = new ArrayList<>();
        lore.add(translations.render(
                locale,
                "item.value",
                Placeholder.unparsed("value", translations.formatMoney(locale, payload.unitValueMinor()))
        ));
        if (payload.progressPoints() > 0) {
            lore.add(translations.render(
                    locale,
                    "item.progress",
                    Placeholder.unparsed("amount", translations.formatDecimal(locale, payload.progressPoints()))
            ));
        }
        if (payload.kind() == HeadKind.PLAYER) {
            String displayOwner = ownerName == null ? payload.ownerId().toString() : ownerName;
            lore.add(translations.render(locale, "item.owner", Placeholder.unparsed("owner", displayOwner)));
        }
        lore.add(translations.render(locale, "item.authentic"));
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            if (meta instanceof SkullMeta skullMeta) {
                applyProfile(skullMeta, definition, payload, ownerName, ownerProfile);
            }
        });
    }

    private void applyProfile(
            SkullMeta skullMeta,
            HeadDefinition definition,
            HeadPayload payload,
            String ownerName,
            PlayerProfile ownerProfile
    ) {
        if (ownerProfile != null) {
            skullMeta.setPlayerProfile(ownerProfile.clone());
            return;
        }
        if (!definition.textureUrl().isBlank()) {
            try {
                byte[] profileSource = ("headhunting:" + definition.key())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                UUID profileId = UUID.nameUUIDFromBytes(profileSource);
                String profileName = definition.key().substring(0, Math.min(16, definition.key().length()));
                PlayerProfile profile = server.createProfile(profileId, profileName);
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(definition.textureUrl()).toURL());
                profile.setTextures(textures);
                skullMeta.setPlayerProfile(profile);
                return;
            } catch (MalformedURLException | IllegalArgumentException ignored) {
                // Configuration validation normally prevents this fallback path.
            }
        }
        if (payload.kind() == HeadKind.PLAYER && ownerName != null) {
            skullMeta.setPlayerProfile(server.createProfile(payload.ownerId(), ownerName));
        }
    }

    private HeadDefinition definition(HeadPayload payload) {
        HeadDefinition configured = configuration.current().heads().get(payload.headKey());
        if (configured != null) {
            return configured;
        }
        return new HeadDefinition(
                payload.headKey(),
                payload.kind(),
                null,
                Material.PLAYER_HEAD.name(),
                "",
                "head." + payload.headKey(),
                new Money(payload.unitValueMinor()),
                payload.progressPoints(),
                0,
                1,
                0,
                Money.ZERO
        );
    }
}
