package com.github.postyizhan.betterquesting.platform.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves platform player names and controls explicit legacy UUID mappings.
 *
 * <p>The production implementation stores explicit mapping decisions in world-bound durable storage with an
 * append-only audit trail. Callers must still use only the service bound to their current server and must isolate
 * unresolved identities rather than inventing fallback UUIDs.</p>
 */
public interface PlayerIdentityService {
    PlayerIdentityResolution resolveUsername(String username);

    PlayerIdentityResolution resolveLegacy(UUID legacyUuid);

    PlayerIdentityResolution mapLegacy(UUID legacyUuid, String username, String decision);

    PlayerIdentityResolution mergeLegacy(UUID legacyUuid, String username, String decision);

    Optional<PlayerIdentityResolution> removeLegacyMapping(UUID legacyUuid);

    PlayerIdentityResolution replaceLegacyMapping(UUID legacyUuid, String username, String decision);

    Map<UUID, PlayerIdentityResolution> legacyMappingsSnapshot();
}
