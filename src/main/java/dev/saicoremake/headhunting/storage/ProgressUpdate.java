package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.PlayerProfile;

public record ProgressUpdate(boolean applied, PlayerProfile profile) {
}
