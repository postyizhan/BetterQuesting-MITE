package com.github.postyizhan.betterquesting.platform.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerIdentityResolution {
    private final UUID legacyUuid;
    private final PlayerIdentity identity;
    private final PlayerIdentitySource source;
    private final String decision;

    private PlayerIdentityResolution(UUID legacyUuid, PlayerIdentity identity, PlayerIdentitySource source, String decision) {
        this.legacyUuid = legacyUuid;
        this.identity = identity;
        this.source = Objects.requireNonNull(source, "source");
        this.decision = requireDecision(decision);
    }

    public static PlayerIdentityResolution local(PlayerIdentity identity) {
        return new PlayerIdentityResolution(null, Objects.requireNonNull(identity, "identity"),
            PlayerIdentitySource.MITE_USERNAME_DERIVED, "derived from normalized MITE username");
    }

    public static PlayerIdentityResolution mapped(UUID legacyUuid, PlayerIdentity identity, String decision) {
        return new PlayerIdentityResolution(Objects.requireNonNull(legacyUuid, "legacyUuid"),
            Objects.requireNonNull(identity, "identity"), PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING, decision);
    }

    public static PlayerIdentityResolution isolated(UUID legacyUuid) {
        return new PlayerIdentityResolution(Objects.requireNonNull(legacyUuid, "legacyUuid"), null,
            PlayerIdentitySource.LEGACY_UUID_UNMAPPED, "isolated pending administrator mapping");
    }

    public static PlayerIdentityResolution unsupportedUsername(String reportedUsername) {
        String reportValue = reportedUsername == null ? "<null>" : reportedUsername;
        return new PlayerIdentityResolution(null, null, PlayerIdentitySource.UNSUPPORTED_USERNAME,
            "unsupported MITE username: " + reportValue);
    }

    public Optional<UUID> legacyUuid() {
        return Optional.ofNullable(legacyUuid);
    }

    public Optional<PlayerIdentity> identity() {
        return Optional.ofNullable(identity);
    }

    public PlayerIdentitySource source() {
        return source;
    }

    public String decision() {
        return decision;
    }

    public boolean resolved() {
        return identity != null;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PlayerIdentityResolution other)) {
            return false;
        }
        return Objects.equals(legacyUuid, other.legacyUuid)
            && Objects.equals(identity, other.identity)
            && source == other.source
            && decision.equals(other.decision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(legacyUuid, identity, source, decision);
    }

    @Override
    public String toString() {
        return "PlayerIdentityResolution{legacyUuid=" + legacyUuid + ", identity=" + identity
            + ", source=" + source + ", decision='" + decision + "'}";
    }

    private static String requireDecision(String decision) {
        Objects.requireNonNull(decision, "decision");
        if (decision.trim().isEmpty()) {
            throw new IllegalArgumentException("decision must not be blank");
        }
        return decision;
    }
}
