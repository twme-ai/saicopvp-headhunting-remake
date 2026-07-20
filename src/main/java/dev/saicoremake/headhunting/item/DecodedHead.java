package dev.saicoremake.headhunting.item;

import dev.saicoremake.headhunting.security.HeadPayload;

public record DecodedHead(HeadDecodeStatus status, HeadPayload payload, String ownerName) {
    public static DecodedHead notAHead() {
        return new DecodedHead(HeadDecodeStatus.NOT_A_HEAD, null, null);
    }

    public static DecodedHead invalid() {
        return new DecodedHead(HeadDecodeStatus.INVALID, null, null);
    }

    public static DecodedHead valid(HeadPayload payload, String ownerName) {
        return new DecodedHead(HeadDecodeStatus.VALID, payload, ownerName);
    }
}
