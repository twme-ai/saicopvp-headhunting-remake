package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.PlayerProfile;

public record RankUpResult(RankUpStatus status, PlayerProfile profile) {
}
