package dev.saicoremake.headhunting.locale;

import dev.saicoremake.headhunting.config.ConfigurationException;
import dev.saicoremake.headhunting.config.PluginSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TranslationLoader {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?:\\u00a7|&)[0-9A-FK-OR]", Pattern.CASE_INSENSITIVE);
    private final MiniMessage miniMessage = MiniMessage.builder().strict(true).build();

    public TranslationBundle load(Path dataDirectory, PluginSettings settings) throws ConfigurationException {
        Map<Locale, Map<String, String>> translations = new LinkedHashMap<>();
        for (Locale locale : settings.supportedLocales()) {
            Path path = dataDirectory.resolve("locales").resolve(fileName(locale));
            translations.put(locale, loadFile(path));
        }
        Map<String, String> defaults = translations.get(settings.defaultLocale());
        if (defaults == null || defaults.isEmpty()) {
            throw new ConfigurationException("Default locale has no messages");
        }
        return new TranslationBundle(settings.defaultLocale(), translations);
    }

    private Map<String, String> loadFile(Path path) throws ConfigurationException {
        if (Files.notExists(path)) {
            throw new ConfigurationException("Missing locale file: " + path.getFileName());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            throw new ConfigurationException("Could not load locale file " + path.getFileName(), exception);
        }
        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                continue;
            }
            if (LEGACY_COLOR.matcher(value).find()) {
                throw new ConfigurationException(
                        "Legacy color code in " + path.getFileName() + " at " + entry.getKey()
                );
            }
            try {
                miniMessage.deserialize(value);
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException(
                        "Invalid MiniMessage in " + path.getFileName() + " at " + entry.getKey(),
                        exception
                );
            }
            messages.put(entry.getKey(), value);
        }
        return Map.copyOf(messages);
    }

    private static String fileName(Locale locale) {
        String country = locale.getCountry();
        return country.isBlank() ? locale.getLanguage() + ".yml" : locale.getLanguage() + "_" + country + ".yml";
    }
}
