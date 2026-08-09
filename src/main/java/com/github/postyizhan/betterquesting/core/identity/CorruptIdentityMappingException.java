package com.github.postyizhan.betterquesting.core.identity;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Thrown when the mapping snapshot exists but is not fully valid.
 *
 * <p>Deliberately a throw and not an empty result. Reporting corruption as "no mappings" would let
 * isolated legacy saves be re-claimed by derived identities, which is exactly the auto-merge that
 * plan.md stage 3 item 7 forbids.
 */
public final class CorruptIdentityMappingException extends IOException {
    private final transient List<IdentityRecordRejection> rejections;

    public CorruptIdentityMappingException(String message, List<IdentityRecordRejection> rejections) {
        super(message + ": " + Objects.requireNonNull(rejections, "rejections"));
        this.rejections = List.copyOf(rejections);
    }

    public List<IdentityRecordRejection> rejections() {
        return rejections;
    }
}
