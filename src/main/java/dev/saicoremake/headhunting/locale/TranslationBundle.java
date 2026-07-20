package dev.saicoremake.headhunting.locale;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TranslationBundle(Locale defaultLocale, Map<Locale, Map<String, String>> translations) {
    public TranslationBundle {
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        translations = translations.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> Map.copyOf(entry.getValue())
        ));
        if (!translations.containsKey(defaultLocale)) {
            throw new IllegalArgumentException("Default locale translations are missing");
        }
    }

    public Map<String, String> forLocale(Locale requested) {
        Map<String, String> exact = translations.get(requested);
        if (exact != null) {
            return exact;
        }
        return translations.entrySet().stream()
                .filter(entry -> entry.getKey().getLanguage().equalsIgnoreCase(requested.getLanguage()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> translations.get(defaultLocale));
    }

    @Override
    public Map<Locale, Map<String, String>> translations() {
        Map<Locale, Map<String, String>> copy = new LinkedHashMap<>();
        translations.forEach((locale, values) -> copy.put(locale, Map.copyOf(values)));
        return Map.copyOf(copy);
    }
}
