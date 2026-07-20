package dev.saicoremake.headhunting.config;

import dev.saicoremake.headhunting.locale.TranslationBundle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeConfiguration {
    private final AtomicReference<Snapshot> current;

    public RuntimeConfiguration(PluginSettings settings, TranslationBundle translations) {
        current = new AtomicReference<>(new Snapshot(settings, translations));
    }

    public Snapshot current() {
        return current.get();
    }

    public void replace(PluginSettings settings, TranslationBundle translations) {
        current.set(new Snapshot(settings, translations));
    }

    public record Snapshot(PluginSettings settings, TranslationBundle translations) {
        public Snapshot {
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(translations, "translations");
            if (!settings.defaultLocale().equals(translations.defaultLocale())) {
                throw new IllegalArgumentException("Settings and translations must use the same default locale");
            }
            if (!translations.translations().keySet().containsAll(settings.supportedLocales())) {
                throw new IllegalArgumentException("Translations must contain every supported locale");
            }
        }
    }
}
