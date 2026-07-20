package dev.saicoremake.headhunting.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LevelDefinitionTest {
    @Test
    void requiresProgressAndEveryKillCounter() {
        LevelDefinition level = new LevelDefinition(
                2,
                false,
                "Basic",
                100,
                new Money(5_000),
                Map.of("horde", 3L),
                List.of("wolf"),
                List.of("wolf"),
                List.of()
        );

        assertFalse(level.isComplete(99, Map.of("horde", 3L)));
        assertFalse(level.isComplete(100, Map.of("horde", 2L)));
        assertTrue(level.isComplete(100, Map.of("horde", 3L)));
    }
}
