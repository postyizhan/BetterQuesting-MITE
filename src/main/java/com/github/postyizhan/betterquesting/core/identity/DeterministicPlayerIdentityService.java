package com.github.postyizhan.betterquesting.core.identity;

import com.github.postyizhan.betterquesting.platform.api.IdentityMappingConflictException;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DeterministicPlayerIdentityService implements PlayerIdentityService {
    /** This namespace is a permanent save-format input and must never be regenerated or changed. */
    public static final UUID USERNAME_NAMESPACE = UUID.fromString("24d8a503-9f08-5adf-8e5f-73f42ae2f240");
    private static final Pattern CONSERVATIVE_MITE_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Map<UUID, PlayerIdentityResolution> legacyMappings = new HashMap<>();

    @Override
    public PlayerIdentityResolution resolveUsername(String username) {
        Objects.requireNonNull(username, "username");
        if (!CONSERVATIVE_MITE_USERNAME.matcher(username).matches()) {
            return PlayerIdentityResolution.unsupportedUsername(username);
        }
        return PlayerIdentityResolution.local(identityForValidUsername(username));
    }

    @Override
    public synchronized PlayerIdentityResolution resolveLegacy(UUID legacyUuid) {
        Objects.requireNonNull(legacyUuid, "legacyUuid");
        PlayerIdentityResolution resolution = legacyMappings.get(legacyUuid);
        return resolution != null ? resolution : PlayerIdentityResolution.isolated(legacyUuid);
    }

    @Override
    public synchronized PlayerIdentityResolution mapLegacy(UUID legacyUuid, String username, String decision) {
        requireUnmappedLegacy(legacyUuid);
        PlayerIdentity identity = requireIdentityForAdmin(username);
        requireUnusedIdentity(identity.id(), null);
        return putMapping(legacyUuid, identity, decision);
    }

    @Override
    public synchronized PlayerIdentityResolution mergeLegacy(UUID legacyUuid, String username, String decision) {
        requireUnmappedLegacy(legacyUuid);
        PlayerIdentity identity = requireIdentityForAdmin(username);
        if (!hasLegacyMapping(identity.id())) {
            throw new IdentityMappingConflictException(
                "merge target has no existing legacy mapping: " + identity.normalizedUsername());
        }
        return putMapping(legacyUuid, identity, decision);
    }

    @Override
    public synchronized Optional<PlayerIdentityResolution> removeLegacyMapping(UUID legacyUuid) {
        Objects.requireNonNull(legacyUuid, "legacyUuid");
        return Optional.ofNullable(legacyMappings.remove(legacyUuid));
    }

    @Override
    public synchronized PlayerIdentityResolution replaceLegacyMapping(UUID legacyUuid, String username, String decision) {
        Objects.requireNonNull(legacyUuid, "legacyUuid");
        if (!legacyMappings.containsKey(legacyUuid)) {
            throw new IllegalStateException("legacy UUID is not mapped: " + legacyUuid);
        }
        PlayerIdentity identity = requireIdentityForAdmin(username);
        requireUnusedIdentity(identity.id(), legacyUuid);
        return putMapping(legacyUuid, identity, decision);
    }

    @Override
    public synchronized Map<UUID, PlayerIdentityResolution> legacyMappingsSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(legacyMappings));
    }

    /**
     * Replaces every mapping with a previously persisted snapshot.
     *
     * <p>This bypasses the {@code map}/{@code merge}/{@code replace} guards on purpose: a valid
     * snapshot can legitimately contain many-to-one mappings created by earlier explicit merges, and
     * replaying those through {@link #mapLegacy} would be rejected. It is not an alternative path
     * for creating mappings; it only reinstates decisions an administrator already made and that
     * were already audited.
     *
     * <p>Each entry must be keyed by its own legacy UUID, carry a resolved identity, and carry an
     * identity whose UUID is the value derived from its own normalized username
     * ({@link #requireDerivedIdentity}). Without that last check a mislabelled entry loads cleanly
     * and keys progress to one player while displaying another.
     *
     * <p>Many-to-one mappings remain accepted, because {@link #mergeLegacy} legitimately creates
     * them and does not call {@code requireUnusedIdentity}.
     */
    public synchronized void restoreMappings(Map<UUID, PlayerIdentityResolution> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<UUID, PlayerIdentityResolution> validated = new HashMap<>();
        for (Map.Entry<UUID, PlayerIdentityResolution> entry : snapshot.entrySet()) {
            UUID legacyUuid = Objects.requireNonNull(entry.getKey(), "legacyUuid");
            PlayerIdentityResolution resolution = Objects.requireNonNull(entry.getValue(), "resolution");
            if (!resolution.resolved()) {
                throw new IllegalArgumentException("restored mapping has no identity: " + legacyUuid);
            }
            if (!legacyUuid.equals(resolution.legacyUuid().orElse(null))) {
                throw new IllegalArgumentException("restored mapping key does not match its resolution: " + legacyUuid);
            }
            requireDerivedIdentity(resolution.identity().orElseThrow());
            validated.put(legacyUuid, resolution);
        }
        legacyMappings.clear();
        legacyMappings.putAll(validated);
    }

    /**
     * Rejects an identity whose UUID is not the value this service derives from its own normalized
     * username.
     *
     * <p>Without this cross-field check a hand-edited record such as
     * {@code <bob's derived UUID> | "alice"} loads with no rejection, so progress is keyed to bob
     * while every report and UI shows alice.
     *
     * <p>This also makes a change to {@link #USERNAME_NAMESPACE} or to the case-folding rule fail
     * loudly instead of silently splitting one player into two identities. Absorbing that case
     * quietly is not an option, because the check could then no longer tell a rule change apart from
     * a forgery. Such a change is a breaking save-format change and needs an explicit migration.
     *
     * <p>A self-mapping, where the legacy UUID equals the derived identity UUID, is deliberately
     * still accepted. Rejecting it only on the read path would make a mapping that
     * {@link #mapLegacy} accepts unloadable after a restart, breaking a world over an astronomically
     * unlikely collision. Once derivation is verified a self-map carries no ambiguity.
     */
    static void requireDerivedIdentity(PlayerIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        PlayerIdentity derived = identityForValidUsername(identity.normalizedUsername());
        if (!derived.id().equals(identity.id())) {
            throw new IllegalArgumentException("identity UUID " + identity.id()
                + " is not the value derived from username " + identity.normalizedUsername()
                + " (expected " + derived.id() + ")");
        }
    }

    private void requireUnmappedLegacy(UUID legacyUuid) {
        Objects.requireNonNull(legacyUuid, "legacyUuid");
        if (legacyMappings.containsKey(legacyUuid)) {
            throw new IdentityMappingConflictException("legacy UUID is already mapped: " + legacyUuid);
        }
    }

    private void requireUnusedIdentity(UUID identityId, UUID ignoredLegacyUuid) {
        for (Map.Entry<UUID, PlayerIdentityResolution> entry : legacyMappings.entrySet()) {
            if (!entry.getKey().equals(ignoredLegacyUuid)
                && entry.getValue().identity().orElseThrow().id().equals(identityId)) {
                throw new IdentityMappingConflictException(
                    "local identity is already mapped from legacy UUID " + entry.getKey());
            }
        }
    }

    private boolean hasLegacyMapping(UUID identityId) {
        for (PlayerIdentityResolution resolution : legacyMappings.values()) {
            if (resolution.identity().orElseThrow().id().equals(identityId)) {
                return true;
            }
        }
        return false;
    }

    private PlayerIdentityResolution putMapping(UUID legacyUuid, PlayerIdentity identity, String decision) {
        PlayerIdentityResolution resolution = PlayerIdentityResolution.mapped(legacyUuid, identity, decision);
        legacyMappings.put(legacyUuid, resolution);
        return resolution;
    }

    private static PlayerIdentity requireIdentityForAdmin(String username) {
        Objects.requireNonNull(username, "username");
        if (!CONSERVATIVE_MITE_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username must match [A-Za-z0-9_]{1,16}");
        }
        return identityForValidUsername(username);
    }

    private static PlayerIdentity identityForValidUsername(String username) {
        String normalized = username.toLowerCase(Locale.ROOT);
        byte[] namespaceBytes = ByteBuffer.allocate(16)
            .putLong(USERNAME_NAMESPACE.getMostSignificantBits())
            .putLong(USERNAME_NAMESPACE.getLeastSignificantBits())
            .array();
        byte[] nameBytes = normalized.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1 = sha1();
        sha1.update(namespaceBytes);
        byte[] digest = sha1.digest(nameBytes);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer uuidBytes = ByteBuffer.wrap(digest);
        return new PlayerIdentity(new UUID(uuidBytes.getLong(), uuidBytes.getLong()), normalized);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide mandatory SHA-1", exception);
        }
    }
}
