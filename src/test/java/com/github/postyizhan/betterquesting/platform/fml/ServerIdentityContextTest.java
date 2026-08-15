package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.identity.CorruptIdentityMappingException;
import com.github.postyizhan.betterquesting.core.identity.LegacyMappingStore;
import com.github.postyizhan.betterquesting.core.identity.PersistentPlayerIdentityService;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerIdentityContextTest {
    private static final UUID LEGACY_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LEGACY_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final Object firstOwner = new String("equal owner value");
    private final Object secondOwner = new String("equal owner value");
    private final Object restartOwner = new Object();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void retireTestOwners() {
        ServerIdentityContext.retire(firstOwner);
        ServerIdentityContext.retire(secondOwner);
        ServerIdentityContext.retire(restartOwner);
    }

    @Test
    void matchingOwnerRetiresBinding() throws IOException {
        PersistentPlayerIdentityService service = bind(firstOwner, "world");

        assertSame(service, ServerIdentityContext.current(firstOwner).orElseThrow());

        ServerIdentityContext.retire(firstOwner);

        assertTrue(ServerIdentityContext.current(firstOwner).isEmpty());
    }

    @Test
    void staleOldOwnerCallbackCannotClearNewerBinding() throws IOException {
        assertEquals(firstOwner, secondOwner);
        assertNotSame(firstOwner, secondOwner);
        bind(firstOwner, "first-world");
        ServerIdentityContext.retire(firstOwner);
        PersistentPlayerIdentityService second = bind(secondOwner, "second-world");

        ServerIdentityContext.retire(firstOwner);

        assertSame(second, ServerIdentityContext.current(secondOwner).orElseThrow());
        assertTrue(ServerIdentityContext.current(firstOwner).isEmpty());
    }

    @Test
    void cleanRebindAfterRetireUsesOnlyTheNewWorldStore() throws IOException {
        PersistentPlayerIdentityService first = bind(firstOwner, "first-world");
        first.mapLegacy(LEGACY_A, "Alice", "first world mapping");
        ServerIdentityContext.retire(firstOwner);

        PersistentPlayerIdentityService second = bind(secondOwner, "second-world");
        second.mapLegacy(LEGACY_B, "Bob", "second world mapping");

        assertTrue(ServerIdentityContext.current(firstOwner).isEmpty());
        assertSame(second, ServerIdentityContext.current(secondOwner).orElseThrow());
        assertFalse(second.resolveLegacy(LEGACY_A).resolved());
        assertTrue(second.resolveLegacy(LEGACY_B).resolved());
    }

    @Test
    void restartLoadsMappingsFromTheSameWorldStore() throws IOException {
        PersistentPlayerIdentityService first = bind(firstOwner, "world");
        first.mapLegacy(LEGACY_A, "Alice", "persist across restart");
        ServerIdentityContext.retire(firstOwner);

        PersistentPlayerIdentityService restarted = bind(restartOwner, "world");

        assertTrue(restarted.resolveLegacy(LEGACY_A).resolved());
        assertSame(restarted, ServerIdentityContext.current(restartOwner).orElseThrow());
    }

    @Test
    void staleDifferentOwnerBindCannotReplaceNewerBinding() throws IOException {
        bind(firstOwner, "first-world");
        ServerIdentityContext.retire(firstOwner);
        PersistentPlayerIdentityService second = bind(secondOwner, "second-world");

        assertThrows(IllegalStateException.class, () -> bind(firstOwner, "stale-first-world"));

        assertSame(second, ServerIdentityContext.current(secondOwner).orElseThrow());
        assertTrue(ServerIdentityContext.current(firstOwner).isEmpty());
    }

    @Test
    void corruptDifferentOwnerBindIsRejectedBeforeLoadAndPreservesNewerBinding() throws IOException {
        PersistentPlayerIdentityService second = bind(secondOwner, "second-world");
        Path corruptWorld = temporaryDirectory.resolve("corrupt-world");
        Path mapping = corruptWorld.resolve(LegacyMappingStore.MAPPING_PATH);
        Files.createDirectories(mapping.getParent());
        Files.writeString(mapping, "not an identity mapping snapshot", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
            () -> ServerIdentityContext.bind(firstOwner, new DirectoryWorldStorage(corruptWorld)));

        assertTrue(ServerIdentityContext.current(firstOwner).isEmpty());
        assertSame(second, ServerIdentityContext.current(secondOwner).orElseThrow());
    }

    @Test
    void failedSameOwnerReloadLeavesContextFailClosed() throws IOException {
        bind(firstOwner, "first-world");
        Path corruptWorld = temporaryDirectory.resolve("corrupt-world");
        Path mapping = corruptWorld.resolve(LegacyMappingStore.MAPPING_PATH);
        Files.createDirectories(mapping.getParent());
        Files.writeString(mapping, "not an identity mapping snapshot", StandardCharsets.UTF_8);

        assertThrows(CorruptIdentityMappingException.class,
            () -> ServerIdentityContext.bind(firstOwner, new DirectoryWorldStorage(corruptWorld)));

        assertTrue(ServerIdentityContext.current(firstOwner).isEmpty());
    }

    private PersistentPlayerIdentityService bind(Object owner, String world) throws IOException {
        return ServerIdentityContext.bind(
            owner, new DirectoryWorldStorage(temporaryDirectory.resolve(world)));
    }
}
