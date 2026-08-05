package com.github.postyizhan.betterquesting.platform.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves platform player names and controls explicit legacy UUID mappings.
 *
 * <p>The current implementation keeps mappings only in process memory. Until WorldStorage and the migration
 * batch add durable mapping records and append-only audit, callers must not write identity-keyed progress files
 * from these results. Mapping removal and replacement decisions do not survive a restart.</p>
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
