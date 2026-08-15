package com.github.postyizhan.betterquesting.network.handshake;

public record HandshakeCapabilities(
    int protocolVersion,
    int dataFormatVersion,
    long supportedFeatureBits,
    long requiredFeatureBits
) {
    public HandshakeCapabilities {
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        if (dataFormatVersion <= 0) {
            throw new IllegalArgumentException("dataFormatVersion must be positive");
        }
        if (supportedFeatureBits < 0L || requiredFeatureBits < 0L) {
            throw new IllegalArgumentException("feature bits must be non-negative");
        }
        if ((requiredFeatureBits & ~supportedFeatureBits) != 0L) {
            throw new IllegalArgumentException("required features must also be supported");
        }
    }
}
