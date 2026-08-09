package com.github.postyizhan.betterquesting.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyMappingStoreTest {
    private static final UUID LEGACY_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LEGACY_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    // Derived, never hardcoded: a literal UUID here previously paired alice's name with
    // test_player's derived value, so the round-trip tests asserted a mislabelled mapping.
    private static final PlayerIdentity ALICE =
        new DeterministicPlayerIdentityService().resolveUsername("alice").identity().orElseThrow();

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileMeansAFreshWorldRatherThanCorruption() throws IOException {
        assertEquals(Optional.empty(), store().load());
    }

    @Test
    void roundTripsMappingsIncludingManyToOneMerges() throws IOException {
        Map<UUID, PlayerIdentityResolution> mappings = Map.of(
            LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "primary old account"),
            LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_B, ALICE, "admin approved account merge"));
        LegacyMappingStore store = store();

        store.save(mappings);

        assertEquals(mappings, store.load().orElseThrow());
    }

    @Test
    void roundTripsDecisionTextContainingSeparatorAndTerminators() throws IOException {
        String decision = "admin note: a|b, path C:\\saves\\world\nsecond line";
        LegacyMappingStore store = store();

        store.save(Map.of(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, decision)));

        assertEquals(decision, store.load().orElseThrow().get(LEGACY_A).decision());
    }

    @Test
    void savesAnEmptySnapshotAsAHeaderOnlyFile() throws IOException {
        LegacyMappingStore store = store();

        store.save(Map.of());

        assertEquals(1, Files.readAllLines(mappingFile(), StandardCharsets.UTF_8).size());
        assertEquals(Map.of(), store.load().orElseThrow());
    }

    @Test
    void reportsCorruptionWhenARecordLineIsTampered() throws IOException {
        LegacyMappingStore store = store();
        store.save(Map.of(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "original")));
        Files.writeString(mappingFile(),
            Files.readString(mappingFile(), StandardCharsets.UTF_8).replace("original", "tampered"),
            StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, store::load);

        assertEquals(1, corrupt.rejections().size());
        assertTrue(corrupt.rejections().get(0).reason().contains("checksum mismatch"));
    }

    @Test
    void reportsCorruptionWhenARecordLineIsDeleted() throws IOException {
        LegacyMappingStore store = store();
        store.save(Map.of(
            LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "first"),
            LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_B, ALICE, "second")));
        List<String> lines = Files.readAllLines(mappingFile(), StandardCharsets.UTF_8);
        Files.writeString(mappingFile(), lines.get(0) + "\n" + lines.get(1) + "\n", StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, store::load);

        assertTrue(corrupt.rejections().get(0).reason().contains("header declares 2 records but the file has 1"));
    }

    @Test
    void reportsCorruptionForAnUnterminatedTail() throws IOException {
        LegacyMappingStore store = store();
        store.save(Map.of(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "first")));
        Files.writeString(mappingFile(),
            Files.readString(mappingFile(), StandardCharsets.UTF_8) + "BQIDMAPREC1|partial",
            StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, store::load);

        assertTrue(corrupt.rejections().stream()
            .anyMatch(rejection -> rejection.reason().contains("not terminated by LF")));
    }

    @Test
    void reportsCorruptionForAnEmptyFileInsteadOfTreatingItAsNoMappings() throws IOException {
        Files.createDirectories(mappingFile().getParent());
        Files.write(mappingFile(), new byte[0]);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, () -> store().load());

        assertTrue(corrupt.rejections().get(0).reason().contains("no header line"));
    }

    @Test
    void reportsCorruptionForADuplicateLegacyUuid() throws IOException {
        LegacyMappingStore store = store();
        store.save(Map.of(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "first")));
        List<String> lines = Files.readAllLines(mappingFile(), StandardCharsets.UTF_8);
        String header = IdentityRecordCodec.encode(LegacyMappingStore.HEADER_MAGIC, List.of("2"));
        Files.writeString(mappingFile(), header + "\n" + lines.get(1) + "\n" + lines.get(1) + "\n",
            StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, store::load);

        assertTrue(corrupt.rejections().get(0).reason().contains("duplicate legacy UUID"));
    }

    @Test
    void reportsCorruptionWhenTheHeaderIsReplacedByARecordLine() throws IOException {
        LegacyMappingStore store = store();
        store.save(Map.of(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "first")));
        List<String> lines = Files.readAllLines(mappingFile(), StandardCharsets.UTF_8);
        Files.writeString(mappingFile(), lines.get(1) + "\n" + lines.get(1) + "\n", StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, store::load);

        // A record line has 5 payload fields, so the header's field count check rejects it first.
        assertTrue(corrupt.rejections().get(0).reason().contains("expected 3 fields"));
    }

    @Test
    void reportsCorruptionWhenTheHeaderCarriesAForeignMagic() throws IOException {
        LegacyMappingStore store = store();
        store.save(Map.of(LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "first")));
        List<String> lines = Files.readAllLines(mappingFile(), StandardCharsets.UTF_8);
        Files.writeString(mappingFile(),
            IdentityRecordCodec.encode("BQIDMAP0", List.of("1")) + "\n" + lines.get(1) + "\n",
            StandardCharsets.UTF_8);

        CorruptIdentityMappingException corrupt =
            assertThrows(CorruptIdentityMappingException.class, store::load);

        assertTrue(corrupt.rejections().get(0).reason().contains("expected magic BQIDMAP1"));
    }

    @Test
    void writesRecordsSortedByLegacyUuidSoTheFileIsByteStable() throws IOException {
        LegacyMappingStore store = store();
        Map<UUID, PlayerIdentityResolution> mappings = Map.of(
            LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_B, ALICE, "second"),
            LEGACY_A, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "first"));

        store.save(mappings);
        byte[] first = Files.readAllBytes(mappingFile());
        store.save(mappings);

        assertEquals(new String(first, StandardCharsets.UTF_8),
            Files.readString(mappingFile(), StandardCharsets.UTF_8));
        List<String> lines = Files.readAllLines(mappingFile(), StandardCharsets.UTF_8);
        assertTrue(lines.get(1).contains(LEGACY_A.toString()));
        assertTrue(lines.get(2).contains(LEGACY_B.toString()));
    }

    @Test
    void refusesToSaveAMappingWhoseKeyDisagreesWithItsResolution() {
        assertThrows(IllegalArgumentException.class, () -> store().save(
            Map.of(LEGACY_B, PlayerIdentityResolution.mapped(LEGACY_A, ALICE, "mismatched key"))));
    }

    private LegacyMappingStore store() {
        return new LegacyMappingStore(storage());
    }

    private WorldStorage storage() {
        return new DirectoryWorldStorage(temporaryDirectory);
    }

    private Path mappingFile() {
        return temporaryDirectory.resolve(LegacyMappingStore.MAPPING_PATH);
    }
}
