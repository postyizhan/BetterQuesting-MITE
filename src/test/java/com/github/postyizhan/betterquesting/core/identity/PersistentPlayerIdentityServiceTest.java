package com.github.postyizhan.betterquesting.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentitySource;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentPlayerIdentityServiceTest {
    private static final UUID LEGACY_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID LEGACY_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant FIXED_TIME = Instant.parse("2025-06-07T08:09:10.123Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void mappingsSurviveARestart() throws IOException {
        PersistentPlayerIdentityService first = service();
        first.loadFromStorage();
        first.mapLegacy(LEGACY_A, "Alice", "admin confirmed from server backup");
        first.mergeLegacy(LEGACY_B, "alice", "admin approved account merge");

        PersistentPlayerIdentityService restarted = service();
        restarted.loadFromStorage();

        assertEquals(2, restarted.legacyMappingsSnapshot().size());
        PlayerIdentityResolution restored = restarted.resolveLegacy(LEGACY_A);
        assertEquals(restarted.resolveUsername("alice").identity(), restored.identity());
        assertEquals("admin confirmed from server backup", restored.decision());
        assertEquals(PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING, restored.source());
        assertEquals("admin approved account merge", restarted.resolveLegacy(LEGACY_B).decision());
    }

    @Test
    void removalSurvivesARestart() throws IOException {
        PersistentPlayerIdentityService first = service();
        first.loadFromStorage();
        first.mapLegacy(LEGACY_A, "Alice", "initial");
        first.removeLegacyMapping(LEGACY_A, "admin isolated the save again");

        PersistentPlayerIdentityService restarted = service();
        restarted.loadFromStorage();

        assertFalse(restarted.resolveLegacy(LEGACY_A).resolved());
        assertEquals(PlayerIdentitySource.LEGACY_UUID_UNMAPPED, restarted.resolveLegacy(LEGACY_A).source());
    }

    @Test
    void replacementSurvivesARestart() throws IOException {
        PersistentPlayerIdentityService first = service();
        first.loadFromStorage();
        first.mapLegacy(LEGACY_A, "Alice", "initial");
        first.replaceLegacyMapping(LEGACY_A, "Bob", "admin corrected mapping");

        PersistentPlayerIdentityService restarted = service();
        restarted.loadFromStorage();

        assertEquals(restarted.resolveUsername("bob").identity(), restarted.resolveLegacy(LEGACY_A).identity());
        assertEquals("admin corrected mapping", restarted.resolveLegacy(LEGACY_A).decision());
    }

    @Test
    void eachAdministratorOperationProducesOneAuditEntryRecordingSourceAndDecision() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "mapped from backup");
        service.mergeLegacy(LEGACY_B, "Alice", "merged duplicate account");
        service.replaceLegacyMapping(LEGACY_B, "Bob", "replaced after correction");
        service.removeLegacyMapping(LEGACY_B, "removed at owner request");

        IdentityAuditReport report = service.auditLog().read();

        assertFalse(report.hasRejections());
        assertEquals(List.of(IdentityAuditOperation.MAP, IdentityAuditOperation.MERGE,
                IdentityAuditOperation.REPLACE, IdentityAuditOperation.REMOVE),
            report.records().stream().map(IdentityAuditRecord::operation).toList());
        assertEquals(List.of("mapped from backup", "merged duplicate account",
                "replaced after correction", "removed at owner request"),
            report.records().stream().map(record -> record.resolution().decision()).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L),
            report.records().stream().map(IdentityAuditRecord::sequence).toList());
        for (IdentityAuditRecord record : report.records()) {
            assertEquals(PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING, record.resolution().source());
            assertEquals(FIXED_TIME.toEpochMilli(), record.epochMilli());
            assertTrue(record.resolution().legacyUuid().isPresent());
            assertTrue(record.resolution().identity().isPresent());
        }
    }

    @Test
    void removalWithoutAMatchingMappingProducesNoAuditEntry() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();

        assertTrue(service.removeLegacyMapping(LEGACY_A, "nothing to remove").isEmpty());
        assertEquals(List.of(), service.auditLog().read().records());
    }

    @Test
    void auditSequenceContinuesAfterARestart() throws IOException {
        PersistentPlayerIdentityService first = service();
        first.loadFromStorage();
        first.mapLegacy(LEGACY_A, "Alice", "first session");

        PersistentPlayerIdentityService restarted = service();
        restarted.loadFromStorage();
        restarted.mapLegacy(LEGACY_B, "Bob", "second session");

        assertEquals(List.of(1L, 2L),
            restarted.auditLog().read().records().stream().map(IdentityAuditRecord::sequence).toList());
    }

    @Test
    void rejectsCrashFragmentLineWithoutDiscardingValidRecords() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "before the crash");
        appendRaw(IdentityAuditLog.AUDIT_PATH, "BQIDAUDIT1|2|17|MAP|par");
        service.mapLegacy(LEGACY_B, "Bob", "after the crash");

        IdentityAuditReport report = service.auditLog().read();

        assertEquals(List.of("before the crash", "after the crash"),
            report.records().stream().map(record -> record.resolution().decision()).toList());
        assertEquals(1, report.rejections().size());
        assertEquals(2, report.rejections().get(0).lineNumber());
        assertTrue(report.rejections().get(0).reason().contains("fields"));
    }

    @Test
    void rejectsUnterminatedTrailingFragmentInsteadOfDiscardingItSilently() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "complete record");
        Path audit = temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH);
        Files.write(audit, "BQIDAUDIT1|2|17|MAP".getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.APPEND);

        IdentityAuditReport report = service.auditLog().read();

        assertEquals(1, report.records().size());
        assertEquals(1, report.rejections().size());
        assertTrue(report.rejections().get(0).reason().contains("not terminated by LF"));
    }

    @Test
    void aCompleteRecordMissingOnlyItsLfDoesNotLetTheNextAppendReuseItsSequence() throws IOException {
        // Simulates power loss after the record bytes reached disk but before the LF did. The line is
        // checksum-valid, so it passes every per-line check and is rejected only for the missing LF.
        PersistentPlayerIdentityService first = service();
        first.loadFromStorage();
        first.mapLegacy(LEGACY_A, "Alice", "survived the crash");
        Path audit = temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH);
        String complete = Files.readString(audit, StandardCharsets.UTF_8);
        String secondRecord = IdentityRecordCodec.encode(IdentityAuditRecord.MAGIC, List.of(
            "2", "17", "MAP", LEGACY_B.toString(),
            derivedIdentity("bob").id().toString(), "bob",
            PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING.name(), "lost its LF"));
        Files.writeString(audit, complete + secondRecord, StandardCharsets.UTF_8);

        PersistentPlayerIdentityService restarted = service();
        restarted.loadFromStorage();
        restarted.mapLegacy(LEGACY_B, "Bob", "written after the crash");

        // Sequence 2 belongs to the fragment, so the new record must take 3. If it reused 2, the
        // appended LF would terminate the fragment and this record would be rejected forever.
        IdentityAuditReport report = restarted.auditLog().read();
        List<Long> sequences = report.records().stream().map(IdentityAuditRecord::sequence).toList();
        assertEquals(List.of(1L, 2L, 3L), sequences);
        assertEquals(List.of("survived the crash", "lost its LF", "written after the crash"),
            report.records().stream().map(record -> record.resolution().decision()).toList());
        assertFalse(report.hasRejections());
    }

    @Test
    void rejectsTruncatedAuditLine() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "complete record");
        Path audit = temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH);
        String complete = Files.readString(audit, StandardCharsets.UTF_8).strip();
        Files.writeString(audit, complete.substring(0, complete.length() - 12) + "\n", StandardCharsets.UTF_8);

        IdentityAuditReport report = service.auditLog().read();

        assertEquals(List.of(), report.records());
        assertEquals(1, report.rejections().size());
    }

    @Test
    void rejectsTamperedAuditChecksum() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "original decision");
        Path audit = temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH);
        Files.writeString(audit,
            Files.readString(audit, StandardCharsets.UTF_8).replace("original decision", "forged decision"),
            StandardCharsets.UTF_8);

        IdentityAuditReport report = service.auditLog().read();

        assertEquals(List.of(), report.records());
        assertTrue(report.rejections().get(0).reason().contains("checksum mismatch"));
    }

    @Test
    void rejectsAuditRecordClaimingANonAdministratorSource() throws IOException {
        appendRaw(IdentityAuditLog.AUDIT_PATH, IdentityRecordCodec.encode(IdentityAuditRecord.MAGIC, List.of(
            "1", "17", "MAP", LEGACY_A.toString(),
            // Alice's real derived UUID, so this test isolates the source check instead of also
            // tripping the identity derivation check.
            derivedIdentity("alice").id().toString(), "alice",
            PlayerIdentitySource.MITE_USERNAME_DERIVED.name(), "forged source")));

        IdentityAuditReport report = new IdentityAuditLog(storage()).read();

        assertEquals(List.of(), report.records());
        assertTrue(report.rejections().get(0).reason().contains("unsupported identity source"));
    }

    @Test
    void rejectsOutOfOrderAuditSequence() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "first");
        service.mapLegacy(LEGACY_B, "Bob", "second");
        Path audit = temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH);
        List<String> lines = Files.readAllLines(audit, StandardCharsets.UTF_8);
        Files.writeString(audit, lines.get(1) + "\n" + lines.get(0) + "\n", StandardCharsets.UTF_8);

        IdentityAuditReport report = new IdentityAuditLog(storage()).read();

        assertEquals(List.of("second"), report.records().stream()
            .map(record -> record.resolution().decision()).toList());
        assertTrue(report.rejections().get(0).reason().contains("does not increase past"));
    }

    private PersistentPlayerIdentityService service() {
        return new PersistentPlayerIdentityService(storage(), Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
    }

    private WorldStorage storage() {
        return new DirectoryWorldStorage(temporaryDirectory);
    }

    private void appendRaw(String relativePath, String line) throws IOException {
        new DirectoryWorldStorage(temporaryDirectory).appendLine(relativePath, line);
    }

    /** Derives an identity instead of hardcoding a UUID, so name and UUID cannot drift apart. */
    private static com.github.postyizhan.betterquesting.platform.api.PlayerIdentity derivedIdentity(
        String username) {
        return new DeterministicPlayerIdentityService().resolveUsername(username).identity().orElseThrow();
    }

    @Test
    void snapshotAndAuditUseSeparateFilesUnderTheIdentityDirectory() throws IOException {
        PersistentPlayerIdentityService service = service();
        service.loadFromStorage();
        service.mapLegacy(LEGACY_A, "Alice", "path check");

        assertTrue(Files.exists(temporaryDirectory.resolve(LegacyMappingStore.MAPPING_PATH)));
        assertTrue(Files.exists(temporaryDirectory.resolve(IdentityAuditLog.AUDIT_PATH)));
    }
}
