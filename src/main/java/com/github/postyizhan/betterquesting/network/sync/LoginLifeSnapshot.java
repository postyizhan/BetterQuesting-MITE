package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.api.storage.ILifeDatabase;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-authored Life value for the player attached to one login connection. */
public record LoginLifeSnapshot(int lives) {
    public static final String FORMAT_ID = "betterquesting:life_sync";
    public static final int FORMAT_VERSION = 1;

    public static LoginLifeSnapshot capture(ILifeDatabase database, UUID playerId) {
        Objects.requireNonNull(database, "database");
        return new LoginLifeSnapshot(database.getLives(
            Objects.requireNonNull(playerId, "playerId")));
    }
}
