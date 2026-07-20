package dev.saicoremake.headhunting.command;

import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.config.ReloadService;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.gui.HeadMenuService;
import dev.saicoremake.headhunting.item.DecodedHead;
import dev.saicoremake.headhunting.item.HeadDecodeStatus;
import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.listener.PlayerLifecycleListener;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.service.HeadExchangeService;
import dev.saicoremake.headhunting.service.HeadMintService;
import dev.saicoremake.headhunting.service.HeadSaleService;
import dev.saicoremake.headhunting.service.RankUpService;
import dev.saicoremake.headhunting.service.SaleScope;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.ProfileCounter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadHuntCommand implements TabExecutor {
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final PlayerSessionService sessions;
    private final TranslationService translations;
    private final HeadMenuService menus;
    private final HeadSaleService sales;
    private final HeadExchangeService exchanges;
    private final RankUpService rankUps;
    private final HeadMintService mints;
    private final HeadItemCodec itemCodec;
    private final HeadStore store;
    private final ReloadService reloads;
    private final PlayerLifecycleListener lifecycle;

    public HeadHuntCommand(
            JavaPlugin plugin,
            ConfigurationService configuration,
            PlayerSessionService sessions,
            TranslationService translations,
            HeadMenuService menus,
            HeadSaleService sales,
            HeadExchangeService exchanges,
            RankUpService rankUps,
            HeadMintService mints,
            HeadItemCodec itemCodec,
            HeadStore store,
            ReloadService reloads,
            PlayerLifecycleListener lifecycle
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.rankUps = Objects.requireNonNull(rankUps, "rankUps");
        this.mints = Objects.requireNonNull(mints, "mints");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.store = Objects.requireNonNull(store, "store");
        this.reloads = Objects.requireNonNull(reloads, "reloads");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("rankup")) {
            return playerAction(sender, "headhunting.rankup", rankUps::rankUp);
        }
        if (command.getName().equalsIgnoreCase("level")) {
            return playerAction(sender, "headhunting.use", menus::openLevels);
        }
        if (args.length == 0) {
            return playerAction(sender, "headhunting.use", menus::openLevels);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                send(sender, "command.help");
                yield true;
            }
            case "level" -> playerAction(sender, "headhunting.use", menus::openLevels);
            case "rankup" -> playerAction(sender, "headhunting.rankup", rankUps::rankUp);
            case "sell" -> handleSell(sender, args);
            case "exchange" -> playerAction(sender, "headhunting.exchange", menus::openExchange);
            case "language" -> handleLanguage(sender, args);
            case "balance", "status" -> handleStatus(sender);
            case "admin" -> handleAdmin(sender, args);
            default -> {
                send(sender, "command.help");
                yield true;
            }
        };
    }

    private boolean handleSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "error.player-only");
            return true;
        }
        if (!requirePermission(sender, "headhunting.sell")) {
            return true;
        }
        if (args.length > 2 || args.length == 2
                && !args[1].equalsIgnoreCase("hand")
                && !args[1].equalsIgnoreCase("all")) {
            send(sender, "command.usage-sell");
            return true;
        }
        SaleScope scope = args.length == 2 && args[1].equalsIgnoreCase("hand")
                ? SaleScope.HELD : SaleScope.ALL;
        sales.sell(player, scope);
        return true;
    }

    private boolean handleLanguage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "error.player-only");
            return true;
        }
        if (!requirePermission(sender, "headhunting.language")) {
            return true;
        }
        if (args.length != 2) {
            send(sender, "command.usage-language");
            return true;
        }
        Locale selected = args[1].equalsIgnoreCase("auto") ? null : findLocale(args[1]);
        if (selected == null && !args[1].equalsIgnoreCase("auto")) {
            send(
                    sender,
                    "error.invalid-locale",
                    Placeholder.unparsed("locales", supportedLocaleNames())
            );
            return true;
        }
        sessions.setLocaleOverride(player, selected).whenComplete((profile, failure) -> schedule(() -> {
            if (failure != null) {
                reportFailure(sender, "Could not update player locale", failure);
                return;
            }
            lifecycle.relocalize(player, sessions.locale(player));
            String key = selected == null ? "language.automatic" : "language.changed";
            send(player, key, Placeholder.unparsed("locale", localeName(sessions.locale(player))));
        }));
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "error.player-only");
            return true;
        }
        if (!requirePermission(sender, "headhunting.use")) {
            return true;
        }
        withProfile(player, profile -> {
            Locale locale = sessions.locale(player);
            send(
                    player,
                    "profile.balance",
                    Placeholder.unparsed("amount", translations.formatMoney(locale, profile.balance().minorUnits())),
                    Placeholder.unparsed("amount_souls", translations.formatDecimal(locale, profile.souls()))
            );
            if (profile.completed()) {
                send(player, "profile.completed");
            } else {
                var level = configuration.current().level(profile.level());
                send(
                        player,
                        "profile.status",
                        Placeholder.unparsed("level", Integer.toString(profile.level())),
                        Placeholder.unparsed("tier", level.tier()),
                        Placeholder.unparsed("progress", translations.formatDecimal(locale, profile.progress())),
                        Placeholder.unparsed("required", translations.formatDecimal(locale, level.requiredProgress()))
                );
            }
        });
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "headhunting.admin")) {
            return true;
        }
        if (args.length < 2) {
            send(sender, "command.help");
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "inspect" -> handleInspect(sender);
            case "give" -> handleGive(sender, args);
            case "setlevel" -> handleSetLevel(sender, args);
            case "addprogress" -> handleAdd(sender, args, ProfileCounter.PROGRESS);
            case "addsouls" -> handleAdd(sender, args, ProfileCounter.SOULS);
            case "addbalance" -> handleAdd(sender, args, ProfileCounter.BALANCE);
            default -> {
                send(sender, "command.help");
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        reloads.reload().whenComplete((ignored, failure) -> schedule(() -> {
            if (failure == null) {
                send(sender, "admin.reload-success");
            } else {
                plugin.getLogger().log(Level.SEVERE, "HeadHunting reload failed", failure);
                send(sender, "error.reload-failed");
            }
        }));
        return true;
    }

    private boolean handleInspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "error.player-only");
            return true;
        }
        DecodedHead decoded = itemCodec.decode(player.getInventory().getItemInMainHand());
        if (decoded.status() != HeadDecodeStatus.VALID) {
            send(sender, "admin.inspect-invalid");
            return true;
        }
        send(
                sender,
                "admin.inspect-valid",
                Placeholder.unparsed("batch", decoded.payload().batchId().toString()),
                Placeholder.unparsed("head", decoded.payload().headKey())
        );
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5) {
            send(sender, "command.usage-admin-give");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            send(sender, "error.invalid-player", Placeholder.unparsed("player", args[2]));
            return true;
        }
        int amount;
        try {
            amount = args.length == 5 ? Integer.parseInt(args[4]) : 1;
        } catch (NumberFormatException exception) {
            send(sender, "error.invalid-number", Placeholder.unparsed("value", args[4]));
            return true;
        }
        var definition = configuration.current().heads().get(args[3]);
        if (definition == null || definition.kind() != dev.saicoremake.headhunting.domain.HeadKind.MOB) {
            send(sender, "error.invalid-head", Placeholder.unparsed("head", args[3]));
            return true;
        }
        mints.mintAdministrative(target, args[3], amount).whenComplete((ignored, failure) -> schedule(() -> {
            if (failure == null) {
                send(
                        sender,
                        "admin.give-success",
                        Placeholder.unparsed("amount", Integer.toString(amount)),
                        Placeholder.unparsed("head", args[3]),
                        Placeholder.unparsed("player", target.getName())
                );
            } else {
                reportFailure(sender, "Could not execute administrative mint", failure);
            }
        }));
        return true;
    }

    private boolean handleSetLevel(CommandSender sender, String[] args) {
        if (args.length != 4) {
            send(sender, "command.usage-admin-setlevel");
            return true;
        }
        Player target = requireOnlineTarget(sender, args[2]);
        if (target == null) {
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            send(sender, "error.invalid-number", Placeholder.unparsed("value", args[3]));
            return true;
        }
        if (level < 1 || level > configuration.current().levels().size()) {
            send(sender, "error.invalid-number", Placeholder.unparsed("value", args[3]));
            return true;
        }
        withProfile(target, ignored -> store.setLevel(target.getUniqueId(), level, false)
                .whenComplete((profile, failure) -> finishAdminUpdate(sender, target, profile, failure)));
        return true;
    }

    private boolean handleAdd(CommandSender sender, String[] args, ProfileCounter counter) {
        if (args.length != 4) {
            send(sender, "command.usage-admin-add");
            return true;
        }
        Player target = requireOnlineTarget(sender, args[2]);
        if (target == null) {
            return true;
        }
        long delta;
        try {
            delta = counter == ProfileCounter.BALANCE
                    ? new BigDecimal(args[3]).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
                    : Long.parseLong(args[3]);
        } catch (ArithmeticException | NumberFormatException exception) {
            send(sender, "error.invalid-number", Placeholder.unparsed("value", args[3]));
            return true;
        }
        withProfile(target, ignored -> store.addCounter(target.getUniqueId(), counter, delta)
                .whenComplete((profile, failure) -> finishAdminUpdate(sender, target, profile, failure)));
        return true;
    }

    private void finishAdminUpdate(
            CommandSender sender,
            Player target,
            PlayerProfile profile,
            Throwable failure
    ) {
        schedule(() -> {
            if (failure != null) {
                reportFailure(sender, "Could not update player profile", failure);
                return;
            }
            sessions.update(profile);
            send(sender, "admin.update-success", Placeholder.unparsed("player", target.getName()));
        });
    }

    private Player requireOnlineTarget(CommandSender sender, String name) {
        Player target = plugin.getServer().getPlayerExact(name);
        if (target == null) {
            send(sender, "error.invalid-player", Placeholder.unparsed("player", name));
        }
        return target;
    }

    private boolean playerAction(CommandSender sender, String permission, Consumer<Player> action) {
        if (!(sender instanceof Player player)) {
            send(sender, "error.player-only");
            return true;
        }
        if (requirePermission(sender, permission)) {
            action.accept(player);
        }
        return true;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "error.no-permission");
        return false;
    }

    private void withProfile(Player player, Consumer<PlayerProfile> action) {
        PlayerProfile profile = sessions.profile(player.getUniqueId());
        if (profile != null) {
            action.accept(profile);
            return;
        }
        sessions.ensureLoaded(player).whenComplete((loaded, failure) -> schedule(() -> {
            if (failure == null && player.isOnline()) {
                action.accept(loaded);
            } else if (failure != null) {
                reportFailure(player, "Could not load player profile", failure);
            }
        }));
    }

    private Locale findLocale(String input) {
        String normalized = input.replace('-', '_');
        return configuration.current().supportedLocales().stream()
                .filter(locale -> localeName(locale).equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }

    private String supportedLocaleNames() {
        return String.join(", ", configuration.current().supportedLocales().stream()
                .map(HeadHuntCommand::localeName)
                .toList());
    }

    private Locale locale(CommandSender sender) {
        return sender instanceof Player player
                ? sessions.locale(player) : configuration.current().defaultLocale();
    }

    private void send(CommandSender sender, String key, TagResolver... tags) {
        sender.sendMessage(translations.render(locale(sender), key, tags));
    }

    private void reportFailure(CommandSender sender, String message, Throwable failure) {
        plugin.getLogger().log(Level.SEVERE, message, failure);
        send(sender, "error.internal");
    }

    private void schedule(Runnable runnable) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private static String localeName(Locale locale) {
        return locale.getCountry().isBlank()
                ? locale.getLanguage() : locale.getLanguage() + "_" + locale.getCountry();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("headhunt")) {
            return options;
        }
        if (args.length == 1) {
            options.addAll(List.of("help", "level", "rankup", "sell", "exchange", "language", "status"));
            if (sender.hasPermission("headhunting.admin")) {
                options.add("admin");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            options.addAll(List.of("hand", "all"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("language")) {
            options.add("auto");
            configuration.current().supportedLocales().stream().map(HeadHuntCommand::localeName).forEach(options::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            options.addAll(List.of("reload", "inspect", "give", "setlevel", "addprogress", "addsouls", "addbalance"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("give")) {
            options.addAll(configuration.current().heads().keySet());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
