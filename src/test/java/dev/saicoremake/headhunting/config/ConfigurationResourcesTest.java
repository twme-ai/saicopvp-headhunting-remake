package dev.saicoremake.headhunting.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.saicoremake.headhunting.domain.ProgressionMode;
import dev.saicoremake.headhunting.locale.TranslationBundle;
import dev.saicoremake.headhunting.locale.TranslationLoader;
import dev.saicoremake.headhunting.locale.TranslationService;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationResourcesTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void defaultGameplayConfigurationIsCompleteAndConsistent() throws ConfigurationException {
        PluginSettings settings = new ConfigurationLoader().load(RESOURCES);

        assertEquals(ProgressionMode.SELL_HEADS, settings.progressionMode());
        assertEquals(25, settings.levels().size());
        assertFalse(settings.level(1).terminal());
        assertTrue(settings.level(25).terminal());
        assertEquals(1, settings.heads().get("pig").minimumLevel());
        assertEquals(25, settings.heads().get("skeleton_horse").minimumLevel());
        assertTrue(settings.exchanges().containsKey("legendary_mask"));
    }

    @Test
    void everyEnglishTranslationHasATraditionalChineseTranslation() throws ConfigurationException {
        PluginSettings settings = new ConfigurationLoader().load(RESOURCES);
        TranslationBundle translations = new TranslationLoader().load(RESOURCES, settings);

        assertEquals(
                translations.translations().get(Locale.US).keySet(),
                translations.translations().get(Locale.TAIWAN).keySet()
        );
    }

    @Test
    void runtimeConfigurationReplacesSettingsAndTranslationsAsOneSnapshot() throws ConfigurationException {
        PluginSettings settings = new ConfigurationLoader().load(RESOURCES);
        TranslationBundle translations = new TranslationLoader().load(RESOURCES, settings);
        RuntimeConfiguration runtime = new RuntimeConfiguration(settings, translations);
        ConfigurationService configurationService = new ConfigurationService(runtime);
        TranslationService translationService = new TranslationService(runtime);
        TranslationBundle replacement = new TranslationBundle(
                Locale.US,
                Map.of(
                        Locale.US, Map.of("probe", "<green>replacement</green>"),
                        Locale.TAIWAN, Map.of("probe", "<green>replacement</green>")
                )
        );

        runtime.replace(settings, replacement);

        assertEquals(settings, configurationService.current());
        assertEquals(
                "replacement",
                PlainTextComponentSerializer.plainText().serialize(translationService.render(Locale.US, "probe"))
        );
    }

    @Test
    void reloadCannotShrinkBelowAStoredProfileLevel() throws ConfigurationException {
        PluginSettings settings = new ConfigurationLoader().load(RESOURCES);

        ReloadService.validateLevelCapacity(settings, 25);
        assertThrows(IllegalStateException.class, () -> ReloadService.validateLevelCapacity(settings, 26));
    }

    @Test
    void missingRequiredDefaultTranslationIsRejected() throws Exception {
        copyResources();
        Path englishPath = temporaryDirectory.resolve("locales/en_US.yml");
        YamlConfiguration english = YamlConfiguration.loadConfiguration(englishPath.toFile());
        english.set("sell.success", null);
        english.save(englishPath.toFile());
        PluginSettings settings = new ConfigurationLoader().load(temporaryDirectory);

        assertThrows(
                ConfigurationException.class,
                () -> new TranslationLoader().load(temporaryDirectory, settings)
        );
    }

    @Test
    void duplicateLevelRewardIdsAreRejected() throws Exception {
        copyResources();
        Path levelsPath = temporaryDirectory.resolve("levels.yml");
        YamlConfiguration levels = YamlConfiguration.loadConfiguration(levelsPath.toFile());
        levels.set("levels.1.rewards", List.of(
                Map.of("id", "duplicate", "type", "SOULS", "amount", 1),
                Map.of("id", "duplicate", "type", "SOULS", "amount", 2)
        ));
        levels.save(levelsPath.toFile());

        assertThrows(ConfigurationException.class, () -> new ConfigurationLoader().load(temporaryDirectory));
    }

    @Test
    void aProgressHeadCannotBeLockedBeyondItsLevel() throws Exception {
        copyResources();
        Path headsPath = temporaryDirectory.resolve("heads.yml");
        YamlConfiguration heads = YamlConfiguration.loadConfiguration(headsPath.toFile());
        heads.set("heads.pig.minimum-level", 2);
        heads.save(headsPath.toFile());

        assertThrows(ConfigurationException.class, () -> new ConfigurationLoader().load(temporaryDirectory));
    }

    private void copyResources() throws Exception {
        for (String file : List.of("config.yml", "heads.yml", "levels.yml", "exchanges.yml")) {
            Files.copy(RESOURCES.resolve(file), temporaryDirectory.resolve(file));
        }
        Path locales = Files.createDirectories(temporaryDirectory.resolve("locales"));
        Files.copy(RESOURCES.resolve("locales/en_US.yml"), locales.resolve("en_US.yml"));
        Files.copy(RESOURCES.resolve("locales/zh_TW.yml"), locales.resolve("zh_TW.yml"));
    }
}
