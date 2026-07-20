package dev.saicoremake.headhunting.service;

import dev.saicoremake.headhunting.domain.Money;
import java.util.Objects;

record KillCredit(long progress, long souls, Money money, boolean recordsEvent) {
    KillCredit {
        Objects.requireNonNull(money, "money");
        if (progress < 0 || souls < 0) {
            throw new IllegalArgumentException("Kill credit cannot be negative");
        }
    }
}
