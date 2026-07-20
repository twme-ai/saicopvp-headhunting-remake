package dev.saicoremake.headhunting.config;

import dev.saicoremake.headhunting.locale.TranslationBundle;
import dev.saicoremake.headhunting.locale.TranslationLoader;
import dev.saicoremake.headhunting.locale.TranslationService;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ReloadService {
    private final Path dataDirectory;
    private final ConfigurationLoader configurationLoader;
    private final TranslationLoader translationLoader;
    private final ConfigurationService configuration;
    private final TranslationService translations;
    private final Executor executor;

    public ReloadService(
            Path dataDirectory,
            ConfigurationLoader configurationLoader,
            TranslationLoader translationLoader,
            ConfigurationService configuration,
            TranslationService translations,
            Executor executor
    ) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        this.translationLoader = Objects.requireNonNull(translationLoader, "translationLoader");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletableFuture<Void> reload() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                PluginSettings newSettings = configurationLoader.load(dataDirectory);
                TranslationBundle newTranslations = translationLoader.load(dataDirectory, newSettings);
                return new ReloadSnapshot(newSettings, newTranslations);
            } catch (ConfigurationException exception) {
                throw new IllegalStateException("Configuration reload validation failed", exception);
            }
        }, executor).thenAccept(snapshot -> {
            configuration.replace(snapshot.settings());
            translations.replace(snapshot.translations());
        });
    }

    private record ReloadSnapshot(PluginSettings settings, TranslationBundle translations) {
    }
}
