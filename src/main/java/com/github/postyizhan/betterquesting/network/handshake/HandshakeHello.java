package com.github.postyizhan.betterquesting.network.handshake;

import java.util.Objects;

public record HandshakeHello(HandshakeCapabilities capabilities) {
    public HandshakeHello {
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
