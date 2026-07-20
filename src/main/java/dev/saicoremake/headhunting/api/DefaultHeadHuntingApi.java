package dev.saicoremake.headhunting.api;

import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.gui.HeadMenuService;
import dev.saicoremake.headhunting.item.DecodedHead;
import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.service.HeadMintService;
import dev.saicoremake.headhunting.service.HeadSaleService;
import dev.saicoremake.headhunting.service.RankUpService;
import dev.saicoremake.headhunting.service.SaleScope;
import dev.saicoremake.headhunting.storage.HeadStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class DefaultHeadHuntingApi implements HeadHuntingApi {
    private final ConfigurationService configuration;
    private final HeadStore store;
    private final HeadItemCodec itemCodec;
    private final HeadMintService mintService;
    private final HeadMenuService menus;
    private final HeadSaleService sales;
    private final RankUpService rankUps;

    public DefaultHeadHuntingApi(
            ConfigurationService configuration,
            HeadStore store,
            HeadItemCodec itemCodec,
            HeadMintService mintService,
            HeadMenuService menus,
            HeadSaleService sales,
            RankUpService rankUps
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.store = Objects.requireNonNull(store, "store");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.mintService = Objects.requireNonNull(mintService, "mintService");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.rankUps = Objects.requireNonNull(rankUps, "rankUps");
    }

    @Override
    public Optional<HeadDefinition> head(String key) {
        return Optional.ofNullable(configuration.current().heads().get(key));
    }

    @Override
    public CompletableFuture<PlayerProfile> profile(UUID playerId) {
        return store.findProfile(playerId);
    }

    @Override
    public DecodedHead inspect(ItemStack item) {
        return itemCodec.decode(item);
    }

    @Override
    public CompletableFuture<Void> mint(Player recipient, String headKey, int quantity) {
        return mintService.mintAdministrative(recipient, headKey, quantity);
    }

    @Override
    public void openLevels(Player player) {
        menus.openLevels(player);
    }

    @Override
    public void sellAll(Player player) {
        sales.sell(player, SaleScope.ALL);
    }

    @Override
    public void rankUp(Player player) {
        rankUps.rankUp(player);
    }
}
