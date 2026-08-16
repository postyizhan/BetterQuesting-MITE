package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;

public final class LoginSyncProtocol {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DATA_FORMAT_VERSION = 1;
    public static final HandshakeCapabilities CAPABILITIES = new HandshakeCapabilities(
        PROTOCOL_VERSION,
        DATA_FORMAT_VERSION,
        0L,
        0L);
    public static final HandshakeLimits LIMITS = new HandshakeLimits(63, 0L, 0L);

    private LoginSyncProtocol() {
    }
}
