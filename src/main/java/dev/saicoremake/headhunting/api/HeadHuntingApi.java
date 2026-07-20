package dev.saicoremake.headhunting.api;

import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.item.DecodedHead;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public interface HeadHuntingApi {
    Optional<HeadDefinition> head(String key);

    CompletableFuture<PlayerProfile> profile(UUID playerId);

    DecodedHead inspect(ItemStack item);

    CompletableFuture<Void> mint(Player recipient, String headKey, int quantity);

    void openLevels(Player player);

    void sellAll(Player player);

    void rankUp(Player player);
}
