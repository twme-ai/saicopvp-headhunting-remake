package dev.saicoremake.headhunting.config;

import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.HeadKind;
import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.Money;
import dev.saicoremake.headhunting.domain.ProgressionMode;
import dev.saicoremake.headhunting.domain.RewardDefinition;
import dev.saicoremake.headhunting.domain.RewardType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigurationLoader {
    private static final List<String> DEFAULT_RESOURCES = List.of(
            "config.yml",
            "heads.yml",
            "levels.yml",
            "exchanges.yml",
            "locales/en_US.yml",
            "locales/zh_TW.yml"
    );

    public void installDefaults(JavaPlugin plugin) {
        for (String resource : DEFAULT_RESOURCES) {
            Path target = plugin.getDataFolder().toPath().resolve(resource);
            if (Files.notExists(target)) {
                plugin.saveResource(resource, false);
            }
        }
    }

    public PluginSettings load(Path dataDirectory) throws ConfigurationException {
        YamlConfiguration main = loadYaml(dataDirectory.resolve("config.yml"));
        Map<String, HeadDefinition> heads = loadHeads(loadYaml(dataDirectory.resolve("heads.yml")));
        List<LevelDefinition> levels = loadLevels(loadYaml(dataDirectory.resolve("levels.yml")), heads);
        validateHeadLevels(heads, levels.size());
        Map<String, ExchangeRecipe> exchanges = loadExchanges(
                loadYaml(dataDirectory.resolve("exchanges.yml")),
                heads
        );
        try {
            ProgressionMode mode = enumValue(
                    ProgressionMode.class,
                    requiredString(main, "mechanics.progression-mode"),
                    "mechanics.progression-mode"
            );
            long batchWindow = positiveLong(main, "mechanics.batch-window-seconds");
            int maximumPending = positiveInt(main, "mechanics.maximum-pending-mints");
            Set<CreatureSpawnEvent.SpawnReason> spawnReasons = enumSet(
                    CreatureSpawnEvent.SpawnReason.class,
                    main.getStringList("mechanics.allowed-spawn-reasons"),
                    "mechanics.allowed-spawn-reasons"
            );
            Set<EntityDamageEvent.DamageCause> damageCauses = enumSet(
                    EntityDamageEvent.DamageCause.class,
                    main.getStringList("mechanics.allowed-damage-causes"),
                    "mechanics.allowed-damage-causes"
            );
            List<Locale> locales = main.getStringList("localization.supported-locales")
                    .stream().map(ConfigurationLoader::parseLocale).toList();
            Locale defaultLocale = parseLocale(requiredString(main, "localization.default-locale"));
            PlayerHeadSettings playerHeads = loadPlayerHeadSettings(main);
            if (playerHeads.enabled()
                    && (heads.get("player") == null || heads.get("player").kind() != HeadKind.PLAYER)) {
                throw new IllegalArgumentException("Enabled player heads require a PLAYER definition named 'player'");
            }
            return new PluginSettings(
                    mode,
                    batchWindow,
                    maximumPending,
                    main.getBoolean("mechanics.right-click-sells", true),
                    main.getBoolean("mechanics.sell-signs-enabled", true),
                    new LinkedHashSet<>(main.getStringList("worlds.allowed")),
                    new LinkedHashSet<>(main.getStringList("worlds.blocked")),
                    spawnReasons,
                    damageCauses,
                    playerHeads,
                    defaultLocale,
                    locales,
                    heads,
                    levels,
                    exchanges
            );
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("Invalid config.yml: " + exception.getMessage(), exception);
        }
    }

    private static Map<String, HeadDefinition> loadHeads(YamlConfiguration yaml) throws ConfigurationException {
        ConfigurationSection root = requiredSection(yaml, "heads");
        Map<String, HeadDefinition> definitions = new LinkedHashMap<>();
        Map<String, String> entityOwners = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = requiredSection(root, key);
            try {
                String configuredKind = Objects.requireNonNull(section.getString("kind", "MOB"));
                HeadKind kind = enumValue(HeadKind.class, configuredKind, "heads." + key + ".kind");
                String entity = kind == HeadKind.MOB ? requiredString(section, "entity") : null;
                if (entity != null) {
                    enumValue(EntityType.class, entity, "heads." + key + ".entity");
                }
                String material = requiredString(section, "material");
                if (Material.matchMaterial(material) == null) {
                    throw new IllegalArgumentException("Unknown material " + material);
                }
                String textureUrl = Objects.requireNonNull(section.getString("texture-url", ""));
                validateTextureUrl(textureUrl);
                HeadDefinition definition = new HeadDefinition(
                        key,
                        kind,
                        entity,
                        material,
                        textureUrl,
                        section.getString("display-key", "head." + key),
                        money(section, "value"),
                        nonNegativeLong(section, "progress-points"),
                        section.getDouble("drop-chance", 1.0),
                        positiveInt(section, "minimum-level"),
                        nonNegativeLong(section, "soul-reward"),
                        money(section, "direct-money-reward")
                );
                if (definition.displayKey().isBlank()) {
                    throw new IllegalArgumentException("Display key cannot be blank");
                }
                if (entity != null) {
                    String existing = entityOwners.putIfAbsent(entity.toUpperCase(Locale.ROOT), key);
                    if (existing != null) {
                        throw new IllegalArgumentException(
                                "Entity " + entity + " is already assigned to head '" + existing + "'"
                        );
                    }
                }
                definitions.put(key, definition);
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException("Invalid head '" + key + "': " + exception.getMessage(), exception);
            }
        }
        return Map.copyOf(definitions);
    }

    private static List<LevelDefinition> loadLevels(
            YamlConfiguration yaml,
            Map<String, HeadDefinition> heads
    ) throws ConfigurationException {
        ConfigurationSection root = requiredSection(yaml, "levels");
        List<Integer> numbers;
        try {
            numbers = root.getKeys(false).stream().map(Integer::parseInt).sorted().toList();
        } catch (NumberFormatException exception) {
            throw new ConfigurationException("Level keys must be integers", exception);
        }
        if (numbers.isEmpty()) {
            throw new ConfigurationException("At least one level is required");
        }
        if (numbers.size() > 28) {
            throw new ConfigurationException("The level menu supports at most 28 levels");
        }
        List<LevelDefinition> levels = new ArrayList<>();
        for (int index = 0; index < numbers.size(); index++) {
            int number = numbers.get(index);
            if (number != index + 1) {
                throw new ConfigurationException("Levels must be contiguous and start at 1");
            }
            ConfigurationSection section = requiredSection(root, Integer.toString(number));
            Map<String, Long> kills = longMap(section.getConfigurationSection("kill-requirements"));
            List<String> progressHeads = section.getStringList("progress-heads");
            List<String> unlocks = section.getStringList("unlocks");
            validateHeadReferences(heads, kills.keySet(), "kill requirement", number);
            validateHeadReferences(heads, progressHeads, "progress head", number);
            validateHeadReferences(heads, unlocks, "unlock", number);
            for (String headKey : progressHeads) {
                if (heads.get(headKey).minimumLevel() > number) {
                    throw new ConfigurationException(
                            "Progress head '" + headKey + "' is locked until after level " + number
                    );
                }
            }
            List<RewardDefinition> rewards = loadRewards(section, "rewards", "level " + number);
            try {
                levels.add(new LevelDefinition(
                        number,
                        number == numbers.size(),
                        section.getString("tier", "HeadHunter"),
                        nonNegativeLong(section, "required-progress"),
                        money(section, "rank-up-cost"),
                        kills,
                        progressHeads,
                        unlocks,
                        rewards
                ));
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException("Invalid level " + number + ": " + exception.getMessage(), exception);
            }
        }
        return List.copyOf(levels);
    }

    private static Map<String, ExchangeRecipe> loadExchanges(
            YamlConfiguration yaml,
            Map<String, HeadDefinition> heads
    ) throws ConfigurationException {
        ConfigurationSection root = requiredSection(yaml, "exchanges");
        Map<String, ExchangeRecipe> exchanges = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = requiredSection(root, key);
            Map<String, Long> costs = longMap(requiredSection(section, "head-costs"));
            validateHeadReferences(heads, costs.keySet(), "exchange cost", key);
            List<RewardDefinition> rewards = loadRewards(section, "rewards", "exchange " + key);
            if (rewards.size() != 1) {
                throw new ConfigurationException("Exchange '" + key + "' must contain exactly one reward");
            }
            try {
                exchanges.put(key, new ExchangeRecipe(
                        key,
                        Objects.requireNonNull(section.getString("display-key", "exchange." + key)),
                        costs,
                        section.getLong("soul-cost", 0),
                        Objects.requireNonNull(rewards.getFirst())
                ));
            } catch (IllegalArgumentException exception) {
                String message = "Invalid exchange '" + key + "': " + exception.getMessage();
                throw new ConfigurationException(message, exception);
            }
        }
        return Map.copyOf(exchanges);
    }

    private static List<RewardDefinition> loadRewards(
            ConfigurationSection section,
            String path,
            String context
    ) throws ConfigurationException {
        List<RewardDefinition> rewards = new ArrayList<>();
        Set<String> rewardIds = new LinkedHashSet<>();
        int index = 0;
        for (Map<?, ?> reward : section.getMapList(path)) {
            index++;
            try {
                String id = String.valueOf(requiredMapValue(reward, "id"));
                if (!rewardIds.add(id)) {
                    throw new IllegalArgumentException("Duplicate reward id: " + id);
                }
                RewardType type = enumValue(
                        RewardType.class,
                        String.valueOf(requiredMapValue(reward, "type")),
                        context + " reward type"
                );
                Object configuredValue = reward.containsKey("value") ? reward.get("value") : "";
                Object configuredAmount = reward.containsKey("amount") ? reward.get("amount") : 1;
                String value = String.valueOf(configuredValue);
                long amount = Long.parseLong(String.valueOf(configuredAmount));
                if (type == RewardType.ITEM && (Material.matchMaterial(value) == null || amount < 1 || amount > 6400)) {
                    throw new IllegalArgumentException("ITEM rewards need a valid material and amount from 1 to 6400");
                }
                if (type == RewardType.COMMAND && value.isBlank()) {
                    throw new IllegalArgumentException("COMMAND rewards need a non-empty command");
                }
                rewards.add(new RewardDefinition(id, type, value, amount));
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException(
                        "Invalid reward " + index + " in " + context + ": " + exception.getMessage(),
                        exception
                );
            }
        }
        return List.copyOf(rewards);
    }

    private static PlayerHeadSettings loadPlayerHeadSettings(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("player-heads");
        if (section == null) {
            throw new IllegalArgumentException("Missing player-heads section");
        }
        return new PlayerHeadSettings(
                section.getBoolean("enabled", true),
                new BigDecimal(requiredString(section, "balance-fraction")),
                money(section, "minimum-victim-balance").minorUnits(),
                money(section, "maximum-head-value").minorUnits(),
                section.getBoolean("deduct-from-victim", true),
                section.getDouble("drop-chance", 1.0),
                nonNegativeLong(section, "killer-victim-cooldown-seconds"),
                nonNegativeLong(section, "victim-cooldown-seconds"),
                section.getBoolean("block-same-address", true)
        );
    }

    private static YamlConfiguration loadYaml(Path path) throws ConfigurationException {
        if (Files.notExists(path)) {
            throw new ConfigurationException("Missing configuration file: " + path.getFileName());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new ConfigurationException("Could not load " + path.getFileName(), exception);
        }
    }

    private static Money money(ConfigurationSection section, String path) {
        Object value = section.get(path);
        if (value == null) {
            throw new IllegalArgumentException("Missing money value: " + fullPath(section, path));
        }
        return Money.fromMajor(new BigDecimal(String.valueOf(value)));
    }

    private static String requiredString(ConfigurationSection section, String path) {
        String value = section.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing string: " + fullPath(section, path));
        }
        return value;
    }

    private static int positiveInt(ConfigurationSection section, String path) {
        int value = section.getInt(path, 0);
        if (value < 1) {
            throw new IllegalArgumentException(fullPath(section, path) + " must be positive");
        }
        return value;
    }

    private static long positiveLong(ConfigurationSection section, String path) {
        long value = section.getLong(path, 0);
        if (value < 1) {
            throw new IllegalArgumentException(fullPath(section, path) + " must be positive");
        }
        return value;
    }

    private static long nonNegativeLong(ConfigurationSection section, String path) {
        long value = section.getLong(path, 0);
        if (value < 0) {
            throw new IllegalArgumentException(fullPath(section, path) + " cannot be negative");
        }
        return value;
    }

    private static ConfigurationSection requiredSection(ConfigurationSection parent, String path)
            throws ConfigurationException {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new ConfigurationException("Missing section: " + fullPath(parent, path));
        }
        return section;
    }

    private static Map<String, Long> longMap(ConfigurationSection section) throws ConfigurationException {
        if (section == null) {
            return Map.of();
        }
        Map<String, Long> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            long value = section.getLong(key, -1);
            if (value < 0) {
                throw new ConfigurationException(fullPath(section, key) + " cannot be negative");
            }
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static void validateHeadReferences(
            Map<String, HeadDefinition> heads,
            Iterable<String> references,
            String kind,
            Object owner
    ) throws ConfigurationException {
        for (String key : references) {
            if (!heads.containsKey(key)) {
                throw new ConfigurationException("Unknown " + kind + " '" + key + "' in " + owner);
            }
        }
    }

    private static void validateHeadLevels(Map<String, HeadDefinition> heads, int levelCount)
            throws ConfigurationException {
        for (HeadDefinition definition : heads.values()) {
            if (definition.minimumLevel() > levelCount) {
                throw new ConfigurationException(
                        "Head '" + definition.key() + "' requires level " + definition.minimumLevel()
                                + " but only " + levelCount + " levels are configured"
                );
            }
        }
    }

    private static <E extends Enum<E>> Set<E> enumSet(
            Class<E> type,
            List<String> values,
            String path
    ) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(path + " cannot be empty");
        }
        Set<E> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(enumValue(type, value, path));
        }
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String path) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            String options = java.util.Arrays.stream(type.getEnumConstants())
                    .map(Enum::name).sorted(Comparator.naturalOrder()).toList().toString();
            throw new IllegalArgumentException(path + " has unknown value '" + value + "'; expected one of " + options);
        }
    }

    private static Locale parseLocale(String value) {
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        if (locale.getLanguage().isBlank()) {
            throw new IllegalArgumentException("Invalid locale: " + value);
        }
        return locale;
    }

    private static Object requiredMapValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing reward " + key);
        }
        return value;
    }

    private static void validateTextureUrl(String value) {
        if (value.isBlank()) {
            return;
        }
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Texture URL must be an absolute HTTPS URL");
        }
    }

    private static String fullPath(ConfigurationSection section, String path) {
        String current = section.getCurrentPath();
        return current == null || current.isBlank() ? path : current + "." + path;
    }
}
