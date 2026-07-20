package dev.saicoremake.headhunting.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.saicoremake.headhunting.domain.ProgressionMode;
import dev.saicoremake.headhunting.locale.TranslationBundle;
import dev.saicoremake.headhunting.locale.TranslationLoader;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ConfigurationResourcesTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");

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
}
