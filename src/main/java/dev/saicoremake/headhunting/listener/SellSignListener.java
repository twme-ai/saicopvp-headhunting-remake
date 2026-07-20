package dev.saicoremake.headhunting.listener;

import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.gui.HeadMenuService;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import java.util.Objects;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SellSignListener implements Listener {
    private static final String CREATION_TEXT = "[HeadHunt]";
    private final JavaPlugin plugin;
    private final ConfigurationService configuration;
    private final PlayerSessionService sessions;
    private final TranslationService translations;
    private final HeadMenuService menus;
    private final NamespacedKey signKey;

    public SellSignListener(
            JavaPlugin plugin,
            ConfigurationService configuration,
            PlayerSessionService sessions,
            TranslationService translations,
            HeadMenuService menus
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.signKey = new NamespacedKey(plugin, "sell_sign");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!configuration.current().sellSignsEnabled()) {
            return;
        }
        String firstLine = PlainTextComponentSerializer.plainText().serialize(
                Objects.requireNonNull(event.line(0))
        );
        if (!CREATION_TEXT.equalsIgnoreCase(firstLine.trim())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("headhunting.sign.create")) {
            player.sendMessage(translations.render(sessions.locale(player), "error.no-permission"));
            return;
        }
        event.line(0, translations.render(sessions.locale(player), "sell.sign-line-one"));
        event.line(1, translations.render(sessions.locale(player), "sell.sign-line-two"));
        player.sendMessage(translations.render(sessions.locale(player), "sell.sign-created"));
        org.bukkit.block.Block block = event.getBlock();
        org.bukkit.block.sign.Side signSide = event.getSide();
        Component lineOne = translations.render(sessions.locale(player), "sell.sign-line-one");
        Component lineTwo = translations.render(sessions.locale(player), "sell.sign-line-two");
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (block.getState() instanceof Sign sign) {
                sign.getPersistentDataContainer().set(signKey, PersistentDataType.BOOLEAN, true);
                SignSide side = sign.getSide(signSide);
                side.line(0, lineOne);
                side.line(1, lineTwo);
                sign.setWaxed(true);
                sign.update(true, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!configuration.current().sellSignsEnabled()
                || event.getHand() != EquipmentSlot.HAND
                || !event.getAction().isRightClick()) {
            return;
        }
        org.bukkit.block.Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || !(clickedBlock.getState() instanceof Sign sign)) {
            return;
        }
        if (!sign.getPersistentDataContainer().getOrDefault(
                        signKey,
                        PersistentDataType.BOOLEAN,
                        false
                )) {
            return;
        }
        if (!event.getPlayer().hasPermission("headhunting.sign.use")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(translations.render(
                    sessions.locale(event.getPlayer()),
                    "error.no-permission"
            ));
            return;
        }
        event.setCancelled(true);
        menus.openSell(event.getPlayer());
    }
}
