package dev.saicoremake.headhunting.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigurationService {
    private final AtomicReference<PluginSettings> current;

    public ConfigurationService(PluginSettings initialSettings) {
        current = new AtomicReference<>(Objects.requireNonNull(initialSettings, "initialSettings"));
    }

    public PluginSettings current() {
        return current.get();
    }

    public void replace(PluginSettings newSettings) {
        current.set(Objects.requireNonNull(newSettings, "newSettings"));
    }
}
