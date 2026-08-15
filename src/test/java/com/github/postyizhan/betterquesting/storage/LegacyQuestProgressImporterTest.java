package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyQuestProgressImporterTest {
    private static final UUID QUEST = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SECOND_QUEST = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID CHARLIE = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @TempDir
    Path dataDirectory;

    private QuestDatabase quests;

    @BeforeEach
    void setUp() {
        quests = new QuestDatabase();
        quests.createNew(QUEST);
        quests.createNew(SECOND_QUEST);
    }

    @Test
    void successfulCopyMigrationRetainsSourceAndExactBackupAndLoadsSplitOutputs() throws IOException {
        byte[] original = validDocument().getBytes(StandardCharsets.UTF_8);
        Files.write(source(), original);

        QuestProgressPersistence.LoadReport report = persistence().load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertEquals(List.of(ALICE, BOB), report.loadedPlayers());
        QuestProgressPersistence.LegacyMigrationReport migration = report.legacyMigration().orElseThrow();
        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED, migration.status());
        assertArrayEquals(original, Files.readAllBytes(source()));
        assertArrayEquals(original, Files.readAllBytes(migration.backupPath().orElseThrow()));
        assertTrue(Files.isRegularFile(dataDirectory.resolve(
            LegacyQuestProgressImporter.PREPARED_MARKER_PATH)));
        assertTrue(Files.isRegularFile(dataDirectory.resolve(
            LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)));
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
        assertEquals("owned-extra", quests.get(QUEST).getCompletionInfo(ALICE).getString("opaque"));
        assertTrue(Files.readString(playerPath(ALICE)).contains("owned-extra"));
        assertFalse(Files.readString(playerPath(ALICE)).contains(BOB.toString()));
        assertFalse(Files.readString(playerPath(BOB)).contains(ALICE.toString()));
    }

    @Test
    void completedMigrationRestartIsReadOnlyAndIdempotent() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        QuestProgressPersistence first = persistence();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, first.load().status());
        List<FileSnapshot> before = snapshots();
        first.clearProgress();

        QuestProgressPersistence.LoadReport restarted = persistence().load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, restarted.status());
        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED,
            restarted.legacyMigration().orElseThrow().status());
        assertEquals(before, snapshots());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
    }

    @Test
    void postMigrationChangesRefreshCompleteManifestAndRemainRestartable() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        QuestProgressPersistence active = persistence();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, active.load().status());
        byte[] initialMarker = Files.readAllBytes(
            dataDirectory.resolve(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH));

        quests.get(QUEST).setClaimed(ALICE, 21L);
        active.savePlayer(ALICE);
        quests.get(SECOND_QUEST).setComplete(CHARLIE, 22L);
        active.savePlayer(CHARLIE);
        assertTrue(active.deletePlayer(BOB));

        byte[] refreshedMarker = Files.readAllBytes(
            dataDirectory.resolve(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH));
        assertFalse(java.util.Arrays.equals(initialMarker, refreshedMarker));
        active.clearProgress();
        QuestProgressPersistence.LoadReport restarted = persistence().load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, restarted.status());
        assertTrue(quests.get(QUEST).hasClaimed(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
        assertTrue(quests.get(SECOND_QUEST).isComplete(CHARLIE));
        assertTrue(Files.exists(source()));
    }

    @Test
    void liveSaveNeverBlessesAnUnrelatedOutputDigestMismatch() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        QuestProgressPersistence active = persistence();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, active.load().status());
        byte[] aliceBefore = Files.readAllBytes(playerPath(ALICE));
        byte[] markerBefore = Files.readAllBytes(
            dataDirectory.resolve(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH));
        Files.writeString(playerPath(BOB), "tampered", StandardCharsets.UTF_8);
        quests.get(QUEST).setClaimed(ALICE, 23L);

        assertThrows(IOException.class, () -> active.savePlayer(ALICE));

        assertArrayEquals(aliceBefore, Files.readAllBytes(playerPath(ALICE)));
        assertArrayEquals(markerBefore, Files.readAllBytes(
            dataDirectory.resolve(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)));
    }

    @ParameterizedTest(name = "rejects unsafe legacy shape: {0}")
    @MethodSource("unsafeDocuments")
    void rejectsEveryUnsafeShapeBeforeNbtConversion(String name, String document,
        QuestProgressPersistence.MigrationStatus expected) throws IOException {
        byte[] original = document.getBytes(StandardCharsets.UTF_8);
        Files.write(source(), original);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(expected, report.status(), name + ": " + report.issues());
        assertArrayEquals(original, Files.readAllBytes(source()));
        assertFalse(Files.exists(dataDirectory.resolve(LegacyQuestProgressImporter.BACKUP_PATH)));
        assertFalse(Files.exists(dataDirectory.resolve(LegacyQuestProgressImporter.PREPARED_MARKER_PATH)));
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
    }

    @ParameterizedTest(name = "rejects negative zero before conversion: {0}")
    @MethodSource("negativeZeroDocuments")
    void rejectsNegativeZeroAtEveryAcceptedIntegerLocation(String name, String document)
        throws IOException {
        byte[] original = document.getBytes(StandardCharsets.UTF_8);
        Files.write(source(), original);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, report.status(),
            name + ": " + report.issues());
        assertArrayEquals(original, Files.readAllBytes(source()));
        assertFalse(Files.exists(dataDirectory.resolve(LegacyQuestProgressImporter.BACKUP_PATH)));
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
    }

    private static Stream<Arguments> negativeZeroDocuments() {
        String completion = completion(ALICE, "");
        return Stream.of(
            Arguments.of("legacy quest ID", root("{\"0:10\":{\"questID:3\":-0,"
                + "\"completed:9\":{" + completion + "},\"tasks:9\":{}}}")),
            Arguments.of("quest ID high", pairQuestDocument("-0", "257", completion)),
            Arguments.of("quest ID low", pairQuestDocument("0", "-0", completion)),
            Arguments.of("completion byte", integerCompletionDocument(1, "-0")),
            Arguments.of("completion short", integerCompletionDocument(2, "-0")),
            Arguments.of("completion int", integerCompletionDocument(3, "-0")),
            Arguments.of("completion long", integerCompletionDocument(4, "-0")),
            Arguments.of("completion byte array", completionDocument("\"owned:7\":[-0]")),
            Arguments.of("completion int array", completionDocument("\"owned:11\":[-0]"))
        );
    }

    @ParameterizedTest(name = "rejects non-canonical or out-of-range integer: {0}")
    @MethodSource("invalidIntegerLiterals")
    void rejectsNonCanonicalAndOutOfRangeIntegerLiterals(String name, int type, String literal)
        throws IOException {
        String document = integerCompletionDocument(type, literal);
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, report.status(),
            name + ": " + report.issues());
        assertFalse(Files.exists(dataDirectory.resolve(LegacyQuestProgressImporter.BACKUP_PATH)));
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
    }

    private static Stream<Arguments> invalidIntegerLiterals() {
        return Stream.of(
            Arguments.of("explicit plus", 4, "+0"),
            Arguments.of("leading zero", 4, "01"),
            Arguments.of("negative leading zero", 4, "-01"),
            Arguments.of("decimal", 4, "0.0"),
            Arguments.of("exponent", 4, "0e0"),
            Arguments.of("negative-zero exponent", 4, "-0E+0"),
            Arguments.of("byte underflow", 1, "-129"),
            Arguments.of("byte overflow", 1, "128"),
            Arguments.of("short underflow", 2, "-32769"),
            Arguments.of("short overflow", 2, "32768"),
            Arguments.of("int underflow", 3, "-2147483649"),
            Arguments.of("int overflow", 3, "2147483648"),
            Arguments.of("long underflow", 4, "-9223372036854775809"),
            Arguments.of("long overflow", 4, "9223372036854775808")
        );
    }

    @Test
    void acceptsCanonicalIntegerZeroAndExactTypeBoundaries() throws IOException {
        String document = completionDocument(String.join(",",
            "\"zero:4\":0",
            "\"byteMin:1\":-128", "\"byteMax:1\":127",
            "\"shortMin:2\":-32768", "\"shortMax:2\":32767",
            "\"intMin:3\":-2147483648", "\"intMax:3\":2147483647",
            "\"longMin:4\":-9223372036854775808", "\"longMax:4\":9223372036854775807",
            "\"bytes:7\":[-128,127]", "\"ints:11\":[-2147483648,2147483647]"));
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED, report.status(),
            report.issues().toString());
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
    }

    private static Stream<Arguments> unsafeDocuments() {
        String validQuest = questRecord(QUEST, completion(ALICE, "\"timestamp:4\":11"));
        String duplicateQuest = list(validQuest, validQuest);
        String duplicateUuid = list(completion(ALICE, "\"timestamp:4\":11"),
            completion(ALICE, "\"timestamp:4\":12"));
        return Stream.of(
            invalid("duplicate root member",
                "{\"questProgress:9\":{},\"questProgress:9\":{}}"),
            invalid("block comment", "{/*comment*/\"questProgress:9\":{}}"),
            invalid("line comment", "{//comment\n\"questProgress:9\":{}}"),
            invalid("single quoted key", "{'questProgress:9':{}}"),
            invalid("unquoted key", "{questProgress:9:{}}"),
            invalid("trailing comma", "{\"questProgress:9\":{},}"),
            invalid("duplicate nested member", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDHigh:4\":0,\"questIDLow:4\":257,"
                + "\"completed:9\":{" + completion(ALICE, "") + "},\"tasks:9\":{}}}")),
            invalid("typed root conflict",
                "{\"questProgress:9\":{},\"questProgress:10\":{}}"),
            invalid("typed quest id conflict", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDHigh:3\":0,\"questIDLow:4\":257,"
                + "\"completed:9\":{" + completion(ALICE, "") + "},\"tasks:9\":{}}}")),
            invalid("typed completion uuid conflict", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{\"0:10\":{"
                + "\"uuid:8\":\"" + ALICE + "\",\"uuid:3\":1}},\"tasks:9\":{}}}")),
            invalid("long quest high overflow", root("{\"0:10\":{"
                + "\"questIDHigh:4\":9223372036854775808,\"questIDLow:4\":257,"
                + "\"completed:9\":{" + completion(ALICE, "") + "},\"tasks:9\":{}}}")),
            invalid("legacy quest int overflow", root("{\"0:10\":{"
                + "\"questID:3\":2147483648,\"completed:9\":{" + completion(ALICE, "")
                + "},\"tasks:9\":{}}}")),
            invalid("completion long overflow", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{"
                + completion(ALICE, "\"timestamp:4\":9223372036854775808")
                + "},\"tasks:9\":{}}}")),
            invalid("completion byte overflow", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{"
                + completion(ALICE, "\"claimed:1\":128") + "},\"tasks:9\":{}}}")),
            invalid("fractional int", root("{\"0:10\":{"
                + "\"questID:3\":257.0,\"completed:9\":{" + completion(ALICE, "")
                + "},\"tasks:9\":{}}}")),
            invalid("missing completed", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"tasks:9\":{}}}")),
            invalid("missing tasks", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{"
                + completion(ALICE, "") + "}}}")),
            invalid("wrong completed type", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:10\":{},"
                + "\"tasks:9\":{}}}")),
            invalid("wrong tasks type", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{"
                + completion(ALICE, "") + "},\"tasks:10\":{}}}")),
            invalid("dual quest IDs", root("{\"0:10\":{"
                + "\"questID:3\":257,\"questIDHigh:4\":0,\"questIDLow:4\":257,"
                + "\"completed:9\":{" + completion(ALICE, "") + "},\"tasks:9\":{}}}")),
            blocked("root opaque field",
                "{\"opaque:8\":\"ownerless\",\"questProgress:9\":{" + validQuest + "}}"),
            blocked("quest opaque field", root("{\"0:10\":{"
                + "\"opaque:8\":\"ownerless\",\"questIDHigh:4\":0,\"questIDLow:4\":257,"
                + "\"completed:9\":{" + completion(ALICE, "") + "},\"tasks:9\":{}}}")),
            blocked("empty completions", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{},"
                + "\"tasks:9\":{}}}")),
            blocked("non-empty tasks", root("{\"0:10\":{"
                + "\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{"
                + completion(ALICE, "") + "},\"tasks:9\":{\"0:10\":{}}}}")),
            invalid("duplicate quest record", root("{" + duplicateQuest + "}")),
            invalid("duplicate completion UUID", root("{\"0:10\":{"
                + questRecordBody(QUEST, duplicateUuid) + "}}")),
            invalid("case variant UUID", root("{\"0:10\":{"
                + questRecordBody(QUEST, completionValue(
                    "D4f5a6b7-1122-3344-5566-778899aabbcc", "")) + "}}")),
            invalid("mixed valid and invalid", root("{" + list(validQuest,
                questRecord(SECOND_QUEST, completionValue("not-a-uuid", ""))) + "}")),
            blocked("current port format",
                "{\"mitePortFormat:8\":\"1\",\"questProgress:9\":{}}"),
            blocked("future port format",
                "{\"mitePortFormat:8\":\"2\",\"questProgress:9\":{}}")
        );
    }

    @Test
    void invalidUtf8IsRejectedBeforeJsonParsing() throws IOException {
        byte[] prefix = "{\"questProgress:9\":{},\"x:8\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, invalid, 0, prefix.length);
        invalid[prefix.length] = (byte) 0xC3;
        invalid[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, invalid, prefix.length + 2, suffix.length);
        Files.write(source(), invalid);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, report.status());
        assertArrayEquals(invalid, Files.readAllBytes(source()));
        assertFalse(Files.exists(dataDirectory.resolve(LegacyQuestProgressImporter.PREPARED_MARKER_PATH)));
    }

    @Test
    void excessiveDepthIsRejectedBeforeGsonRecursion() throws IOException {
        String nested = "0";
        for (int depth = 0; depth <= LegacyQuestProgressImporter.MAX_STRUCTURE_DEPTH; depth++) {
            nested = "{\"x:10\":" + nested + "}";
        }
        String document = "{\"questProgress:9\":{},\"opaque:10\":" + nested + "}";
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("depth")));
        assertEquals(document, Files.readString(source()));
    }

    @Test
    void exactMaximumStructuralDepthReachesValidationWithoutParserRejection() throws IOException {
        String nested = "0";
        for (int depth = 0; depth < LegacyQuestProgressImporter.MAX_STRUCTURE_DEPTH - 1; depth++) {
            nested = "{\"x:10\":" + nested + "}";
        }
        String document = "{\"questProgress:9\":{},\"opaque:10\":" + nested + "}";
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, report.status());
        assertTrue(report.issues().stream().noneMatch(issue -> issue.contains("depth")));
        assertEquals(document, Files.readString(source()));
    }

    @Test
    void oversizedSourceIsRejectedWithoutArtifacts() throws IOException {
        byte[] oversized = new byte[(int) QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        Files.write(source(), oversized);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.OVERSIZED, report.status());
        assertArrayEquals(oversized, Files.readAllBytes(source()));
        assertEquals(List.of("QuestProgress.json"), tree());
    }

    @Test
    void exactMaximumSourceSizeIsAcceptedAndPreservedByteForByte() throws IOException {
        byte[] prefix = "{\"questProgress:9\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] maximum = new byte[(int) QuestProgressPersistence.MAX_DOCUMENT_BYTES];
        System.arraycopy(prefix, 0, maximum, 0, prefix.length);
        java.util.Arrays.fill(maximum, prefix.length, maximum.length, (byte) ' ');
        Files.write(source(), maximum);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED, report.status());
        assertArrayEquals(maximum, Files.readAllBytes(source()));
        assertArrayEquals(maximum, Files.readAllBytes(report.backupPath().orElseThrow()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2"})
    void anyMitePortFormatBlocksLegacyRecognition(String version) throws IOException {
        String document = "{\"mitePortFormat:8\":\"" + version
            + "\",\"questProgress:9\":{}}";
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, report.status());
        assertEquals(List.of("QuestProgress.json"), tree());
    }

    @ParameterizedTest(name = "pre-existing collision: {0}")
    @MethodSource("collisionPaths")
    void preExistingArtifactsAreNeverOverwritten(String name, String relativePath) throws IOException {
        byte[] source = validDocument().getBytes(StandardCharsets.UTF_8);
        byte[] collision = "do-not-overwrite".getBytes(StandardCharsets.UTF_8);
        Files.write(source(), source);
        Path target = dataDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, collision);

        QuestProgressPersistence.LegacyMigrationReport report = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, report.status(), name);
        assertArrayEquals(source, Files.readAllBytes(source()));
        assertArrayEquals(collision, Files.readAllBytes(target));
    }

    private static Stream<Arguments> collisionPaths() {
        return Stream.of(
            Arguments.of("backup", LegacyQuestProgressImporter.BACKUP_PATH),
            Arguments.of("prepared marker", LegacyQuestProgressImporter.PREPARED_MARKER_PATH),
            Arguments.of("complete marker", LegacyQuestProgressImporter.COMPLETE_MARKER_PATH),
            Arguments.of("player output", QuestProgressPersistence.pathFor(ALICE))
        );
    }

    @Test
    void preExistingProgressNonDirectoryIsRejected() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        Files.writeString(dataDirectory.resolve("QuestProgress"), "not-a-directory");

        assertThrows(IOException.class, () -> persistence().migrateLegacy());

        assertEquals("not-a-directory", Files.readString(dataDirectory.resolve("QuestProgress")));
    }

    @Test
    void preExistingProgressDirectorySymlinkIsRejected() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        Path outside = Files.createDirectory(dataDirectory.resolveSibling(
            dataDirectory.getFileName() + "-outside"));
        try {
            Files.createSymbolicLink(dataDirectory.resolve("QuestProgress"), outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            Assumptions.abort("symbolic links unavailable: " + unsupported);
        }

        assertThrows(IOException.class, () -> persistence().migrateLegacy());
        assertEquals(List.of(), Files.list(outside).toList());
    }

    @Test
    void preExistingPlayerOutputSymlinkIsRejected() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Path outside = dataDirectory.resolveSibling(dataDirectory.getFileName() + "-outside.json");
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(playerPath(ALICE), outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            Assumptions.abort("symbolic links unavailable: " + unsupported);
        }

        assertThrows(IOException.class, () -> persistence().migrateLegacy());
        assertEquals("outside", Files.readString(outside));
    }

    @ParameterizedTest(name = "ordinary I/O failure recovers: {0}")
    @MethodSource("publicationTargets")
    void ordinaryPublicationFailureRollsBackOrLeavesRecoverablePreparedState(
        String name, Predicate<Path> failingPath) throws IOException {
        byte[] original = validDocument().getBytes(StandardCharsets.UTF_8);
        Files.write(source(), original);
        LegacyQuestProgressImporter.MigrationIo failing =
            new ThrowOnceIo(failingPath, FailureOperation.CREATE);

        assertThrows(IOException.class, () -> persistence(failing).migrateLegacy(), name);

        QuestProgressPersistence.LoadReport recovered = persistence().load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, recovered.status());
        assertArrayEquals(original, Files.readAllBytes(source()));
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
    }

    private static Stream<Arguments> publicationTargets() {
        return Stream.of(
            Arguments.of("prepared marker", named(
                LegacyQuestProgressImporter.PREPARED_MARKER_PATH + ".tmp")),
            Arguments.of("backup", named(LegacyQuestProgressImporter.BACKUP_PATH)),
            Arguments.of("alice output", named(ALICE + ".json")),
            Arguments.of("bob output", named(BOB + ".json")),
            Arguments.of("complete marker", named(
                LegacyQuestProgressImporter.COMPLETE_MARKER_PATH + ".tmp"))
        );
    }

    @ParameterizedTest(name = "atomic marker publication failure recovers: {0}")
    @ValueSource(strings = {
        LegacyQuestProgressImporter.PREPARED_MARKER_PATH,
        LegacyQuestProgressImporter.COMPLETE_MARKER_PATH
    })
    void atomicMarkerPublicationFailureIsRecoverable(String marker) throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> persistence(
            new ThrowOnceIo(named(marker), FailureOperation.PUBLISH)).migrateLegacy());

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
    }

    @ParameterizedTest(name = "file fsync failure recovers: {0}")
    @MethodSource("syncTargets")
    void fileSyncFailureIsRecoverable(String name, Predicate<Path> failingPath) throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);

        assertThrows(IOException.class,
            () -> persistence(new ThrowOnceIo(failingPath, FailureOperation.FILE_SYNC)).migrateLegacy(), name);

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
    }

    private static Stream<Arguments> syncTargets() {
        return Stream.of(
            Arguments.of("prepared marker", named(LegacyQuestProgressImporter.PREPARED_MARKER_PATH + ".tmp")),
            Arguments.of("backup", named(LegacyQuestProgressImporter.BACKUP_PATH)),
            Arguments.of("alice output", named(ALICE + ".json")),
            Arguments.of("bob output", named(BOB + ".json")),
            Arguments.of("complete marker", named(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH + ".tmp"))
        );
    }

    @ParameterizedTest(name = "directory fsync failure {0} is recoverable")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void everyDirectorySyncFailureIsRecoverable(int failingSync) throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);

        assertThrows(IOException.class,
            () -> persistence(new ThrowOnDirectorySyncIo(failingSync)).migrateLegacy());

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
    }

    @ParameterizedTest(name = "mid-file power loss recovers: {0}")
    @ValueSource(strings = {
        LegacyQuestProgressImporter.BACKUP_PATH,
        "00000000-0000-0000-0000-000000000201.json",
        "00000000-0000-0000-0000-000000000202.json",
        LegacyQuestProgressImporter.COMPLETE_MARKER_PATH + ".tmp"
    })
    void restartRepairsEveryPartialArtifactWithPreparedState(String file) throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);

        assertThrows(SimulatedPowerLoss.class,
            () -> persistence(new CrashDuringWriteIo(named(file))).migrateLegacy());

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
    }

    @Test
    void restartFailsClosedAfterPowerLossLeavesOnlyPartialPreparedTemporary() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);

        assertThrows(SimulatedPowerLoss.class, () -> persistence(new CrashDuringWriteIo(
            named(LegacyQuestProgressImporter.PREPARED_MARKER_PATH + ".tmp"))).migrateLegacy());

        QuestProgressPersistence.LoadReport restarted = persistence().load();
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, restarted.status());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void failedCompleteMarkerRefreshFailsClosedUntilOwnedSaveRetriesIt() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        ThrowOnceIo failingIo = new ThrowOnceIo(
            named(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH), FailureOperation.REPLACE);
        QuestProgressPersistence active = persistence(failingIo);
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, active.load().status());
        quests.get(QUEST).setClaimed(ALICE, 31L);

        assertThrows(IOException.class, () -> active.savePlayer(ALICE));
        QuestDatabase blockedQuests = new QuestDatabase();
        blockedQuests.createNew(QUEST);
        blockedQuests.createNew(SECOND_QUEST);
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED,
            new QuestProgressPersistence(blockedQuests,
                new DirectoryWorldStorage(dataDirectory)).load().status());

        active.savePlayer(ALICE);
        active.clearProgress();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).getCompletionInfo(ALICE).getBoolean("claimed"));
    }

    @ParameterizedTest(name = "failed refresh rejects unrelated output {0} before retry")
    @ValueSource(strings = {"modified", "deleted"})
    void failedRefreshRetryNeverBlessesAnUnrelatedOutputChange(String change) throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        ThrowOnceIo failingIo = new ThrowOnceIo(
            named(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH), FailureOperation.REPLACE);
        QuestProgressPersistence active = persistence(failingIo);
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, active.load().status());
        quests.get(QUEST).setClaimed(ALICE, 32L);
        assertThrows(IOException.class, () -> active.savePlayer(ALICE));
        byte[] oldMarker = Files.readAllBytes(
            dataDirectory.resolve(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH));

        if (change.equals("modified")) {
            Files.writeString(playerPath(BOB), "unrelated", StandardCharsets.UTF_8);
        } else {
            Files.delete(playerPath(BOB));
        }

        assertThrows(IOException.class, () -> active.savePlayer(ALICE));
        assertArrayEquals(oldMarker, Files.readAllBytes(
            dataDirectory.resolve(LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)));
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, persistence().load().status());
    }

    @Test
    void generatedPlayerOutputHonorsExactEightMibBoundaryBeforePublication() throws IOException {
        Path probeRoot = dataDirectory.resolve("probe");
        Path probeSource = probeRoot.resolve(QuestProgressPersistence.LEGACY_PATH);
        Files.createDirectories(probeRoot);
        String emptyPayload = completionDocument("\"opaque:8\":\"\"");
        Files.writeString(probeSource, emptyPayload, StandardCharsets.UTF_8);
        QuestDatabase probeQuests = questDatabase();
        QuestProgressPersistence probe = persistence(probeRoot, probeQuests);
        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED,
            probe.migrateLegacy().status());
        long emptyOutputSize = Files.size(probeRoot.resolve(
            QuestProgressPersistence.pathFor(ALICE)));
        int boundaryPayloadSize = Math.toIntExact(
            QuestProgressPersistence.MAX_DOCUMENT_BYTES - emptyOutputSize);

        Path boundaryRoot = dataDirectory.resolve("boundary");
        Files.createDirectories(boundaryRoot);
        String boundaryDocument = completionDocument(
            "\"opaque:8\":\"" + "a".repeat(boundaryPayloadSize) + "\"");
        assertTrue(boundaryDocument.getBytes(StandardCharsets.UTF_8).length
            < QuestProgressPersistence.MAX_DOCUMENT_BYTES);
        Files.writeString(boundaryRoot.resolve(QuestProgressPersistence.LEGACY_PATH),
            boundaryDocument, StandardCharsets.UTF_8);
        QuestProgressPersistence boundary = persistence(boundaryRoot, questDatabase());

        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED,
            boundary.migrateLegacy().status());
        assertEquals(QuestProgressPersistence.MAX_DOCUMENT_BYTES,
            Files.size(boundaryRoot.resolve(QuestProgressPersistence.pathFor(ALICE))));

        Path oversizedRoot = dataDirectory.resolve("oversized-output");
        Files.createDirectories(oversizedRoot);
        String oversizedDocument = completionDocument(
            "\"opaque:8\":\"" + "a".repeat(boundaryPayloadSize + 1) + "\"");
        assertTrue(oversizedDocument.getBytes(StandardCharsets.UTF_8).length
            < QuestProgressPersistence.MAX_DOCUMENT_BYTES);
        Files.writeString(oversizedRoot.resolve(QuestProgressPersistence.LEGACY_PATH),
            oversizedDocument, StandardCharsets.UTF_8);
        QuestProgressPersistence oversized = persistence(oversizedRoot, questDatabase());

        QuestProgressPersistence.LegacyMigrationReport rejected = oversized.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, rejected.status(),
            rejected.issues().toString());
        assertTrue(rejected.issues().stream().anyMatch(issue -> issue.contains("player output")));
        try (Stream<Path> paths = Files.walk(oversizedRoot)) {
            assertEquals(List.of(QuestProgressPersistence.LEGACY_PATH), paths
                .filter(path -> !path.equals(oversizedRoot))
                .map(oversizedRoot::relativize)
                .map(Path::toString)
                .sorted()
                .toList());
        }
    }

    @Test
    void completeMarkerRollbackDeleteFailurePreservesRecoverableDependencies() throws IOException {
        byte[] original = validDocument().getBytes(StandardCharsets.UTF_8);
        Files.write(source(), original);

        IOException failure = assertThrows(IOException.class,
            () -> persistence(new CompleteMarkerRollbackDeleteFailingIo()).migrateLegacy());

        assertTrue(failure.getSuppressed().length > 0);
        assertArrayEquals(original, Files.readAllBytes(source()));
        assertTrue(Files.exists(dataDirectory.resolve(LegacyQuestProgressImporter.BACKUP_PATH)));
        assertTrue(Files.exists(playerPath(ALICE)));
        assertTrue(Files.exists(playerPath(BOB)));
        assertTrue(Files.exists(dataDirectory.resolve(
            LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)));

        QuestProgressPersistence.LoadReport restarted = persistence().load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, restarted.status(),
            restarted.issues().toString());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
        assertEquals("owned-extra", quests.get(QUEST).getCompletionInfo(ALICE).getString("opaque"));
    }

    @Test
    void legacyNumericQuestIdIsRewrittenToCurrentUuidSchemaAndLoads() throws IOException {
        String document = root("{\"0:10\":{\"questID:3\":257,\"completed:9\":{"
            + completion(ALICE, "\"opaque:8\":\"retained\"") + "},\"tasks:9\":{}}}");
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport migrated = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED, migrated.status(),
            migrated.issues().toString());
        String output = Files.readString(playerPath(ALICE));
        assertFalse(output.contains("\"questID:3\""));
        assertTrue(output.contains("\"questIDHigh:4\": 0"));
        assertTrue(output.contains("\"questIDLow:4\": 257"));
        assertTrue(output.contains("retained"));

        quests.get(QUEST).resetUser(null, true);
        QuestProgressPersistence.LoadReport loaded = persistence().load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, loaded.status(),
            loaded.issues().toString());
        assertEquals("retained", quests.get(QUEST).getCompletionInfo(ALICE).getString("opaque"));
    }

    @ParameterizedTest(name = "rejects escaped lone surrogate: {0}")
    @ValueSource(strings = {"D800", "DC00"})
    void escapedLoneSurrogatesAreRejectedBeforeConversion(String surrogate) throws IOException {
        String document = completionDocument("\"opaque:8\":\"\\u" + surrogate + "\"");
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport rejected = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, rejected.status(),
            rejected.issues().toString());
        assertEquals(List.of(QuestProgressPersistence.LEGACY_PATH), tree());
    }

    @Test
    void surrogateReplacementCannotCollapseDistinctMemberNames() throws IOException {
        String document = completionDocument(
            "\"opaque\\uD800:8\":\"first\",\"opaque?:8\":\"second\"");
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport rejected = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, rejected.status(),
            rejected.issues().toString());
        assertEquals(List.of(QuestProgressPersistence.LEGACY_PATH), tree());
    }

    @Test
    void validSurrogatePairsInNamesAndValuesArePreserved() throws IOException {
        String document = completionDocument(
            "\"owned\\uD83D\\uDE00:8\":\"value\\uD83D\\uDE00\"");
        Files.writeString(source(), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport migrated = persistence().migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED, migrated.status(),
            migrated.issues().toString());
        quests.get(QUEST).resetUser(null, true);
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
        assertEquals("value\uD83D\uDE00",
            quests.get(QUEST).getCompletionInfo(ALICE).getString("owned\uD83D\uDE00"));
    }

    @Test
    void rollbackFailureIsSuppressedAndRestartRecoversFromPreparedMarker() throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        RollbackFailingIo failing = new RollbackFailingIo();

        IOException failure = assertThrows(IOException.class,
            () -> persistence(failing).migrateLegacy());

        assertTrue(failure.getSuppressed().length > 0);
        assertTrue(Files.exists(dataDirectory.resolve(
            LegacyQuestProgressImporter.PREPARED_MARKER_PATH)));
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
    }

    @ParameterizedTest(name = "restart after durable state: {0}")
    @MethodSource("restartCheckpoints")
    void restartRecoversAfterEveryDurablePartialState(String name,
        LegacyQuestProgressImporter.Checkpoint checkpoint, int occurrence) throws IOException {
        byte[] original = validDocument().getBytes(StandardCharsets.UTF_8);
        Files.write(source(), original);
        CrashAfterCheckpointIo crashing = new CrashAfterCheckpointIo(checkpoint, occurrence);

        assertThrows(SimulatedPowerLoss.class, () -> persistence(crashing).migrateLegacy(), name);

        QuestProgressPersistence.LoadReport recovered = persistence().load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, recovered.status());
        assertArrayEquals(original, Files.readAllBytes(source()));
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
    }

    private static Stream<Arguments> restartCheckpoints() {
        return Stream.of(
            Arguments.of("prepared", LegacyQuestProgressImporter.Checkpoint.PREPARED, 1),
            Arguments.of("backup", LegacyQuestProgressImporter.Checkpoint.BACKUP, 1),
            Arguments.of("first output", LegacyQuestProgressImporter.Checkpoint.OUTPUT, 1),
            Arguments.of("second output", LegacyQuestProgressImporter.Checkpoint.OUTPUT, 2),
            Arguments.of("complete", LegacyQuestProgressImporter.Checkpoint.COMPLETE, 1)
        );
    }

    @ParameterizedTest(name = "completed digest mismatch: {0}")
    @MethodSource("completedArtifacts")
    void completedMarkerOnlySuppressesSourceWhenEveryDigestStillMatches(
        String name, String relativePath) throws IOException {
        Files.writeString(source(), validDocument(), StandardCharsets.UTF_8);
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, persistence().load().status());
        quests.get(QUEST).resetUser(null, true);
        Files.writeString(dataDirectory.resolve(relativePath), "tampered", StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport restarted = persistence().load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, restarted.status(), name);
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
    }

    private static Stream<Arguments> completedArtifacts() {
        return Stream.of(
            Arguments.of("source", QuestProgressPersistence.LEGACY_PATH),
            Arguments.of("backup", LegacyQuestProgressImporter.BACKUP_PATH),
            Arguments.of("alice output", QuestProgressPersistence.pathFor(ALICE)),
            Arguments.of("bob output", QuestProgressPersistence.pathFor(BOB)),
            Arguments.of("prepared marker", LegacyQuestProgressImporter.PREPARED_MARKER_PATH),
            Arguments.of("complete marker", LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)
        );
    }

    private QuestProgressPersistence persistence() {
        return new QuestProgressPersistence(quests, new DirectoryWorldStorage(dataDirectory));
    }

    private QuestProgressPersistence persistence(LegacyQuestProgressImporter.MigrationIo io) {
        return new QuestProgressPersistence(quests, new DirectoryWorldStorage(dataDirectory), io);
    }

    private static QuestProgressPersistence persistence(Path root, QuestDatabase database) {
        return new QuestProgressPersistence(database, new DirectoryWorldStorage(root));
    }

    private static QuestDatabase questDatabase() {
        QuestDatabase database = new QuestDatabase();
        database.createNew(QUEST);
        database.createNew(SECOND_QUEST);
        return database;
    }

    private Path source() {
        return dataDirectory.resolve(QuestProgressPersistence.LEGACY_PATH);
    }

    private Path playerPath(UUID uuid) {
        return dataDirectory.resolve(QuestProgressPersistence.pathFor(uuid));
    }

    private List<String> tree() throws IOException {
        try (Stream<Path> paths = Files.walk(dataDirectory)) {
            return paths.filter(path -> !path.equals(dataDirectory))
                .map(dataDirectory::relativize)
                .map(Path::toString)
                .sorted()
                .toList();
        }
    }

    private List<FileSnapshot> snapshots() throws IOException {
        List<FileSnapshot> snapshots = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dataDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                snapshots.add(new FileSnapshot(dataDirectory.relativize(path).toString(),
                    Files.readAllBytes(path)));
            }
        }
        return snapshots;
    }

    private String validDocument() {
        return root("{\"0:10\":{" + questRecordBody(QUEST,
            list(completion(ALICE, "\"timestamp:4\":11,\"claimed:1\":0,"
                + "\"opaque:8\":\"owned-extra\""),
                completion(BOB, "\"timestamp:4\":12,\"claimed:1\":false"))) + "}}");
    }

    private static String root(String listObject) {
        return "{\"questProgress:9\":" + listObject + "}";
    }

    private static String questRecord(UUID quest, String completions) {
        return "\"0:10\":{" + questRecordBody(quest, completions) + "}";
    }

    private static String questRecordBody(UUID quest, String completions) {
        return "\"questIDHigh:4\":" + quest.getMostSignificantBits()
            + ",\"questIDLow:4\":" + quest.getLeastSignificantBits()
            + ",\"completed:9\":{" + completions + "},\"tasks:9\":{}";
    }

    private static String pairQuestDocument(String high, String low, String completions) {
        return root("{\"0:10\":{\"questIDHigh:4\":" + high + ",\"questIDLow:4\":" + low
            + ",\"completed:9\":{" + completions + "},\"tasks:9\":{}}}");
    }

    private static String integerCompletionDocument(int type, String literal) {
        return completionDocument("\"owned:" + type + "\":" + literal);
    }

    private static String completionDocument(String fields) {
        return root("{\"0:10\":{" + questRecordBody(QUEST, completion(ALICE, fields)) + "}}");
    }

    private static String completion(UUID uuid, String fields) {
        return completionValue(uuid.toString(), fields);
    }

    private static String completionValue(String uuid, String fields) {
        return "\"0:10\":{\"uuid:8\":\"" + uuid + "\""
            + (fields.isEmpty() ? "" : "," + fields) + "}";
    }

    private static String list(String... entries) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < entries.length; index++) {
            if (index > 0) result.append(',');
            String entry = entries[index];
            int colon = entry.indexOf(':');
            result.append('\"').append(index).append(entry.substring(colon));
        }
        return result.toString();
    }

    private static Arguments invalid(String name, String document) {
        return Arguments.of(name, document, QuestProgressPersistence.MigrationStatus.QUARANTINED);
    }

    private static Arguments blocked(String name, String document) {
        return Arguments.of(name, document, QuestProgressPersistence.MigrationStatus.BLOCKED);
    }

    private static Predicate<Path> named(String name) {
        return path -> path.getFileName().toString().equals(name);
    }

    private record FileSnapshot(String path, byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof FileSnapshot that && path.equals(that.path)
                && java.util.Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * path.hashCode() + java.util.Arrays.hashCode(bytes);
        }
    }

    private enum FailureOperation { CREATE, FILE_SYNC, DIRECTORY_SYNC, PUBLISH, REPLACE }

    private static class DelegatingIo implements LegacyQuestProgressImporter.MigrationIo {
        final LegacyQuestProgressImporter.MigrationIo delegate =
            new LegacyQuestProgressImporter.NioMigrationIo();

        @Override public FileChannel createNew(Path path) throws IOException {
            return delegate.createNew(path);
        }

        @Override public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            return delegate.write(channel, bytes);
        }

        @Override public void syncFile(Path path, FileChannel channel) throws IOException {
            delegate.syncFile(path, channel);
        }

        @Override public void syncDirectory(Path directory) throws IOException {
            delegate.syncDirectory(directory);
        }

        @Override public void publishMarker(Path temporary, Path marker) throws IOException {
            delegate.publishMarker(temporary, marker);
        }

        @Override public void replaceMarker(Path temporary, Path marker) throws IOException {
            delegate.replaceMarker(temporary, marker);
        }

        @Override public void deleteIfExists(Path path) throws IOException {
            delegate.deleteIfExists(path);
        }

        @Override public void checkpoint(LegacyQuestProgressImporter.Checkpoint checkpoint, Path path) {
        }
    }

    private static final class ThrowOnceIo extends DelegatingIo {
        private final Predicate<Path> target;
        private final FailureOperation operation;
        private boolean failed;

        private ThrowOnceIo(Predicate<Path> target, FailureOperation operation) {
            this.target = target;
            this.operation = operation;
        }

        @Override public FileChannel createNew(Path path) throws IOException {
            fail(path, FailureOperation.CREATE);
            return super.createNew(path);
        }

        @Override public void syncFile(Path path, FileChannel channel) throws IOException {
            fail(path, FailureOperation.FILE_SYNC);
            super.syncFile(path, channel);
        }

        @Override public void syncDirectory(Path directory) throws IOException {
            fail(directory, FailureOperation.DIRECTORY_SYNC);
            super.syncDirectory(directory);
        }

        @Override public void publishMarker(Path temporary, Path marker) throws IOException {
            fail(marker, FailureOperation.PUBLISH);
            super.publishMarker(temporary, marker);
        }

        @Override public void replaceMarker(Path temporary, Path marker) throws IOException {
            fail(marker, FailureOperation.REPLACE);
            super.replaceMarker(temporary, marker);
        }

        private void fail(Path path, FailureOperation candidate) throws IOException {
            if (!failed && operation == candidate && target.test(path)) {
                failed = true;
                throw new IOException("injected " + operation + " failure at " + path);
            }
        }
    }

    private static final class ThrowOnDirectorySyncIo extends DelegatingIo {
        private final int failingSync;
        private int syncs;
        private boolean failed;

        private ThrowOnDirectorySyncIo(int failingSync) {
            this.failingSync = failingSync;
        }

        @Override public void syncDirectory(Path directory) throws IOException {
            if (!failed && ++syncs == failingSync) {
                failed = true;
                throw new IOException("injected directory sync failure " + failingSync);
            }
            super.syncDirectory(directory);
        }
    }

    private static final class CrashDuringWriteIo extends DelegatingIo {
        private final Predicate<Path> target;
        private Path current;
        private boolean crashed;

        private CrashDuringWriteIo(Predicate<Path> target) {
            this.target = target;
        }

        @Override public FileChannel createNew(Path path) throws IOException {
            current = path;
            return super.createNew(path);
        }

        @Override public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            if (!crashed && current != null && target.test(current)) {
                crashed = true;
                int originalLimit = bytes.limit();
                bytes.limit(bytes.position() + Math.max(1, bytes.remaining() / 2));
                int written = super.write(channel, bytes);
                bytes.limit(originalLimit);
                throw new SimulatedPowerLoss();
            }
            return super.write(channel, bytes);
        }
    }

    private static final class RollbackFailingIo extends DelegatingIo {
        private boolean publicationFailed;
        private boolean rollbackFailed;

        @Override public FileChannel createNew(Path path) throws IOException {
            if (!publicationFailed && path.getFileName().toString().equals(BOB + ".json")) {
                publicationFailed = true;
                throw new IOException("injected second output failure");
            }
            return super.createNew(path);
        }

        @Override public void deleteIfExists(Path path) throws IOException {
            if (publicationFailed && !rollbackFailed
                && path.getFileName().toString().equals(ALICE + ".json")) {
                rollbackFailed = true;
                throw new IOException("injected rollback failure");
            }
            super.deleteIfExists(path);
        }
    }

    private static final class CompleteMarkerRollbackDeleteFailingIo extends DelegatingIo {
        private boolean completePublished;
        private boolean publicationSyncFailed;
        private boolean rollbackDeleteFailed;

        @Override public void publishMarker(Path temporary, Path marker) throws IOException {
            super.publishMarker(temporary, marker);
            if (marker.getFileName().toString().equals(
                LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)) {
                completePublished = true;
            }
        }

        @Override public void syncDirectory(Path directory) throws IOException {
            if (completePublished && !publicationSyncFailed) {
                publicationSyncFailed = true;
                throw new IOException("injected sync failure after complete marker publication");
            }
            super.syncDirectory(directory);
        }

        @Override public void deleteIfExists(Path path) throws IOException {
            if (publicationSyncFailed && !rollbackDeleteFailed
                && path.getFileName().toString().equals(
                    LegacyQuestProgressImporter.COMPLETE_MARKER_PATH)) {
                rollbackDeleteFailed = true;
                throw new IOException("injected complete marker rollback delete failure");
            }
            super.deleteIfExists(path);
        }
    }

    private static final class CrashAfterCheckpointIo extends DelegatingIo {
        private final LegacyQuestProgressImporter.Checkpoint target;
        private final int targetOccurrence;
        private int occurrences;

        private CrashAfterCheckpointIo(LegacyQuestProgressImporter.Checkpoint target,
            int targetOccurrence) {
            this.target = target;
            this.targetOccurrence = targetOccurrence;
        }

        @Override public void checkpoint(LegacyQuestProgressImporter.Checkpoint checkpoint, Path path) {
            if (checkpoint == target && ++occurrences == targetOccurrence) {
                throw new SimulatedPowerLoss();
            }
        }
    }

    private static final class SimulatedPowerLoss extends Error {
    }
}
