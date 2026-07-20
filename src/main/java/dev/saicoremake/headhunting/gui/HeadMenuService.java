package dev.saicoremake.headhunting.gui;

import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.config.ExchangeRecipe;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.service.HeadExchangeService;
import dev.saicoremake.headhunting.service.HeadSaleService;
import dev.saicoremake.headhunting.service.RankUpService;
import dev.saicoremake.headhunting.service.SaleScope;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadMenuService {
    private static final int[] LEVEL_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final PlayerSessionService sessions;
    private final TranslationService translations;
    private final RankUpService rankUpService;
    private final HeadSaleService saleService;
    private final HeadExchangeService exchangeService;

    public HeadMenuService(
            JavaPlugin plugin,
            ConfigurationService configuration,
            PlayerSessionService sessions,
            TranslationService translations,
            RankUpService rankUpService,
            HeadSaleService saleService,
            HeadExchangeService exchangeService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.rankUpService = Objects.requireNonNull(rankUpService, "rankUpService");
        this.saleService = Objects.requireNonNull(saleService, "saleService");
        this.exchangeService = Objects.requireNonNull(exchangeService, "exchangeService");
    }

    public void openLevels(Player player) {
        withProfile(player, profile -> openLevels(player, profile));
    }

    public void openSell(Player player) {
        Locale locale = sessions.locale(player);
        HeadMenuHolder holder = new HeadMenuHolder(
                player.getUniqueId(),
                27,
                translations.render(locale, "gui.sell-title")
        );
        fill(holder, locale);
        holder.setButton(
                11,
                menuItem(Material.EMERALD, translations.render(locale, "gui.button-sell-hand"), List.of()),
                MenuButton.of(MenuAction.SELL_HELD)
        );
        holder.setButton(
                15,
                menuItem(Material.GOLD_INGOT, translations.render(locale, "gui.button-sell-all"), List.of()),
                MenuButton.of(MenuAction.SELL_ALL)
        );
        holder.setButton(
                22,
                menuItem(Material.ARROW, translations.render(locale, "gui.button-back"), List.of()),
                MenuButton.of(MenuAction.BACK)
        );
        player.openInventory(holder.getInventory());
    }

    public void openExchange(Player player) {
        Locale locale = sessions.locale(player);
        HeadMenuHolder holder = new HeadMenuHolder(
                player.getUniqueId(),
                54,
                translations.render(locale, "gui.exchange-title")
        );
        fill(holder, locale);
        int index = 0;
        for (ExchangeRecipe recipe : configuration.current().exchanges().values()) {
            if (index >= LEVEL_SLOTS.length) {
                break;
            }
            Material material = rewardMaterial(recipe);
            List<Component> lore = new ArrayList<>();
            lore.add(translations.render(
                    locale,
                    "gui.exchange-cost",
                    Placeholder.unparsed("cost", exchangeCost(locale, recipe))
            ));
            if (recipe.soulCost() > 0) {
                lore.add(translations.render(
                        locale,
                        "gui.exchange-souls",
                        Placeholder.unparsed("amount", translations.formatDecimal(locale, recipe.soulCost()))
                ));
            }
            holder.setButton(
                    LEVEL_SLOTS[index++],
                    menuItem(material, translations.render(locale, recipe.displayKey()), lore),
                    new MenuButton(MenuAction.EXCHANGE, recipe.key())
            );
        }
        holder.setButton(
                49,
                menuItem(Material.ARROW, translations.render(locale, "gui.button-back"), List.of()),
                MenuButton.of(MenuAction.BACK)
        );
        player.openInventory(holder.getInventory());
    }

    public void handleButton(Player player, MenuButton button) {
        switch (button.action()) {
            case RANK_UP -> {
                player.closeInventory();
                rankUpService.rankUp(player);
            }
            case OPEN_SELL -> openSell(player);
            case SELL_HELD -> {
                player.closeInventory();
                saleService.sell(player, SaleScope.HELD);
            }
            case SELL_ALL -> {
                player.closeInventory();
                saleService.sell(player, SaleScope.ALL);
            }
            case OPEN_EXCHANGE -> openExchange(player);
            case EXCHANGE -> {
                player.closeInventory();
                exchangeService.exchange(player, Objects.requireNonNull(button.data()));
            }
            case BACK -> openLevels(player);
            case CLOSE -> player.closeInventory();
            default -> throw new IllegalStateException("Unknown menu action: " + button.action());
        }
    }

    private void openLevels(Player player, PlayerProfile profile) {
        Locale locale = sessions.locale(player);
        HeadMenuHolder holder = new HeadMenuHolder(
                player.getUniqueId(),
                54,
                translations.render(locale, "gui.levels-title")
        );
        fill(holder, locale);
        List<LevelDefinition> levels = configuration.current().levels();
        for (int index = 0; index < levels.size() && index < LEVEL_SLOTS.length; index++) {
            LevelDefinition level = levels.get(index);
            ItemStack levelItem = levelItem(locale, profile, level);
            if (!profile.completed() && level.number() == profile.level()) {
                holder.setButton(LEVEL_SLOTS[index], levelItem, MenuButton.of(MenuAction.RANK_UP));
            } else {
                holder.getInventory().setItem(LEVEL_SLOTS[index], levelItem);
            }
        }
        holder.setButton(
                45,
                menuItem(Material.GOLD_INGOT, translations.render(locale, "gui.button-sell"), List.of()),
                MenuButton.of(MenuAction.OPEN_SELL)
        );
        holder.setButton(
                49,
                menuItem(Material.NETHER_STAR, translations.render(locale, "gui.button-rankup"), List.of()),
                MenuButton.of(MenuAction.RANK_UP)
        );
        holder.setButton(
                53,
                menuItem(Material.ENDER_EYE, translations.render(locale, "gui.button-exchange"), List.of()),
                MenuButton.of(MenuAction.OPEN_EXCHANGE)
        );
        player.openInventory(holder.getInventory());
    }

    private ItemStack levelItem(Locale locale, PlayerProfile profile, LevelDefinition level) {
        boolean completed = profile.completed() || level.number() < profile.level();
        boolean current = !profile.completed() && level.number() == profile.level();
        Material material = completed ? Material.LIME_DYE : current ? Material.YELLOW_DYE : Material.GRAY_DYE;
        String nameKey = completed ? "gui.level-completed" : current ? "gui.level-current" : "gui.level-locked";
        List<Component> lore = new ArrayList<>();
        long displayedProgress = current ? profile.progress() : completed ? level.requiredProgress() : 0;
        lore.add(translations.render(
                locale,
                "gui.lore-progress",
                Placeholder.unparsed("progress", translations.formatDecimal(locale, displayedProgress)),
                Placeholder.unparsed("required", translations.formatDecimal(locale, level.requiredProgress()))
        ));
        lore.add(translations.render(
                locale,
                "gui.lore-cost",
                Placeholder.unparsed("cost", translations.formatMoney(locale, level.rankUpCost().minorUnits()))
        ));
        lore.add(translations.render(
                locale,
                "gui.lore-heads",
                Placeholder.unparsed("heads", headList(locale, level.progressHeadKeys()))
        ));
        if (current) {
            lore.add(translations.render(locale, "gui.lore-click-rankup"));
        }
        Component name = translations.render(
                locale,
                nameKey,
                Placeholder.unparsed("level", Integer.toString(level.number())),
                Placeholder.unparsed("tier", level.tier())
        );
        return menuItem(material, name, lore);
    }

    private void withProfile(Player player, java.util.function.Consumer<PlayerProfile> consumer) {
        PlayerProfile profile = sessions.profile(player.getUniqueId());
        if (profile != null) {
            consumer.accept(profile);
            return;
        }
        sessions.ensureLoaded(player).whenComplete((loaded, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (failure == null && player.isOnline()) {
                    consumer.accept(loaded);
                } else if (player.isOnline()) {
                    player.sendMessage(translations.render(sessions.locale(player), "error.internal"));
                }
            });
        });
    }

    private void fill(HeadMenuHolder holder, Locale locale) {
        ItemStack filler = menuItem(
                Material.BLACK_STAINED_GLASS_PANE,
                translations.render(locale, "gui.filler"),
                List.of()
        );
        for (int slot = 0; slot < holder.getInventory().getSize(); slot++) {
            holder.getInventory().setItem(slot, filler);
        }
    }

    private String exchangeCost(Locale locale, ExchangeRecipe recipe) {
        List<String> costs = new ArrayList<>();
        for (Map.Entry<String, Long> entry : recipe.headCosts().entrySet()) {
            HeadDefinition definition = configuration.current().heads().get(entry.getKey());
            String translationKey = definition == null ? "head." + entry.getKey() : definition.displayKey();
            Component name = translations.render(locale, translationKey);
            String plainName = PlainTextComponentSerializer.plainText().serialize(name);
            costs.add(translations.formatDecimal(locale, entry.getValue()) + " x " + plainName);
        }
        return String.join(", ", costs);
    }

    private String headList(Locale locale, List<String> headKeys) {
        List<String> heads = new ArrayList<>();
        for (String key : headKeys) {
            HeadDefinition definition = configuration.current().heads().get(key);
            if (definition != null) {
                heads.add(PlainTextComponentSerializer.plainText().serialize(
                        translations.render(locale, definition.displayKey())
                ));
            }
        }
        return String.join(", ", heads);
    }

    private static Material rewardMaterial(ExchangeRecipe recipe) {
        if (recipe.reward().type() == dev.saicoremake.headhunting.domain.RewardType.ITEM) {
            Material material = Material.matchMaterial(recipe.reward().value());
            if (material != null) {
                return material;
            }
        }
        return Material.CHEST;
    }

    private static ItemStack menuItem(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        boolean edited = item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
        });
        if (!edited) {
            throw new IllegalStateException("Could not create menu item");
        }
        return item;
    }
}
