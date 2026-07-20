package dev.saicoremake.headhunting.locale;

import dev.saicoremake.headhunting.config.ConfigurationException;
import dev.saicoremake.headhunting.config.PluginSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TranslationLoader {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?:\\u00a7|&)[0-9A-FK-OR]", Pattern.CASE_INSENSITIVE);
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "admin.give-success",
            "admin.inspect-invalid",
            "admin.inspect-valid",
            "admin.reload-success",
            "admin.update-success",
            "command.help",
            "command.usage-admin-add",
            "command.usage-admin-give",
            "command.usage-admin-setlevel",
            "command.usage-language",
            "command.usage-sell",
            "drop.mob",
            "drop.player",
            "drop.queue-full",
            "error.busy",
            "error.duplicate-head",
            "error.forged-head",
            "error.internal",
            "error.invalid-head",
            "error.invalid-locale",
            "error.invalid-number",
            "error.invalid-player",
            "error.locked-head",
            "error.no-permission",
            "error.player-only",
            "error.reload-failed",
            "exchange.insufficient-heads",
            "exchange.insufficient-souls",
            "exchange.recovery-cancelled",
            "exchange.recovery-finalized",
            "exchange.success",
            "gui.button-back",
            "gui.button-exchange",
            "gui.button-rankup",
            "gui.button-sell",
            "gui.button-sell-all",
            "gui.button-sell-hand",
            "gui.exchange-cost",
            "gui.exchange-souls",
            "gui.exchange-title",
            "gui.filler",
            "gui.level-completed",
            "gui.level-current",
            "gui.level-locked",
            "gui.levels-title",
            "gui.lore-click-rankup",
            "gui.lore-cost",
            "gui.lore-heads",
            "gui.lore-progress",
            "gui.sell-title",
            "item.authentic",
            "item.head-name",
            "item.owner",
            "item.player-head-name",
            "item.progress",
            "item.reward-name",
            "item.value",
            "language.automatic",
            "language.changed",
            "profile.balance",
            "profile.completed",
            "profile.status",
            "rankup.already-completed",
            "rankup.changed",
            "rankup.completed",
            "rankup.incomplete",
            "rankup.insufficient-funds",
            "rankup.success",
            "reward.failed",
            "reward.inventory-full",
            "reward.received",
            "sell.failed-unchanged",
            "sell.nothing-all",
            "sell.nothing-hand",
            "sell.preparing",
            "sell.recovery-cancelled",
            "sell.recovery-finalized",
            "sell.sign-created",
            "sell.sign-line-one",
            "sell.sign-line-two",
            "sell.success"
    );
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
        validateRequiredKeys(defaults, settings);
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

    private static void validateRequiredKeys(Map<String, String> defaults, PluginSettings settings)
            throws ConfigurationException {
        LinkedHashSet<String> required = new LinkedHashSet<>(REQUIRED_KEYS);
        settings.heads().values().forEach(definition -> required.add(definition.displayKey()));
        settings.exchanges().values().forEach(recipe -> required.add(recipe.displayKey()));
        required.removeAll(defaults.keySet());
        if (!required.isEmpty()) {
            throw new ConfigurationException("Default locale is missing translation keys: " + required);
        }
    }
}
