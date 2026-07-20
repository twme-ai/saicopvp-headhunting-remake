package dev.saicoremake.headhunting.storage;

import dev.saicoremake.headhunting.domain.PlayerProfile;

public record PlayerHeadMintResult(
        PlayerHeadMintStatus status,
        PendingDelivery delivery,
        PlayerProfile victimProfile
) {
}
