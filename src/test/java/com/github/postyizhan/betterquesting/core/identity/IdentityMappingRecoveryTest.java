package com.github.postyizhan.betterquesting.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentitySource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityMappingRecoveryTest {
    private static final UUID LEGACY_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LEGACY_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    // Derived, never hardcoded, so the name and its UUID cannot drift apart.
    private static final PlayerIdentity ALICE =
        new DeterministicPlayerIdentityService().resolveUsername("alice").identity().orElseThrow();
    private static final PlayerIdentity BOB =
        new DeterministicPlayerIdentityService().resolveUsername("bob").identity().orElseThrow();

    @TempDir
    Path temporaryDirectory;

    @Test
    void aCorruptMappingFileBlocksLoadingRatherThanSilentlyIsolatingEveryLegacySave() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "admin confirmed");
        Path mappingFile = temporaryDirectory.resolve(LegacyMappingStore.MAPPING_PATH);
        Files.writeString(mappingFile,
            Files.readString(mappingFile, StandardCharsets.UTF_8).replace("alice", "carol"),
            StandardCharsets.UTF_8);

        PersistentPlayerIdentityService restarted = service();

        // Loading must fail loudly. Reporting corruption as "no mappings" would let the isolated
        // legacy save be re-claimed by a derived identity.
        assertThrows(CorruptIdentityMappingException.class, restarted::loadFromStorage);
    }

    @Test
    void aFailedLoadLeavesNoPartiallyRestoredMappings() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "admin confirmed");
        Path mappingFile = temporaryDirectory.resolve(LegacyMappingStore.MAPPING_PATH);
        Files.writeString(mappingFile,
            Files.readString(mappingFile, StandardCharsets.UTF_8).replace("alice", "carol"),
            StandardCharsets.UTF_8);

        PersistentPlayerIdentityService restarted = service();
        assertThrows(CorruptIdentityMappingException.class, restarted::loadFromStorage);

        assertEquals(Map.of(), restarted.legacyMappingsSnapshot());
        assertFalse(restarted.resolveLegacy(LEGACY_A).resolved());
    }

    @Test
    void aFailedAuditAppendRollsBackTheInMemoryMapping() throws IOException {
        // A directory occupying the audit path makes the real append fail, without needing a seam.
        Files.createDirectories(temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH));
        PersistentPlayerIdentityService service = service();

        assertThrows(RuntimeException.class, () -> service.mapLegacy(LEGACY_A, "Alice", "audit will fail"));

        // A mapping with no audit record must not remain visible.
        assertFalse(service.resolveLegacy(LEGACY_A).resolved());
        assertEquals(Map.of(), service.legacyMappingsSnapshot());
    }

    @Test
    void aFailedAuditAppendLeavesThePreviousSnapshotUnchanged() throws IOException {
        PersistentPlayerIdentityService first = service();
        first.loadFromStorage();
        first.mapLegacy(LEGACY_A, "Alice", "recorded before the failure");

        // Load succeeds first, then the audit path is blocked so only the append can fail.
        PersistentPlayerIdentityService blocked = service();
        blocked.loadFromStorage();
        Path audit = temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH);
        Files.delete(audit);
        Files.createDirectories(audit);
        assertThrows(RuntimeException.class, () -> blocked.mapLegacy(LEGACY_B, "Bob", "audit will fail"));
        assertFalse(blocked.resolveLegacy(LEGACY_B).resolved());
        assertTrue(blocked.resolveLegacy(LEGACY_A).resolved());

        Files.delete(audit);
        PersistentPlayerIdentityService restarted = service();
        restarted.loadFromStorage();
        assertTrue(restarted.resolveLegacy(LEGACY_A).resolved());
        assertFalse(restarted.resolveLegacy(LEGACY_B).resolved());
    }

    @Test
    void restoreMappingsRejectsAnIdentityUuidNotDerivedFromItsUsername() {
        DeterministicPlayerIdentityService service = new DeterministicPlayerIdentityService();
        // Bob's derived UUID carrying alice's name: progress would key to bob while all reporting
        // shows alice.
        PlayerIdentity mislabelled = new PlayerIdentity(BOB.id(), ALICE.normalizedUsername());
        Map<UUID, PlayerIdentityResolution> forged = new HashMap<>();
        forged.put(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, mislabelled, "forged"));

        IllegalArgumentException rejected =
            assertThrows(IllegalArgumentException.class, () -> service.restoreMappings(forged));

        assertTrue(rejected.getMessage().contains("is not the value derived from username alice"));
        assertFalse(service.resolveLegacy(LEGACY_A).resolved());
    }

    @Test
    void loadRejectsAPersistedRecordWhoseIdentityIsNotDerivedFromItsUsername() throws IOException {
        // The forged record is written with a valid CRC, so only the derivation check can catch it.
        PlayerIdentity mislabelled = new PlayerIdentity(BOB.id(), ALICE.normalizedUsername());
        Path mappingFile = temporaryDirectory.resolve(LegacyMappingStore.MAPPING_PATH);
        Files.createDirectories(mappingFile.getParent());
        Files.writeString(mappingFile,
            IdentityRecordCodec.encode(LegacyMappingStore.HEADER_MAGIC, List.of("1")) + "\n"
                + IdentityRecordCodec.encode(LegacyMappingStore.RECORD_MAGIC, List.of(
                    LEGACY_A.toString(), mislabelled.id().toString(), mislabelled.normalizedUsername(),
                    PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING.name(), "forged")) + "\n",
            StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt = assertThrows(CorruptIdentityMappingException.class,
            () -> service().loadFromStorage());

        assertTrue(corrupt.rejections().get(0).reason().contains("is not the value derived from username"));
    }

    @Test
    void restoreMappingsRejectsMismatchedKeysUnresolvedEntriesAndNull() {
        DeterministicPlayerIdentityService service = new DeterministicPlayerIdentityService();
        Map<UUID, PlayerIdentityResolution> mismatchedKey = new HashMap<>();
        mismatchedKey.put(LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "mismatched"));
        Map<UUID, PlayerIdentityResolution> unresolved = new HashMap<>();
        unresolved.put(LEGACY_A, PlayerIdentityResolution.isolated(LEGACY_A));

        assertThrows(IllegalArgumentException.class, () -> service.restoreMappings(mismatchedKey));
        assertThrows(IllegalArgumentException.class, () -> service.restoreMappings(unresolved));
        assertThrows(NullPointerException.class, () -> service.restoreMappings(null));
    }

    @Test
    void restoreMappingsReplacesRatherThanMergesPreviousState() {
        DeterministicPlayerIdentityService service = new DeterministicPlayerIdentityService();
        service.mapLegacy(LEGACY_A, "Alice", "before restore");

        service.restoreMappings(Map.of(LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_B, ALICE, "restored")));

        assertFalse(service.resolveLegacy(LEGACY_A).resolved());
        assertTrue(service.resolveLegacy(LEGACY_B).resolved());
    }

    @Test
    void restoreMappingsReinstatesMergedManyToOneMappingsThatMapLegacyWouldReject() {
        DeterministicPlayerIdentityService service = new DeterministicPlayerIdentityService();

        service.restoreMappings(Map.of(
            LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "primary"),
            LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_B, ALICE, "merged")));

        assertEquals(service.resolveLegacy(LEGACY_A).identity(), service.resolveLegacy(LEGACY_B).identity());
    }

    @Test
    void refusesFurtherMutationsAfterASnapshotWriteFailure() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        // A directory at the snapshot path makes the atomic write fail after the audit entry lands.
        Path mappingFile = temporaryDirectory.resolve(LegacyMappingStore.MAPPING_PATH);
        Files.createDirectories(mappingFile);

        assertThrows(RuntimeException.class, () -> service.mapLegacy(LEGACY_A, "Alice", "snapshot will fail"));

        assertTrue(service.isPoisoned());
        assertTrue(service.poisonedReason().orElseThrow().contains("ahead of disk"));
        // Memory is known to be ahead of disk, so no further mutation may stack onto it.
        assertThrows(IllegalStateException.class, () -> service.mapLegacy(LEGACY_B, "Bob", "must be refused"));
    }

    private PersistentPlayerIdentityService service() {
        return new PersistentPlayerIdentityService(new DirectoryWorldStorage(temporaryDirectory));
    }
}
