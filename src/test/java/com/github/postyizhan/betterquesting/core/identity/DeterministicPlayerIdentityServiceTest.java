package com.github.postyizhan.betterquesting.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.platform.api.IdentityMappingConflictException;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentitySource;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeterministicPlayerIdentityServiceTest {
    private static final UUID LEGACY_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LEGACY_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final DeterministicPlayerIdentityService service = new DeterministicPlayerIdentityService();

    @Test
    void locksNamespaceNormalizationUtf8AndUuidVersionBits() {
        PlayerIdentityResolution resolution = service.resolveUsername("Test_Player");

        assertEquals(UUID.fromString("536d10cf-c585-5a3e-9060-f818e26945f6"), resolution.identity().orElseThrow().id());
        assertEquals("test_player", resolution.identity().orElseThrow().normalizedUsername());
        assertEquals(5, resolution.identity().orElseThrow().id().version());
        assertEquals(2, resolution.identity().orElseThrow().id().variant());
        assertEquals(PlayerIdentitySource.MITE_USERNAME_DERIVED, resolution.source());
    }

    @Test
    void treatsAsciiCaseVariantsAsTheSameUsername() {
        assertEquals(service.resolveUsername("Alice").identity(), service.resolveUsername("aLiCe").identity());
    }

    @Test
    void rejectsNullUsername() {
        assertThrows(NullPointerException.class, () -> service.resolveUsername(null));
    }

    @Test
    void unsupportedNullPlatformUsernameIsReportableWithoutAnIdentity() {
        PlayerIdentityResolution resolution = PlayerIdentityResolution.unsupportedUsername(null);

        assertFalse(resolution.resolved());
        assertTrue(resolution.identity().isEmpty());
        assertEquals(PlayerIdentitySource.UNSUPPORTED_USERNAME, resolution.source());
        assertEquals("unsupported MITE username: <null>", resolution.decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "a-b", "a:b", "Jos\u00e9", "\u73a9\u5bb6", "abcdefghijklmnopq"})
    void reportsUnsupportedRuntimeNamesWithoutCreatingAnIdentity(String username) {
        PlayerIdentityResolution resolution = service.resolveUsername(username);

        assertFalse(resolution.resolved());
        assertEquals(PlayerIdentitySource.UNSUPPORTED_USERNAME, resolution.source());
        assertEquals("unsupported MITE username: " + username, resolution.decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a-b", "Jos\u00e9", "abcdefghijklmnopq"})
    void administratorOperationsStillRejectUnsupportedNames(String username) {
        assertThrows(IllegalArgumentException.class,
            () -> service.mapLegacy(LEGACY_A, username, "administrator supplied mapping"));
        assertThrows(IllegalArgumentException.class,
            () -> service.mergeLegacy(LEGACY_A, username, "administrator supplied merge"));

        service.mapLegacy(LEGACY_A, "Alice", "valid source mapping");
        assertThrows(IllegalArgumentException.class,
            () -> service.replaceLegacyMapping(LEGACY_A, username, "administrator supplied replacement"));
    }

    @Test
    void acceptsDeclaredOneCharacterLowerBound() {
        PlayerIdentityResolution resolution = service.resolveUsername("A");

        assertTrue(resolution.resolved());
        assertEquals("a", resolution.identity().orElseThrow().normalizedUsername());
    }

    @Test
    void aRenameProducesADifferentLocalIdentity() {
        assertNotEquals(service.resolveUsername("OldName").identity(), service.resolveUsername("NewName").identity());
    }

    @Test
    void keepsUnmappedLegacyUuidIsolatedInsteadOfDerivingByName() {
        PlayerIdentityResolution resolution = service.resolveLegacy(LEGACY_A);

        assertFalse(resolution.resolved());
        assertEquals(LEGACY_A, resolution.legacyUuid().orElseThrow());
        assertEquals(PlayerIdentitySource.LEGACY_UUID_UNMAPPED, resolution.source());
        assertEquals("isolated pending administrator mapping", resolution.decision());
    }

    @Test
    void explicitMappingReportsLegacySourceAndAdministratorDecision() {
        PlayerIdentityResolution mapped = service.mapLegacy(LEGACY_A, "Alice", "admin confirmed from server backup");

        assertEquals(mapped.identity(), service.resolveUsername("alice").identity());
        assertEquals(mapped, service.resolveLegacy(LEGACY_A));
        assertEquals(PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING, mapped.source());
        assertEquals("admin confirmed from server backup", mapped.decision());
        assertEquals(LEGACY_A, mapped.legacyUuid().orElseThrow());
    }

    @Test
    void rejectsLegacyRemapAndAccidentalManyToOneMapping() {
        service.mapLegacy(LEGACY_A, "Alice", "first mapping");

        assertThrows(IdentityMappingConflictException.class,
            () -> service.mapLegacy(LEGACY_A, "Bob", "same legacy remap"));
        assertThrows(IdentityMappingConflictException.class,
            () -> service.mapLegacy(LEGACY_B, "ALICE", "accidental merge"));
    }

    @Test
    void onlyExplicitMergeApiAllowsManyLegacyUuidsToShareAnIdentity() {
        service.mapLegacy(LEGACY_A, "Alice", "primary old account");
        PlayerIdentityResolution merged = service.mergeLegacy(LEGACY_B, "alice", "admin approved account merge");

        assertEquals(service.resolveLegacy(LEGACY_A).identity(), merged.identity());
        assertEquals("admin approved account merge", merged.decision());
        assertEquals(PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING, merged.source());
    }

    @Test
    void mergeRequiresAnExistingMappedTarget() {
        IdentityMappingConflictException absent = assertThrows(IdentityMappingConflictException.class,
            () -> service.mergeLegacy(LEGACY_B, "Alice", "target does not exist"));
        assertEquals("merge target has no existing legacy mapping: alice", absent.getMessage());

        service.mapLegacy(LEGACY_A, "Alice", "primary old account");
        IdentityMappingConflictException typo = assertThrows(IdentityMappingConflictException.class,
            () -> service.mergeLegacy(LEGACY_B, "Alicee", "misspelled target"));
        assertEquals("merge target has no existing legacy mapping: alicee", typo.getMessage());
        assertFalse(service.resolveLegacy(LEGACY_B).resolved());
    }

    @Test
    void removeAndReplaceHaveExplicitNonDestructivePolicies() {
        service.mapLegacy(LEGACY_A, "Alice", "initial");
        assertEquals(LEGACY_A, service.removeLegacyMapping(LEGACY_A).orElseThrow().legacyUuid().orElseThrow());
        assertFalse(service.resolveLegacy(LEGACY_A).resolved());
        assertTrue(service.removeLegacyMapping(LEGACY_A).isEmpty());

        service.mapLegacy(LEGACY_A, "Alice", "restored");
        PlayerIdentityResolution replacement = service.replaceLegacyMapping(LEGACY_A, "Bob", "admin corrected mapping");
        assertEquals(service.resolveUsername("bob").identity(), replacement.identity());
        assertEquals("admin corrected mapping", replacement.decision());
        IllegalStateException missing = assertThrows(IllegalStateException.class,
            () -> service.replaceLegacyMapping(LEGACY_B, "Carol", "missing source mapping"));
        assertEquals("legacy UUID is not mapped: " + LEGACY_B, missing.getMessage());
    }

    @Test
    void replacementStillRejectsCollisionWithAnotherLegacyMapping() {
        service.mapLegacy(LEGACY_A, "Alice", "first");
        service.mapLegacy(LEGACY_B, "Bob", "second");

        assertThrows(IdentityMappingConflictException.class,
            () -> service.replaceLegacyMapping(LEGACY_A, "bob", "bad correction"));
        assertEquals(service.resolveUsername("alice").identity(), service.resolveLegacy(LEGACY_A).identity());
    }

    @Test
    void mappingSnapshotIsImmutableAndDetached() {
        service.mapLegacy(LEGACY_A, "Alice", "first");
        Map<UUID, PlayerIdentityResolution> snapshot = service.legacyMappingsSnapshot();

        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        service.mapLegacy(LEGACY_B, "Bob", "second");
        assertEquals(1, snapshot.size());
        assertEquals(2, service.legacyMappingsSnapshot().size());
    }

    @Test
    void resolutionHasFourFieldValueSemantics() {
        PlayerIdentity firstIdentity = new PlayerIdentity(
            UUID.fromString("536d10cf-c585-5a3e-9060-f818e26945f6"), "test_player");
        PlayerIdentity secondIdentity = new PlayerIdentity(
            UUID.fromString("536d10cf-c585-5a3e-9060-f818e26945f6"), "test_player");
        PlayerIdentityResolution first = PlayerIdentityResolution.mapped(LEGACY_A, firstIdentity, "confirmed");
        PlayerIdentityResolution equalButIndependent = PlayerIdentityResolution.mapped(
            UUID.fromString(LEGACY_A.toString()), secondIdentity, new String("confirmed"));
        PlayerIdentityResolution differentDecision = PlayerIdentityResolution.mapped(
            LEGACY_A, secondIdentity, "corrected");

        assertEquals(first, equalButIndependent);
        assertEquals(first.hashCode(), equalButIndependent.hashCode());
        assertNotEquals(first, differentDecision);
        assertEquals("PlayerIdentityResolution{legacyUuid=" + LEGACY_A
            + ", identity=test_player (536d10cf-c585-5a3e-9060-f818e26945f6)"
            + ", source=ADMIN_EXPLICIT_LEGACY_MAPPING, decision='confirmed'}", first.toString());
    }

    @Test
    void dataObjectsRejectBlankDecisionsAndInvalidNormalizedNames() {
        assertThrows(IllegalArgumentException.class, () -> service.mapLegacy(LEGACY_A, "Alice", "   "));
        assertThrows(IllegalArgumentException.class,
            () -> new PlayerIdentity(UUID.randomUUID(), "Not_Normalized"));
    }
}
