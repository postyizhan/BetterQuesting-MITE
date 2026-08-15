package com.github.postyizhan.betterquesting.network.handshake;

import java.util.Objects;
import java.util.UUID;

/** Immutable capabilities advertisement bound to one transport connection. */
public record HandshakeHello(UUID connectionToken, HandshakeCapabilities capabilities) {
    public HandshakeHello {
        Objects.requireNonNull(connectionToken, "connectionToken");
        Objects.requireNonNull(capabilities, "capabilities");
    }

    public UUID token() {
        return connectionToken;
    }
}
