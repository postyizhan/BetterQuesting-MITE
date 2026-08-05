package com.github.postyizhan.betterquesting.platform.api;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PlayerIdentity {
    private static final Pattern NORMALIZED_USERNAME = Pattern.compile("[a-z0-9_]{1,16}");

    private final UUID id;
    private final String normalizedUsername;

    public PlayerIdentity(UUID id, String normalizedUsername) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(normalizedUsername, "normalizedUsername");
        if (!NORMALIZED_USERNAME.matcher(normalizedUsername).matches()
            || !normalizedUsername.equals(normalizedUsername.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("normalizedUsername must match [a-z0-9_]{1,16}");
        }
        this.normalizedUsername = normalizedUsername;
    }

    public UUID id() {
        return id;
    }

    public String normalizedUsername() {
        return normalizedUsername;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PlayerIdentity other)) {
            return false;
        }
        return id.equals(other.id) && normalizedUsername.equals(other.normalizedUsername);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, normalizedUsername);
    }

    @Override
    public String toString() {
        return normalizedUsername + " (" + id + ")";
    }
}
