package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityService;
import com.github.postyizhan.betterquesting.storage.NameCache;
import java.util.Optional;
import java.util.function.Supplier;

final class PlayerNameCacheConsumer {
    enum Outcome { UPDATED, UNCHANGED, UNRESOLVED, UNAVAILABLE }

    @FunctionalInterface
    interface IdentityContext {
        Optional<? extends PlayerIdentityService> current(Object owner);
    }

    private PlayerNameCacheConsumer() {
    }

    static Outcome consume(Object playerServer, String reportedName,
        PlayerIdentityService resolvingIdentities, Supplier<PlayerIdentityResolution> resolution,
        IdentityContext identityContext, Object nameCacheServer, NameCacheLifecycle lifecycle,
        NameCache names) {
        if (playerServer == null || playerServer != nameCacheServer || lifecycle == null
            || !lifecycle.isWritable() || resolvingIdentities == null
            || identityContext.current(playerServer).orElse(null) != resolvingIdentities
            || resolution == null) {
            return Outcome.UNAVAILABLE;
        }

        PlayerIdentityResolution resolved = resolution.get();
        if (reportedName == null || resolved == null || !resolved.resolved()) {
            return Outcome.UNRESOLVED;
        }
        PlayerIdentity identity = resolved.identity().orElseThrow();
        boolean changed = names.updateName(identity.id(), reportedName, names.isOP(identity.id()));
        return changed ? Outcome.UPDATED : Outcome.UNCHANGED;
    }
}
