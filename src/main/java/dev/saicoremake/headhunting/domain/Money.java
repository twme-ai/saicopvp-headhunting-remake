package dev.saicoremake.headhunting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(long minorUnits) implements Comparable<Money> {
    public static final Money ZERO = new Money(0);

    public Money {
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Money cannot be negative");
        }
    }

    public static Money fromMajor(BigDecimal majorUnits) {
        if (majorUnits == null) {
            throw new IllegalArgumentException("Money value cannot be null");
        }
        return new Money(majorUnits.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
    }

    public Money add(Money other) {
        return new Money(Math.addExact(minorUnits, other.minorUnits));
    }

    public Money multiply(long quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        return new Money(Math.multiplyExact(minorUnits, quantity));
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, 2);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minorUnits, other.minorUnits);
    }
}
