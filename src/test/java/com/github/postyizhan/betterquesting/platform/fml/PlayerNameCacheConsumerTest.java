package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.identity.DeterministicPlayerIdentityService;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityService;
import com.github.postyizhan.betterquesting.storage.NameCache;
import com.github.postyizhan.betterquesting.storage.NameCachePersistence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerNameCacheConsumerTest {
    private static final UUID ALICE_ID = UUID.fromString("defc59df-21a5-5a2d-b766-35e73bfb50ec");
    private static final UUID EXISTING_ID = UUID.fromString("00000000-0000-4000-8000-000000000701");

    @TempDir
    Path temporaryDirectory;

    @Test
    void derivedIdentityUsesReportedCaseAndRepeatedLoginIsIdempotent() throws IOException {
        Object server = new Object();
        PlayerIdentityService identities = new DeterministicPlayerIdentityService();
        NameCache names = new NameCache();
        NameCacheLifecycle lifecycle = writableLifecycle("derived", names);

        assertEquals(PlayerNameCacheConsumer.Outcome.UPDATED,
            consume(server, "Alice", identities, server, lifecycle, names));
        assertEquals("Alice", names.getName(ALICE_ID));
        assertEquals(1, names.size());

        assertEquals(PlayerNameCacheConsumer.Outcome.UNCHANGED,
            consume(server, "Alice", identities, server, lifecycle, names));
        assertEquals(1, names.size());
    }

    @Test
    void refreshedNamePreservesExistingOperatorBit() throws IOException {
        Object server = new Object();
        PlayerIdentityService identities = new DeterministicPlayerIdentityService();
        NameCache names = new NameCache();
        NameCacheLifecycle lifecycle = writableLifecycle("operator", names);
        names.updateName(ALICE_ID, "alice", true);

        assertEquals(PlayerNameCacheConsumer.Outcome.UPDATED,
            consume(server, "ALICE", identities, server, lifecycle, names));

        assertEquals("ALICE", names.getName(ALICE_ID));
        assertTrue(names.isOP(ALICE_ID));
    }

    @Test
    void unsupportedUsernameIsIsolatedWithoutFallbackOrMappingMutation() throws IOException {
        Object server = new Object();
        PlayerIdentityService identities = new DeterministicPlayerIdentityService();
        NameCache names = new NameCache();
        NameCacheLifecycle lifecycle = writableLifecycle("unsupported", names);
        names.updateName(EXISTING_ID, "Existing", false);

        assertEquals(PlayerNameCacheConsumer.Outcome.UNRESOLVED,
            consume(server, "bad-name!", identities, server, lifecycle, names));

        assertEquals(1, names.size());
        assertEquals("Existing", names.getName(EXISTING_ID));
        assertTrue(identities.legacyMappingsSnapshot().isEmpty());
    }

    @Test
    void unavailableOrMismatchedBindingsAndWriteDisabledLifecycleGateBeforeResolution()
        throws IOException {
        Object server = new Object();
        Object otherServer = new Object();
        PlayerIdentityService identities = new DeterministicPlayerIdentityService();
        PlayerIdentityService otherIdentities = new DeterministicPlayerIdentityService();
        NameCache names = new NameCache();
        NameCacheLifecycle writable = writableLifecycle("gating-writable", names);
        NameCacheLifecycle blocked = blockedLifecycle("gating-blocked", names);
        AtomicInteger resolutions = new AtomicInteger();

        assertEquals(PlayerNameCacheConsumer.Outcome.UNAVAILABLE,
            PlayerNameCacheConsumer.consume(server, "Alice", identities, () -> {
                resolutions.incrementAndGet();
                return identities.resolveUsername("Alice");
            }, owner -> Optional.empty(), server, writable, names));
        assertEquals(PlayerNameCacheConsumer.Outcome.UNAVAILABLE,
            PlayerNameCacheConsumer.consume(server, "Alice", identities, () -> {
                resolutions.incrementAndGet();
                return identities.resolveUsername("Alice");
            }, owner -> Optional.of(otherIdentities), server, writable, names));
        assertEquals(PlayerNameCacheConsumer.Outcome.UNAVAILABLE,
            PlayerNameCacheConsumer.consume(server, "Alice", identities, () -> {
                resolutions.incrementAndGet();
                return identities.resolveUsername("Alice");
            }, owner -> Optional.of(identities), otherServer, writable, names));
        assertEquals(PlayerNameCacheConsumer.Outcome.UNAVAILABLE,
            PlayerNameCacheConsumer.consume(server, "Alice", identities, () -> {
                resolutions.incrementAndGet();
                return identities.resolveUsername("Alice");
            }, owner -> Optional.of(identities), server, null, names));
        assertEquals(PlayerNameCacheConsumer.Outcome.UNAVAILABLE,
            PlayerNameCacheConsumer.consume(server, "Alice", identities, () -> {
                resolutions.incrementAndGet();
                return identities.resolveUsername("Alice");
            }, owner -> Optional.of(identities), server, blocked, names));

        assertEquals(0, resolutions.get());
        assertEquals(0, names.size());
    }

    @Test
    void worldSavePersistsUpdateForRestartWithoutImmediateFlush() throws IOException {
        Object server = new Object();
        PlayerIdentityService identities = new DeterministicPlayerIdentityService();
        Path world = temporaryDirectory.resolve("restart");
        DirectoryWorldStorage storage = new DirectoryWorldStorage(world);
        NameCache names = new NameCache();
        NameCacheLifecycle lifecycle = new NameCacheLifecycle(storage, names, "test-build");
        lifecycle.onServerStarted();

        assertEquals(PlayerNameCacheConsumer.Outcome.UPDATED,
            consume(server, "Alice", identities, server, lifecycle, names));
        assertFalse(Files.exists(world.resolve(NameCachePersistence.PATH)));

        lifecycle.onWorldSave();
        NameCache restartedNames = new NameCache();
        NameCacheLifecycle restarted = new NameCacheLifecycle(storage, restartedNames, "test-build");
        restarted.onServerStarted();

        assertEquals("Alice", restartedNames.getName(ALICE_ID));
        assertFalse(restartedNames.isOP(ALICE_ID));
        assertEquals(1, restartedNames.size());
    }

    private PlayerNameCacheConsumer.Outcome consume(Object playerServer, String name,
        PlayerIdentityService identities, Object cacheServer, NameCacheLifecycle lifecycle,
        NameCache names) {
        return PlayerNameCacheConsumer.consume(playerServer, name, identities,
            () -> identities.resolveUsername(name), owner -> Optional.of(identities),
            cacheServer, lifecycle, names);
    }

    private NameCacheLifecycle writableLifecycle(String directory, NameCache names) throws IOException {
        NameCacheLifecycle lifecycle = new NameCacheLifecycle(
            new DirectoryWorldStorage(temporaryDirectory.resolve(directory)), names, "test-build");
        lifecycle.onServerStarted();
        return lifecycle;
    }

    private NameCacheLifecycle blockedLifecycle(String directory, NameCache names) throws IOException {
        Path world = temporaryDirectory.resolve(directory);
        Files.createDirectories(world);
        Files.writeString(world.resolve(NameCachePersistence.PATH),
            "{\"nameCache:9\":{},\"mitePortFormat:8\":\"2\"}", StandardCharsets.UTF_8);
        NameCacheLifecycle lifecycle = new NameCacheLifecycle(
            new DirectoryWorldStorage(world), names, "test-build");
        lifecycle.onServerStarted();
        return lifecycle;
    }
}
