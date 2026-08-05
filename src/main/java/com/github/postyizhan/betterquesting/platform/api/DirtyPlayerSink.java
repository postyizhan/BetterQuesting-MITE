package com.github.postyizhan.betterquesting.platform.api;

import java.util.Collection;
import java.util.UUID;

/** Domain seam for player-data dirty notifications; stage 4 will replace the no-op sink with the platform bridge. */
@FunctionalInterface
public interface DirtyPlayerSink {
    DirtyPlayerSink NO_OP = uuid -> { };

    void markDirty(UUID uuid);

    default void markDirty(Collection<UUID> uuids) {
        for (UUID uuid : uuids) {
            markDirty(uuid);
        }
    }
}
