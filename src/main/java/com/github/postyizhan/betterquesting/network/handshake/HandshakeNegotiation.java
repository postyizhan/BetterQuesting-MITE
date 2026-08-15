package com.github.postyizhan.betterquesting.network.handshake;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class HandshakeNegotiation {
    private final int protocolVersion;
    private final int dataFormatVersion;
    private final long featureBits;
    private final Set<Long> features;

    HandshakeNegotiation(int protocolVersion, int dataFormatVersion, long featureBits) {
        this.protocolVersion = protocolVersion;
        this.dataFormatVersion = dataFormatVersion;
        this.featureBits = featureBits;

        Set<Long> enabled = new LinkedHashSet<>();
        for (int bit = 0; bit < 63; bit++) {
            if ((featureBits & (1L << bit)) != 0L) {
                enabled.add((long) bit);
            }
        }
        this.features = Collections.unmodifiableSet(enabled);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public int dataFormatVersion() {
        return dataFormatVersion;
    }

    public long featureBits() {
        return featureBits;
    }

    public Set<Long> features() {
        return features;
    }
}
