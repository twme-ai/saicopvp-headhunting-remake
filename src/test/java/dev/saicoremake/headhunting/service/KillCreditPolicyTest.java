package dev.saicoremake.headhunting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.saicoremake.headhunting.config.ConfigurationException;
import dev.saicoremake.headhunting.config.ConfigurationLoader;
import dev.saicoremake.headhunting.config.PluginSettings;
import dev.saicoremake.headhunting.domain.LevelDefinition;
import dev.saicoremake.headhunting.domain.Money;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.domain.ProgressionMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KillCreditPolicyTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void sellModeStillAwardsConfiguredKillSouls() throws ConfigurationException {
        PluginSettings settings = new ConfigurationLoader().load(RESOURCES);
        PlayerProfile profile = profileAtLevel(12);

        KillCredit credit = KillCreditPolicy.calculate(settings, profile, settings.heads().get("zombie"));

        assertEquals(0, credit.progress());
        assertEquals(8, credit.souls());
        assertTrue(credit.recordsEvent());
    }

    @Test
    void directModeAwardsProgressAndKillRewardsTogether() throws ConfigurationException {
        PluginSettings defaults = new ConfigurationLoader().load(RESOURCES);
        PluginSettings settings = copy(defaults, ProgressionMode.DIRECT_KILLS, defaults.levels());
        PlayerProfile profile = profileAtLevel(12);

        KillCredit credit = KillCreditPolicy.calculate(settings, profile, settings.heads().get("zombie"));

        assertEquals(1, credit.progress());
        assertEquals(8, credit.souls());
        assertTrue(credit.recordsEvent());
    }

    @Test
    void sellModeRecordsConfiguredKillRequirementsWithoutDirectRewards() throws ConfigurationException {
        PluginSettings defaults = new ConfigurationLoader().load(RESOURCES);
        List<LevelDefinition> levels = new ArrayList<>(defaults.levels());
        LevelDefinition first = levels.getFirst();
        levels.set(0, new LevelDefinition(
                first.number(),
                first.terminal(),
                first.tier(),
                first.requiredProgress(),
                first.rankUpCost(),
                Map.of("pig", 1L),
                first.progressHeadKeys(),
                first.unlockedHeadKeys(),
                first.rewards()
        ));
        PluginSettings settings = copy(defaults, ProgressionMode.SELL_HEADS, levels);

        KillCredit credit = KillCreditPolicy.calculate(settings, profileAtLevel(1), settings.heads().get("pig"));

        assertEquals(0, credit.progress());
        assertEquals(Money.ZERO, credit.money());
        assertTrue(credit.recordsEvent());
    }

    private static PlayerProfile profileAtLevel(int level) {
        return new PlayerProfile(
                UUID.randomUUID(),
                "Player",
                Locale.US,
                null,
                level,
                false,
                0,
                Money.ZERO,
                0
        );
    }

    private static PluginSettings copy(
            PluginSettings source,
            ProgressionMode mode,
            List<LevelDefinition> levels
    ) {
        return new PluginSettings(
                mode,
                source.batchWindowSeconds(),
                source.maximumPendingMints(),
                source.rightClickSells(),
                source.sellSignsEnabled(),
                source.allowedWorlds(),
                source.blockedWorlds(),
                source.allowedSpawnReasons(),
                source.allowedDamageCauses(),
                source.playerHeads(),
                source.defaultLocale(),
                source.supportedLocales(),
                source.heads(),
                levels,
                source.exchanges()
        );
    }
}
