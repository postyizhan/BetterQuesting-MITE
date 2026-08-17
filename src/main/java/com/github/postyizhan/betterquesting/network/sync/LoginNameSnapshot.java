package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.api.storage.INameCache;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable server-authored identity and cached display name for the login player only. */
public record LoginNameSnapshot(UUID playerId, String displayName) {
    public static final String FORMAT_ID = "betterquesting:login_name";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_DISPLAY_NAME_LENGTH = 16;

    private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public LoginNameSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(displayName, "displayName");
        if (!DISPLAY_NAME.matcher(displayName).matches()) {
            throw new IllegalArgumentException("displayName must match [A-Za-z0-9_]{1,16}");
        }
    }

    public static Optional<LoginNameSnapshot> capture(
        INameCache names,
        UUID playerId,
        String reportedName
    ) {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(playerId, "playerId");
        if (reportedName == null) {
            return Optional.empty();
        }
        String cachedName = names.getName(playerId);
        if (!reportedName.equals(cachedName) || !playerId.equals(names.getUUID(cachedName))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LoginNameSnapshot(playerId, cachedName));
        } catch (IllegalArgumentException invalidCacheEntry) {
            return Optional.empty();
        }
    }

    public String formatId() {
        return FORMAT_ID;
    }

    public int formatVersion() {
        return FORMAT_VERSION;
    }
}
