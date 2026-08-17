package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.client.state.ClientPlayerNameState;
import com.github.postyizhan.betterquesting.core.identity.DeterministicPlayerIdentityService;
import com.github.postyizhan.betterquesting.network.sync.LoginNameSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginNameSnapshotCodec;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.storage.NameCache;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommonBootstrapLoginNameSnapshotTest {
    private static final UUID OTHER_ID =
        UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void captureRequiresEveryCurrentOwnerHandlerIdentityLifecycleAndCacheCondition() {
        Object owner = new Object();
        Object handler = new Object();
        DeterministicPlayerIdentityService identities = new DeterministicPlayerIdentityService();
        PlayerIdentityResolution resolution = identities.resolveUsername("Alice");
        UUID playerId = resolution.identity().orElseThrow().id();
        NameCache names = new NameCache();
        names.updateName(playerId, "Alice", false);

        Optional<LoginNameSnapshot> captured = capture(
            owner, handler, owner, handler, owner, true,
            identities, identities, resolution, "Alice", names);

        assertEquals(new LoginNameSnapshot(playerId, "Alice"), captured.orElseThrow());
        assertTrue(capture(null, handler, owner, handler, owner, true,
            identities, identities, resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, null, owner, handler, owner, true,
            identities, identities, resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, new Object(), handler, owner, true,
            identities, identities, resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, owner, new Object(), owner, true,
            identities, identities, resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, new Object(), true,
            identities, identities, resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, owner, false,
            identities, identities, resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, owner, true,
            identities, new DeterministicPlayerIdentityService(), resolution, "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, owner, true,
            identities, identities, PlayerIdentityResolution.unsupportedUsername("bad-name"),
            "Alice", names).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, owner, true,
            identities, identities, resolution, "ALICE", names).isEmpty());

        NameCache missing = new NameCache();
        assertTrue(capture(owner, handler, owner, handler, owner, true,
            identities, identities, resolution, "Alice", missing).isEmpty());
        NameCache invalid = new NameCache();
        invalid.updateName(playerId, "Bad-Name", false);
        assertTrue(capture(owner, handler, owner, handler, owner, true,
            identities, identities, resolution, "Bad-Name", invalid).isEmpty());
        NameCache ambiguous = new NameCache();
        ambiguous.updateName(playerId, "Alice", false);
        ambiguous.updateName(OTHER_ID, "ALICE", false);
        assertTrue(capture(owner, handler, owner, handler, owner, true,
            identities, identities, resolution, "Alice", ambiguous).isEmpty());
    }

    @Test
    void integratedClientPublicationRebindAndCleanupNeverMutateServerNameCacheSingleton() {
        NameCache.INSTANCE.reset();
        DeterministicPlayerIdentityService identities = new DeterministicPlayerIdentityService();
        PlayerIdentityResolution resolution = identities.resolveUsername("Alice");
        UUID playerId = resolution.identity().orElseThrow().id();
        NameCache.INSTANCE.updateName(playerId, "Alice", true);
        NameCache.INSTANCE.updateName(OTHER_ID, "Bob", false);
        try {
            Object owner = new Object();
            Object handler = new Object();
            LoginNameSnapshot serverSnapshot = capture(
                owner, handler, owner, handler, owner, true,
                identities, identities, resolution, "Alice", NameCache.INSTANCE).orElseThrow();
            LoginNameSnapshot detachedClientSnapshot = LoginNameSnapshotCodec.decode(
                LoginNameSnapshotCodec.encode(serverSnapshot)).orElseThrow();
            ClientPlayerNameState client = new ClientPlayerNameState();
            ClientPlayerNameState.ConnectionLease oldLease = client.openConnectionLease();

            oldLease.publish(detachedClientSnapshot);
            assertServerCacheUnchanged(playerId);
            ClientPlayerNameState.ConnectionLease replacement = client.openConnectionLease();
            assertTrue(client.current().isEmpty());
            assertServerCacheUnchanged(playerId);
            replacement.publish(detachedClientSnapshot);
            oldLease.close();
            assertEquals(detachedClientSnapshot, client.current().orElseThrow());
            assertServerCacheUnchanged(playerId);
            replacement.close();

            assertTrue(client.current().isEmpty());
            assertServerCacheUnchanged(playerId);
        } finally {
            NameCache.INSTANCE.reset();
        }
    }

    private static Optional<LoginNameSnapshot> capture(
        Object serverOwner,
        Object handler,
        Object playerServer,
        Object playerHandler,
        Object cacheServer,
        boolean cacheWritable,
        DeterministicPlayerIdentityService resolvingIdentities,
        DeterministicPlayerIdentityService currentIdentities,
        PlayerIdentityResolution resolution,
        String reportedName,
        NameCache names
    ) {
        return CommonBootstrap.captureLoginNameSnapshot(
            serverOwner,
            handler,
            playerServer,
            playerHandler,
            cacheServer,
            cacheWritable,
            resolvingIdentities,
            currentIdentities,
            resolution,
            reportedName,
            names);
    }

    private static void assertServerCacheUnchanged(UUID playerId) {
        assertEquals(2, NameCache.INSTANCE.size());
        assertEquals("Alice", NameCache.INSTANCE.getName(playerId));
        assertTrue(NameCache.INSTANCE.isOP(playerId));
        assertEquals("Bob", NameCache.INSTANCE.getName(OTHER_ID));
        assertFalse(NameCache.INSTANCE.isOP(OTHER_ID));
    }
}
