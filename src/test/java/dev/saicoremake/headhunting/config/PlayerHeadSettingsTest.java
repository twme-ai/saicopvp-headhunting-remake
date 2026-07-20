package dev.saicoremake.headhunting.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlayerHeadSettingsTest {
    @Test
    void rejectsCooldownsLongerThanTenYears() {
        assertDoesNotThrow(() -> settings(315_360_000L, 315_360_000L));
        assertThrows(IllegalArgumentException.class, () -> settings(315_360_001L, 0));
        assertThrows(IllegalArgumentException.class, () -> settings(0, 315_360_001L));
    }

    private static PlayerHeadSettings settings(long pairCooldown, long victimCooldown) {
        return new PlayerHeadSettings(
                true,
                new BigDecimal("0.10"),
                100_000,
                100_000_000,
                true,
                1.0,
                pairCooldown,
                victimCooldown,
                true
        );
    }
}
