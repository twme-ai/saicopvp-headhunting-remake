package dev.saicoremake.headhunting.config;

import java.util.Objects;

public final class ConfigurationService {
    private final RuntimeConfiguration runtime;

    public ConfigurationService(RuntimeConfiguration runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public PluginSettings current() {
        return runtime.current().settings();
    }
}
