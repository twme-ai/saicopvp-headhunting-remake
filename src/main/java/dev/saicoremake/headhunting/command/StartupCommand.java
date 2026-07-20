package dev.saicoremake.headhunting.command;

import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

public final class StartupCommand implements TabExecutor {
    private static final net.kyori.adventure.text.Component NOT_READY = MiniMessage.miniMessage().deserialize(
            "<red>HeadHunting is still starting. Try again in a moment.</red>"
    );

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(NOT_READY);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
