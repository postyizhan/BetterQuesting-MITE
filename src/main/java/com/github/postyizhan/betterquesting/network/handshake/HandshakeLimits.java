package com.github.postyizhan.betterquesting.network.handshake;

public record HandshakeLimits(
    int featureBitWidth,
    long knownFeatureBits,
    long reservedFeatureBits
) {
    public HandshakeLimits {
        if (featureBitWidth < 1 || featureBitWidth > 63) {
            throw new IllegalArgumentException("featureBitWidth must be between 1 and 63");
        }
        if (knownFeatureBits < 0L || reservedFeatureBits < 0L) {
            throw new IllegalArgumentException("feature masks must be non-negative");
        }

        long widthMask = widthMask(featureBitWidth);
        if (((knownFeatureBits | reservedFeatureBits) & ~widthMask) != 0L) {
            throw new IllegalArgumentException("feature masks exceed featureBitWidth");
        }
        if ((knownFeatureBits & reservedFeatureBits) != 0L) {
            throw new IllegalArgumentException("known and reserved features must be disjoint");
        }
    }

    long widthMask() {
        return widthMask(featureBitWidth);
    }

    private static long widthMask(int width) {
        return width == 63 ? Long.MAX_VALUE : (1L << width) - 1L;
    }
}
