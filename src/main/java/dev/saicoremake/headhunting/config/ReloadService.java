package dev.saicoremake.headhunting.config;

import dev.saicoremake.headhunting.locale.TranslationBundle;
import dev.saicoremake.headhunting.locale.TranslationLoader;
import dev.saicoremake.headhunting.storage.HeadStore;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ReloadService {
    private final Path dataDirectory;
    private final ConfigurationLoader configurationLoader;
    private final TranslationLoader translationLoader;
    private final RuntimeConfiguration runtime;
    private final HeadStore store;
    private final Executor loadExecutor;
    private final Executor activationExecutor;

    public ReloadService(
            Path dataDirectory,
            ConfigurationLoader configurationLoader,
            TranslationLoader translationLoader,
            RuntimeConfiguration runtime,
            HeadStore store,
            Executor loadExecutor,
            Executor activationExecutor
    ) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        this.translationLoader = Objects.requireNonNull(translationLoader, "translationLoader");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.store = Objects.requireNonNull(store, "store");
        this.loadExecutor = Objects.requireNonNull(loadExecutor, "loadExecutor");
        this.activationExecutor = Objects.requireNonNull(activationExecutor, "activationExecutor");
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
        }, loadExecutor).thenCompose(snapshot -> store.findMaximumProfileLevel().thenApply(maximumLevel -> {
            validateLevelCapacity(snapshot.settings(), maximumLevel);
            return snapshot;
        })).thenAcceptAsync(snapshot -> runtime.replace(
                snapshot.settings(),
                snapshot.translations()
        ), activationExecutor);
    }

    public static void validateLevelCapacity(PluginSettings settings, int maximumProfileLevel) {
        if (maximumProfileLevel > settings.levels().size()) {
            throw new IllegalStateException(
                    "Configured levels cannot be reduced below stored profile level " + maximumProfileLevel
            );
        }
    }

    private record ReloadSnapshot(PluginSettings settings, TranslationBundle translations) {
    }
}
