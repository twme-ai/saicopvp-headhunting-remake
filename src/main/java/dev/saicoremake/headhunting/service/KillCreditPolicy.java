package dev.saicoremake.headhunting.service;

import dev.saicoremake.headhunting.config.PluginSettings;
import dev.saicoremake.headhunting.domain.HeadDefinition;
import dev.saicoremake.headhunting.domain.Money;
import dev.saicoremake.headhunting.domain.PlayerProfile;
import dev.saicoremake.headhunting.domain.ProgressionMode;

final class KillCreditPolicy {
    private KillCreditPolicy() {
    }

    static KillCredit calculate(PluginSettings settings, PlayerProfile profile, HeadDefinition definition) {
        var level = settings.level(profile.level());
        boolean directProgress = settings.progressionMode() == ProgressionMode.DIRECT_KILLS
                && !profile.completed()
                && level.progressHeadKeys().contains(definition.key());
        boolean countsForRequirement = !profile.completed()
                && level.killRequirements().containsKey(definition.key());
        boolean unlocked = profile.level() >= definition.minimumLevel();
        long progress = directProgress ? definition.progressPoints() : 0;
        long souls = unlocked ? definition.soulReward() : 0;
        Money money = unlocked ? definition.directMoneyReward() : Money.ZERO;
        boolean recordsEvent = countsForRequirement || progress > 0 || souls > 0 || !money.equals(Money.ZERO);
        return new KillCredit(progress, souls, money, recordsEvent);
    }
}
