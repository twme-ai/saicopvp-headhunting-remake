package dev.saicoremake.headhunting.gui;

import java.util.Objects;

public record MenuButton(MenuAction action, String data) {
    public MenuButton {
        Objects.requireNonNull(action, "action");
    }

    public static MenuButton of(MenuAction action) {
        return new MenuButton(action, null);
    }
}
