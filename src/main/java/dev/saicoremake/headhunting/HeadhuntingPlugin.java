package dev.saicoremake.headhunting;

import dev.saicoremake.headhunting.api.DefaultHeadHuntingApi;
import dev.saicoremake.headhunting.api.HeadHuntingApi;
import dev.saicoremake.headhunting.command.HeadHuntCommand;
import dev.saicoremake.headhunting.command.StartupCommand;
import dev.saicoremake.headhunting.config.ConfigurationLoader;
import dev.saicoremake.headhunting.config.ConfigurationService;
import dev.saicoremake.headhunting.config.PluginSettings;
import dev.saicoremake.headhunting.config.ReloadService;
import dev.saicoremake.headhunting.gui.HeadMenuService;
import dev.saicoremake.headhunting.item.HeadItemCodec;
import dev.saicoremake.headhunting.listener.DeathListener;
import dev.saicoremake.headhunting.listener.MenuListener;
import dev.saicoremake.headhunting.listener.PlayerLifecycleListener;
import dev.saicoremake.headhunting.listener.SaleProtectionListener;
import dev.saicoremake.headhunting.listener.SellSignListener;
import dev.saicoremake.headhunting.locale.TranslationBundle;
import dev.saicoremake.headhunting.locale.TranslationLoader;
import dev.saicoremake.headhunting.locale.TranslationService;
import dev.saicoremake.headhunting.security.HeadSigner;
import dev.saicoremake.headhunting.security.SecretKeyManager;
import dev.saicoremake.headhunting.service.HeadExchangeService;
import dev.saicoremake.headhunting.service.HeadMintService;
import dev.saicoremake.headhunting.service.HeadSaleService;
import dev.saicoremake.headhunting.service.InventoryTransactionLock;
import dev.saicoremake.headhunting.service.RankUpService;
import dev.saicoremake.headhunting.service.RecoveryCoordinator;
import dev.saicoremake.headhunting.service.RewardDeliveryService;
import dev.saicoremake.headhunting.session.PlayerSessionService;
import dev.saicoremake.headhunting.storage.HeadStore;
import dev.saicoremake.headhunting.storage.SqliteDatabase;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadhuntingPlugin extends JavaPlugin {
    private final ConfigurationLoader configurationLoader = new ConfigurationLoader();
    private final TranslationLoader translationLoader = new TranslationLoader();
    private ExecutorService bootstrapExecutor;
    private CompletableFuture<BootstrapData> bootstrapFuture;
    private volatile SqliteDatabase database;

    @Override
    public void onEnable() {
        installStartupCommands();
        try {
            configurationLoader.installDefaults(this);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not install default HeadHunting resources", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bootstrapExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "headhunting-bootstrap");
            thread.setDaemon(false);
            return thread;
        });
        Path dataDirectory = getDataFolder().toPath();
        bootstrapFuture = CompletableFuture.supplyAsync(() -> loadBootstrapData(dataDirectory), bootstrapExecutor)
                .thenCompose(data -> data.database().start().thenApply(ignored -> data));
        bootstrapFuture.whenComplete((data, failure) -> {
            if (failure != null) {
                scheduleBootstrapFailure(failure);
            } else if (isEnabled()) {
                getServer().getScheduler().runTask(this, () -> initialize(data));
            }
        });
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        CompletableFuture<BootstrapData> future = bootstrapFuture;
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        SqliteDatabase currentDatabase = database;
        if (currentDatabase != null) {
            currentDatabase.close();
        }
        if (bootstrapExecutor != null) {
            bootstrapExecutor.shutdownNow();
            try {
                if (!bootstrapExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("Bootstrap executor did not terminate within five seconds");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private BootstrapData loadBootstrapData(Path dataDirectory) {
        try {
            PluginSettings settings = configurationLoader.load(dataDirectory);
            TranslationBundle translations = translationLoader.load(dataDirectory, settings);
            Path databasePath = dataDirectory.resolve("headhunting.db");
            byte[] secret = new SecretKeyManager().loadOrCreate(
                    dataDirectory.resolve("secret.key"),
                    databasePath
            );
            SqliteDatabase newDatabase = new SqliteDatabase(databasePath, getLogger());
            database = newDatabase;
            return new BootstrapData(settings, translations, secret, newDatabase);
        } catch (Exception exception) {
            throw new IllegalStateException("HeadHunting bootstrap failed", exception);
        }
    }

    private void initialize(BootstrapData data) {
        if (!isEnabled()) {
            return;
        }
        try {
            ConfigurationService configuration = new ConfigurationService(data.settings());
            TranslationService translations = new TranslationService(data.translations());
            HeadStore store = new HeadStore(data.database(), Clock.systemUTC());
            PlayerSessionService sessions = new PlayerSessionService(store, configuration);
            HeadSigner signer = new HeadSigner(data.secret());
            HeadItemCodec itemCodec = new HeadItemCodec(this, configuration, translations, signer);
            InventoryTransactionLock transactionLock = new InventoryTransactionLock();
            RewardDeliveryService rewards = new RewardDeliveryService(
                    this, store, sessions, translations, transactionLock
            );
            HeadSaleService sales = new HeadSaleService(
                    this, configuration, store, sessions, itemCodec, translations, transactionLock
            );
            RankUpService rankUps = new RankUpService(
                    this, configuration, store, sessions, translations, transactionLock, rewards
            );
            HeadMintService mints = new HeadMintService(
                    this,
                    configuration,
                    store,
                    sessions,
                    itemCodec,
                    signer,
                    translations,
                    Clock.systemUTC()
            );
            HeadExchangeService exchanges = new HeadExchangeService(
                    this,
                    configuration,
                    store,
                    sessions,
                    itemCodec,
                    translations,
                    transactionLock,
                    rewards
            );
            HeadMenuService menus = new HeadMenuService(
                    this, configuration, sessions, translations, rankUps, sales, exchanges
            );
            RecoveryCoordinator recovery = new RecoveryCoordinator(this, rewards, exchanges, sales);
            PlayerLifecycleListener lifecycle = new PlayerLifecycleListener(this, sessions, itemCodec, recovery);
            ReloadService reloads = new ReloadService(
                    getDataFolder().toPath(),
                    configurationLoader,
                    translationLoader,
                    configuration,
                    translations,
                    bootstrapExecutor
            );
            registerListeners(
                    configuration,
                    mints,
                    sales,
                    transactionLock,
                    menus,
                    sessions,
                    translations,
                    lifecycle
            );
            installReadyCommands(new HeadHuntCommand(
                    this,
                    configuration,
                    sessions,
                    translations,
                    menus,
                    sales,
                    exchanges,
                    rankUps,
                    mints,
                    itemCodec,
                    store,
                    reloads,
                    lifecycle
            ));
            HeadHuntingApi api = new DefaultHeadHuntingApi(
                    configuration, store, itemCodec, mints, menus, sales, rankUps
            );
            getServer().getServicesManager().register(
                    HeadHuntingApi.class,
                    api,
                    this,
                    ServicePriority.Normal
            );
            mints.recoverPendingDeliveries();
            getServer().getOnlinePlayers().forEach(lifecycle::initializePlayer);
            getLogger().info("SaicoPvP Headhunting Remake is ready");
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not initialize HeadHunting services", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerListeners(
            ConfigurationService configuration,
            HeadMintService mints,
            HeadSaleService sales,
            InventoryTransactionLock transactionLock,
            HeadMenuService menus,
            PlayerSessionService sessions,
            TranslationService translations,
            PlayerLifecycleListener lifecycle
    ) {
        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new DeathListener(mints), this);
        pluginManager.registerEvents(new SaleProtectionListener(sales, configuration, transactionLock), this);
        pluginManager.registerEvents(new MenuListener(menus), this);
        pluginManager.registerEvents(lifecycle, this);
        pluginManager.registerEvents(new SellSignListener(this, configuration, sessions, translations, menus), this);
    }

    private void installStartupCommands() {
        StartupCommand startup = new StartupCommand();
        for (String name : new String[]{"headhunt", "level", "rankup"}) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command: " + name);
            command.setExecutor(startup);
            command.setTabCompleter(startup);
        }
    }

    private void installReadyCommands(HeadHuntCommand executor) {
        for (String name : new String[]{"headhunt", "level", "rankup"}) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command: " + name);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void scheduleBootstrapFailure(Throwable failure) {
        getLogger().log(Level.SEVERE, "HeadHunting could not start", failure);
        if (isEnabled()) {
            getServer().getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
        }
    }

    private record BootstrapData(
            PluginSettings settings,
            TranslationBundle translations,
            byte[] secret,
            SqliteDatabase database
    ) {
        private BootstrapData {
            secret = java.util.Arrays.copyOf(secret, secret.length);
        }

        @Override
        public byte[] secret() {
            return java.util.Arrays.copyOf(secret, secret.length);
        }
    }
}
