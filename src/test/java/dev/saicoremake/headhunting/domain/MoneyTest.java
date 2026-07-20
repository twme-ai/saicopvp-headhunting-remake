package dev.saicoremake.headhunting.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void usesExactMinorUnits() {
        Money money = Money.fromMajor(new BigDecimal("19.99"));

        assertEquals(1999, money.minorUnits());
        assertEquals(new BigDecimal("19.99"), money.toMajor());
        assertEquals(5997, money.multiply(3).minorUnits());
    }

    @Test
    void rejectsFractionalMinorUnitsAndOverflow() {
        assertThrows(ArithmeticException.class, () -> Money.fromMajor(new BigDecimal("1.001")));
        assertThrows(ArithmeticException.class, () -> new Money(Long.MAX_VALUE).add(new Money(1)));
    }
}
