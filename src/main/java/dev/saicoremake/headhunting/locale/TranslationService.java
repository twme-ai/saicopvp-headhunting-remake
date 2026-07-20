package dev.saicoremake.headhunting.locale;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class TranslationService {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final AtomicReference<TranslationBundle> current;

    public TranslationService(TranslationBundle initialBundle) {
        current = new AtomicReference<>(Objects.requireNonNull(initialBundle, "initialBundle"));
    }

    public void replace(TranslationBundle newBundle) {
        current.set(Objects.requireNonNull(newBundle, "newBundle"));
    }

    public Component render(Locale locale, String key, TagResolver... resolvers) {
        TranslationBundle bundle = current.get();
        String template = bundle.forLocale(locale).get(key);
        if (template == null) {
            template = bundle.translations().get(bundle.defaultLocale()).get(key);
        }
        if (template == null) {
            return miniMessage.deserialize(
                    "<red>Missing translation: <key></red>",
                    Placeholder.unparsed("key", key)
            );
        }
        return miniMessage.deserialize(template, TagResolver.resolver(resolvers));
    }

    public String formatDecimal(Locale locale, long value) {
        return NumberFormat.getIntegerInstance(locale).format(value);
    }

    public String formatMoney(Locale locale, long minorUnits) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        return format.format(java.math.BigDecimal.valueOf(minorUnits, 2));
    }
}
