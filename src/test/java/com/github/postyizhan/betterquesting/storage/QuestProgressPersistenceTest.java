package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestProgressPersistenceTest {
    private static final UUID QUEST = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @TempDir
    Path dataDirectory;

    private QuestDatabase quests;
    private QuestProgressPersistence persistence;

    @BeforeEach
    void setUp() {
        quests = new QuestDatabase();
        quests.createNew(QUEST);
        persistence = new QuestProgressPersistence(
            quests,
            new DirectoryWorldStorage(dataDirectory));
    }

    @Test
    void playerFileRoundTripsWithExactUpstreamRootAndUuidPath() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1700000000000L);
        persistence.savePlayer(ALICE);
        persistence.clearProgress();

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertEquals(List.of(ALICE), report.loadedPlayers());
        assertTrue(Files.exists(dataDirectory.resolve("QuestProgress/" + ALICE + ".json")));
        String document = Files.readString(dataDirectory.resolve("QuestProgress/" + ALICE + ".json"));
        assertTrue(document.contains("\"questProgress:9\""));
        assertFalse(document.contains("\"format:8\""));
        assertTrue(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void perPlayerRoundTripOmitsUnownedQuestsAndRetainsAllOwnersAcrossFiles() throws IOException {
        UUID secondQuest = UUID.fromString("00000000-0000-0000-0000-000000000102");
        quests.createNew(secondQuest);
        quests.get(QUEST).setProperty(NativeProps.GLOBAL, true);
        quests.get(QUEST).setComplete(ALICE, 11L);
        quests.get(QUEST).setComplete(BOB, 12L);
        quests.get(secondQuest).setComplete(BOB, 13L);

        persistence.savePlayer(ALICE);
        persistence.savePlayer(BOB);

        NBTTagCompound aliceRoot = new com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore(
            new DirectoryWorldStorage(dataDirectory)).load(QuestProgressPersistence.pathFor(ALICE), true).root();
        assertEquals(1, NbtCompat.getListOrEmpty(aliceRoot, "questProgress").tagCount());

        persistence.clearProgress();
        QuestProgressPersistence.LoadReport report = persistence.load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
        assertTrue(quests.get(QUEST).isComplete(UUID.randomUUID()));
        assertFalse(quests.get(secondQuest).isComplete(ALICE));
        assertTrue(quests.get(secondQuest).isComplete(BOB));
    }

    @Test
    void emptyCompletedQuestEntryIsBlockedAndNeverSaved() throws IOException {
        String document = documentWithQuestRecord("\"questIDHigh:4\":0,\"questIDLow:4\":257,"
            + "\"completed:9\":{},\"tasks:9\":{}");
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("completion record")));
        assertEquals(document, Files.readString(playerPath(ALICE), StandardCharsets.UTF_8));
        assertFalse(Files.exists(playerPath(ALICE).resolveSibling("malformed_" + ALICE + ".json.json")));

        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound emptyQuest = com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType.QUEST
            .writeId(QUEST);
        emptyQuest.setTag("completed", new NBTTagList());
        emptyQuest.setTag("tasks", new NBTTagList());
        NBTTagList entries = new NBTTagList();
        entries.appendTag(emptyQuest);
        root.setTag("questProgress", entries);
        assertThrows(IOException.class, () -> persistence.savePlayer(ALICE, root));
    }

    @Test
    void missingDirectoryClearsPreviousWorldProgress() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1L);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.ABSENT, report.status());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void completeUsersOnlyFixtureIsBlockedWithoutQuarantineOrMutation() throws IOException {
        UUID fixtureQuest = new UUID(100L, 200L);
        UUID fixturePlayer = UUID.fromString("d4f5a6b7-1122-3344-5566-778899aabbcc");
        quests.createNew(fixtureQuest);
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Path target = dataDirectory.resolve(QuestProgressPersistence.pathFor(fixturePlayer));
        byte[] original = Files.readAllBytes(fixture("player-quest-progress.json"));
        Files.write(target, original);
        List<String> initialFiles = fileTree();

        QuestProgressPersistence.LoadReport first = persistence.load();
        QuestProgressPersistence.LoadReport second = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, first.status());
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, second.status());
        assertTrue(first.loadedPlayers().isEmpty());
        assertTrue(first.issues().stream().anyMatch(issue -> issue.contains("task progress")));
        assertFalse(quests.get(fixtureQuest).isComplete(fixturePlayer));
        assertFalse(quests.get(fixtureQuest).isComplete(BOB));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(initialFiles, fileTree());
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken-syntax.json", "truncated-mid-object.json"})
    void malformedAndTruncatedFilesAreQuarantinedWithoutChangingSource(String fixture) throws IOException {
        byte[] original = Files.readAllBytes(malformedFixture(fixture));
        Path target = playerPath(ALICE);
        Files.createDirectories(target.getParent());
        Files.write(target, original);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertTrue(Files.exists(target.resolveSibling("malformed_" + ALICE + ".json.json")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"mitePortFormat:3\":2,\"questProgress:9\":{}}",
        "{\"questProgress:10\":{}}",
        "{\"wrongRoot:9\":{}}"
    })
    void wrongRootsAreQuarantinedBeforeMutation(String document) throws IOException {
        Path target = playerPath(ALICE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertEquals(document, Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void futureCanonicalFormatIsBlockedByteExactWithoutQuarantineAcrossRestarts() throws IOException {
        byte[] original = "{\"mitePortFormat:8\":\"2\",\"questProgress:9\":{}}"
            .getBytes(StandardCharsets.UTF_8);
        Path target = playerPath(ALICE);
        Files.createDirectories(target.getParent());
        Files.write(target, original);

        QuestProgressPersistence.LoadReport first = persistence.load();
        QuestProgressPersistence.LoadReport second = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, first.status());
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, second.status());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(List.of("QuestProgress/" + ALICE + ".json"), fileTree());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void oversizedFileIsRejectedAndPreservedWithoutParsing() throws IOException {
        byte[] original = new byte[(int) QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1];
        Arrays.fill(original, (byte) ' ');
        original[0] = '{';
        Path target = playerPath(ALICE);
        Files.createDirectories(target.getParent());
        Files.write(target, original);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.OVERSIZED, report.status());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("exceeds")));
        assertFalse(Files.exists(target.resolveSibling("malformed_" + ALICE + ".json.json")));
    }

    @Test
    void usernameFilenameIsNeverAutoLinked() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(dataDirectory.resolve("QuestProgress/Alice.json"),
            playerDocument(ALICE), StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("filename")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertTrue(Files.exists(dataDirectory.resolve("QuestProgress/Alice.json")));
    }

    @Test
    void crossFileUuidMismatchIsAllOrNothing() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(dataDirectory.resolve("QuestProgress/" + ALICE + ".json"),
            playerDocument(BOB), StandardCharsets.UTF_8);
        Files.writeString(dataDirectory.resolve("QuestProgress/" + BOB + ".json"),
            playerDocument(BOB), StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("UUID")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
    }

    @Test
    void nonCanonicalDuplicateUuidFilenameIsNeverTreatedAsASecondPlayer() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), playerDocument(ALICE), StandardCharsets.UTF_8);
        Files.writeString(dataDirectory.resolve("QuestProgress/0-0-0-0-201.json"),
            playerDocument(ALICE), StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("filename")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void completeUsersOnlyTaskPayloadIsBlocked() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        String taskOnly = "{\"questProgress:9\":{\"0:10\":{\"questIDHigh:4\":0,"
            + "\"questIDLow:4\":257,\"completed:9\":{},"
            + "\"tasks:9\":{\"0:10\":{\"completeUsers:9\":{\"0:8\":\"" + ALICE
            + "\"}}}}}}";
        Files.writeString(playerPath(ALICE), taskOnly, StandardCharsets.UTF_8);
        List<String> initialFiles = fileTree();

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("task progress")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertEquals(taskOnly, Files.readString(playerPath(ALICE), StandardCharsets.UTF_8));
        assertEquals(initialFiles, fileTree());
    }

    @Test
    void nestedUnrelatedUuidDoesNotBecomePlayerIdentity() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        String document = "{\"questProgress:9\":{\"0:10\":{\"questIDHigh:4\":0,\"questIDLow:4\":257,\"completed:9\":{\"0:10\":"
            + "{\"uuid:8\":\"" + ALICE + "\",\"metadata:10\":{\"uuid:8\":\"" + BOB
            + "\"}}}}}}";
        Files.writeString(playerPath(ALICE), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
    }

    @Test
    void twoOpaqueTaskFilesAreRejectedWithoutMutationOrOverwrite() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        String alice = opaqueTaskDocument(ALICE, "alice-placeholder");
        String bob = opaqueTaskDocument(BOB, "bob-placeholder");
        Files.writeString(playerPath(ALICE), alice, StandardCharsets.UTF_8);
        Files.writeString(playerPath(BOB), bob, StandardCharsets.UTF_8);

        List<String> initialFiles = fileTree();
        QuestProgressPersistence.LoadReport first = persistence.load();
        QuestProgressPersistence.LoadReport second = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, first.status());
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, second.status());
        assertTrue(first.loadedPlayers().isEmpty());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
        assertEquals(alice, Files.readString(playerPath(ALICE), StandardCharsets.UTF_8));
        assertEquals(bob, Files.readString(playerPath(BOB), StandardCharsets.UTF_8));
        assertEquals(initialFiles, fileTree());
    }

    @Test
    void saveRejectsOpaqueTaskProgressBeforeReplacingExistingFile() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1L);
        persistence.savePlayer(ALICE);
        byte[] original = Files.readAllBytes(playerPath(ALICE));
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("questProgress", quests.writeProgressToNBT(new NBTTagList(), List.of(ALICE)));
        ((NBTTagCompound) ((NBTTagList) root.getTag("questProgress")).tagAt(0))
            .setTag("tasks", opaqueTaskList("placeholder"));

        assertThrows(IOException.class, () -> persistence.savePlayer(ALICE, root));
        assertArrayEquals(original, Files.readAllBytes(playerPath(ALICE)));
    }

    @Test
    void saveRejectsCompleteUsersOnlyTaskProgressBeforeReplacingExistingFile() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1L);
        persistence.savePlayer(ALICE);
        byte[] original = Files.readAllBytes(playerPath(ALICE));
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("questProgress", quests.writeProgressToNBT(new NBTTagList(), List.of(ALICE)));
        NBTTagCompound quest = (NBTTagCompound) ((NBTTagList) root.getTag("questProgress")).tagAt(0);
        NBTTagCompound task = new NBTTagCompound();
        NBTTagList completeUsers = new NBTTagList();
        completeUsers.appendTag(new net.minecraft.NBTTagString(ALICE.toString()));
        task.setTag("completeUsers", completeUsers);
        NBTTagList tasks = new NBTTagList();
        tasks.appendTag(task);
        quest.setTag("tasks", tasks);

        assertThrows(IOException.class, () -> persistence.savePlayer(ALICE, root));
        assertArrayEquals(original, Files.readAllBytes(playerPath(ALICE)));
    }

    @Test
    void generatedSnapshotAndSaveRefuseAnyTaskProgress() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1L);
        persistence.savePlayer(ALICE);
        byte[] original = Files.readAllBytes(playerPath(ALICE));
        quests.get(QUEST).getTasks().add(0, new TaskPlaceholder());

        assertThrows(IllegalStateException.class, () -> persistence.snapshotPlayer(ALICE));
        assertThrows(IOException.class, () -> persistence.savePlayer(ALICE));
        assertArrayEquals(original, Files.readAllBytes(playerPath(ALICE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"completed:9\":{}",
        "\"questIDHigh:4\":0,\"completed:9\":{}",
        "\"questIDHigh:3\":0,\"questIDLow:3\":257,\"completed:9\":{}",
        "\"questIDHigh:8\":\"0\",\"questIDLow:4\":257,\"completed:9\":{}",
        "\"questID:8\":\"257\",\"completed:9\":{}",
        "\"questID:3\":-1,\"completed:9\":{}"
    })
    void invalidQuestIdentityIsQuarantinedWithoutChangingCanonicalBytes(String questRecord)
        throws IOException {
        String document = documentWithQuestRecord(questRecord);
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("quest ID")));
        assertEquals(document, Files.readString(playerPath(ALICE), StandardCharsets.UTF_8));
        assertEquals(List.of(
            "QuestProgress/" + ALICE + ".json",
            "QuestProgress/malformed_" + ALICE + ".json.json"), fileTree());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void supportedLegacyNumericQuestIdLoadsUsingQuestDatabaseMapping() throws IOException {
        int legacyId = 257;
        assertEquals(QUEST, UuidConverter.convertLegacyId(legacyId));
        String document = documentWithQuestRecord("\"questID:3\":" + legacyId
            + ",\"completed:9\":{\"0:10\":{\"uuid:8\":\"" + ALICE + "\"}}");
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status(), report.issues().toString());
        assertEquals(List.of(ALICE), report.loadedPlayers());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "4", "6"})
    void wrongNumericLegacyQuestIdTypeIsQuarantinedWithoutReaderCrash(String type) throws IOException {
        String document = documentWithQuestRecord("\"questID:" + type + "\":257,"
            + "\"completed:9\":{\"0:10\":{\"uuid:8\":\"" + ALICE + "\"}}");
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), document, StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("legacy quest ID")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"questIDHigh:4\":0,\"questIDLow:4\":258",
        "\"questID:3\":258"
    })
    void unknownUsableQuestIdIsBlockedWithoutQuarantine(String questIdentity) throws IOException {
        String document = documentWithQuestRecord(questIdentity
            + ",\"completed:9\":{\"0:10\":{\"uuid:8\":\"" + ALICE + "\"}}");
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), document, StandardCharsets.UTF_8);
        List<String> initialFiles = fileTree();

        QuestProgressPersistence.LoadReport first = persistence.load();
        QuestProgressPersistence.LoadReport second = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, first.status());
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, second.status());
        assertTrue(first.issues().stream().anyMatch(issue -> issue.contains("unknown quest ID")));
        assertTrue(first.loadedPlayers().isEmpty());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertEquals(document, Files.readString(playerPath(ALICE), StandardCharsets.UTF_8));
        assertEquals(initialFiles, fileTree());
    }

    @Test
    void invalidEmbeddedUuidIsReportedRatherThanSilentlySkipped() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE),
            playerDocumentValue("not-a-uuid"), StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("not a UUID")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void oneInvalidFilePreventsEveryOtherwiseValidMerge() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.writeString(playerPath(ALICE), playerDocument(ALICE), StandardCharsets.UTF_8);
        Files.writeString(playerPath(BOB), "{\"wrongRoot:9\":{}}", StandardCharsets.UTF_8);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, report.status());
        assertTrue(report.loadedPlayers().isEmpty());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
    }

    @Test
    void writesAreDeterministicAcrossInsertionOrder() throws IOException {
        UUID firstQuest = UUID.fromString("00000000-0000-0000-0000-000000000501");
        UUID secondQuest = UUID.fromString("00000000-0000-0000-0000-000000000502");
        Path secondDirectory = dataDirectory.resolveSibling(dataDirectory.getFileName() + "-deterministic");
        QuestDatabase first = new QuestDatabase();
        first.createNew(secondQuest).setComplete(ALICE, 2L);
        first.createNew(firstQuest).setComplete(ALICE, 1L);
        new QuestProgressPersistence(first, new DirectoryWorldStorage(dataDirectory)).savePlayer(ALICE);

        QuestDatabase second = new QuestDatabase();
        second.createNew(firstQuest).setComplete(ALICE, 1L);
        second.createNew(secondQuest).setComplete(ALICE, 2L);
        new QuestProgressPersistence(second, new DirectoryWorldStorage(secondDirectory)).savePlayer(ALICE);

        assertArrayEquals(
            Files.readAllBytes(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))),
            Files.readAllBytes(secondDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
    }

    @Test
    void readbackFailureLeavesPreviousPlayerFileAndNoTemporary() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1L);
        persistence.savePlayer(ALICE);
        Path target = playerPath(ALICE);
        byte[] original = Files.readAllBytes(target);
        QuestProgressPersistence corrupting = new QuestProgressPersistence(
            quests, new CorruptingWriteStorage(dataDirectory));
        quests.get(QUEST).setClaimed(ALICE, 2L);

        assertThrows(IOException.class, () -> corrupting.savePlayer(ALICE));

        assertArrayEquals(original, Files.readAllBytes(target));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));
    }

    @Test
    void deletionUsesOnlyTheExplicitUuidPath() throws IOException {
        quests.get(QUEST).setComplete(ALICE, 1L);
        persistence.savePlayer(ALICE);

        assertTrue(persistence.deletePlayer(ALICE));
        assertFalse(persistence.deletePlayer(ALICE));
        assertFalse(Files.exists(playerPath(ALICE)));
    }

    @Test
    void legacyAnalyzerBlocksWithoutChangingSourceBytes() throws IOException {
        byte[] original = Files.readAllBytes(fixture("legacy-quest-progress.json"));
        Files.write(dataDirectory.resolve("QuestProgress.json"), original);

        QuestProgressPersistence.LegacyMigrationReport report = persistence.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, report.status());
        assertTrue(report.sourcePreserved());
        assertTrue(report.backupPath().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestProgress.json")));
        assertTrue(report.discoveredUuids().containsAll(List.of(
            UUID.fromString("d4f5a6b7-1122-3344-5566-778899aabbcc"),
            UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00"))));
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void completionOnlyLegacyProgressMigratesLoadsAndPreservesExactSourceBackup() throws IOException {
        byte[] original = completionOnlyLegacyDocument().getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve("QuestProgress.json"), original);

        QuestProgressPersistence.LoadReport report = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertEquals(List.of(ALICE, BOB), report.loadedPlayers());
        QuestProgressPersistence.LegacyMigrationReport migration = report.legacyMigration().orElseThrow();
        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED, migration.status());
        Path backup = migration.backupPath().orElseThrow();
        assertTrue(migration.sourcePreserved());
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestProgress.json")));
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
        assertEquals(11L, quests.get(QUEST).getCompletionInfo(ALICE).getLong("timestamp"));
        assertEquals("alice-extra", quests.get(QUEST).getCompletionInfo(ALICE).getString("opaque"));
        String alice = Files.readString(playerPath(ALICE), StandardCharsets.UTF_8);
        String bob = Files.readString(playerPath(BOB), StandardCharsets.UTF_8);
        assertTrue(alice.contains(ALICE.toString()));
        assertFalse(alice.contains(BOB.toString()));
        assertTrue(alice.contains("alice-extra"));
        assertTrue(bob.contains(BOB.toString()));
        assertFalse(bob.contains(ALICE.toString()));
    }

    @Test
    void legacyMigrationRefusesToOverwriteExistingSplitProgress() throws IOException {
        byte[] legacy = completionOnlyLegacyDocument().getBytes(StandardCharsets.UTF_8);
        byte[] existing = playerDocument(ALICE).getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve("QuestProgress.json"), legacy);
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Files.write(playerPath(ALICE), existing);

        QuestProgressPersistence.LegacyMigrationReport report = persistence.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("already contains")));
        assertArrayEquals(legacy, Files.readAllBytes(dataDirectory.resolve("QuestProgress.json")));
        assertArrayEquals(existing, Files.readAllBytes(playerPath(ALICE)));
        assertTrue(report.backupPath().isEmpty());
        assertEquals(List.of("QuestProgress.json", QuestProgressPersistence.pathFor(ALICE)), fileTree());
    }

    @Test
    void startupAnalyzesLegacyWithoutCreatingBackupOnEveryRestart() throws IOException {
        byte[] original = Files.readAllBytes(fixture("legacy-quest-progress.json"));
        Files.write(dataDirectory.resolve("QuestProgress.json"), original);

        QuestProgressPersistence.LoadReport first = persistence.load();
        QuestProgressPersistence.LoadReport second = persistence.load();

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, first.status());
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, second.status());
        assertTrue(first.legacyMigration().isPresent());
        assertEquals(2, first.legacyMigration().orElseThrow().discoveredUuids().size());
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestProgress.json")));
        try (java.util.stream.Stream<Path> files = Files.list(dataDirectory)) {
            assertEquals(List.of("QuestProgress.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void missingLegacyMigrationIsAReadOnlyAbsentResult() throws IOException {
        QuestProgressPersistence.LegacyMigrationReport report = persistence.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.ABSENT, report.status());
        assertTrue(report.backupPath().isEmpty());
        assertTrue(report.sourcePreserved());
        try (java.util.stream.Stream<Path> files = Files.list(dataDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"broken\":",
        "{\"wrongRoot:9\":{}}",
        "{\"mitePortFormat:3\":2,\"questProgress:9\":{}}"
    })
    void failedLegacyAnalysisQuarantinesAndPreservesExactSource(String original) throws IOException {
        Files.writeString(dataDirectory.resolve("QuestProgress.json"), original, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, report.status());
        assertEquals(original, Files.readString(dataDirectory.resolve("QuestProgress.json")));
        assertTrue(report.backupPath().isEmpty());
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
    }

    @Test
    void repeatedExplicitMigrationOfFutureFormatIsStrictlyReadOnly() throws IOException {
        byte[] original = "{\"mitePortFormat:8\":\"2\",\"questProgress:9\":{}}"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve("QuestProgress.json"), original);

        QuestProgressPersistence.LegacyMigrationReport first = persistence.migrateLegacy();
        QuestProgressPersistence.LegacyMigrationReport second = persistence.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, first.status());
        assertEquals(QuestProgressPersistence.MigrationStatus.BLOCKED, second.status());
        assertTrue(first.backupPath().isEmpty());
        assertTrue(second.backupPath().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestProgress.json")));
        assertEquals(List.of("QuestProgress.json"), fileTree());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void invalidLegacyIdentityIsBackedUpReportedAndNeverConverted() throws IOException {
        String original = "{\"questProgress:9\":{\"0:10\":{"
            + "\"questIDHigh:4\":0,\"questIDLow:4\":257,"
            + "\"completed:9\":{\"0:10\":{\"uuid:8\":\"not-a-uuid\"}},"
            + "\"tasks:9\":{}}}}";
        Files.writeString(dataDirectory.resolve("QuestProgress.json"), original, StandardCharsets.UTF_8);

        QuestProgressPersistence.LegacyMigrationReport report = persistence.migrateLegacy();

        assertEquals(QuestProgressPersistence.MigrationStatus.QUARANTINED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("not a UUID")));
        assertEquals(original, Files.readString(dataDirectory.resolve("QuestProgress.json")));
        assertTrue(report.backupPath().isEmpty());
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
    }

    private String playerDocument(UUID uuid) {
        return playerDocumentValue(uuid.toString());
    }

    private String completionOnlyLegacyDocument() {
        return "{\"questProgress:9\":{\"0:10\":{"
            + "\"questIDHigh:4\":0,\"questIDLow:4\":257,"
            + "\"completed:9\":{"
            + "\"0:10\":{\"uuid:8\":\"" + ALICE + "\",\"timestamp:4\":11,"
            + "\"opaque:8\":\"alice-extra\"},"
            + "\"1:10\":{\"uuid:8\":\"" + BOB + "\",\"timestamp:4\":12}},"
            + "\"tasks:9\":{}}}}";
    }

    private String playerDocumentValue(String uuid) {
        return "{\n"
            + "\t\"questProgress:9\": {\n"
            + "\t\t\"0:10\": {\n"
            + "\t\t\t\"questIDHigh:4\": 0,\n"
            + "\t\t\t\"questIDLow:4\": 257,\n"
            + "\t\t\t\"completed:9\": {\n"
            + "\t\t\t\t\"0:10\": {\"uuid:8\": \"" + uuid + "\"}\n"
            + "\t\t\t}\n"
            + "\t\t}\n"
            + "\t}\n"
            + "}\n";
    }

    private String opaqueTaskDocument(UUID uuid, String marker) {
        return "{\"questProgress:9\":{\"0:10\":{\"questIDHigh:4\":0,\"questIDLow:4\":257,"
            + "\"completed:9\":{\"0:10\":{\"uuid:8\":\""
            + uuid + "\"}},\"tasks:9\":{\"0:10\":{\"taskID:8\":\"betterquesting:placeholder\","
            + "\"opaque:8\":\"" + marker + "\"}}}}}";
    }

    private String documentWithQuestRecord(String questRecord) {
        return "{\"questProgress:9\":{\"0:10\":{" + questRecord + "}}}";
    }

    private List<String> fileTree() throws IOException {
        if (!Files.exists(dataDirectory)) return List.of();
        try (java.util.stream.Stream<Path> paths = Files.walk(dataDirectory)) {
            return paths.filter(Files::isRegularFile)
                .map(dataDirectory::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    private NBTTagList opaqueTaskList(String marker) {
        NBTTagList tasks = new NBTTagList();
        NBTTagCompound task = new NBTTagCompound();
        task.setString("taskID", "betterquesting:placeholder");
        task.setString("opaque", marker);
        tasks.appendTag(task);
        return tasks;
    }

    private Path fixture(String name) {
        try {
            return Path.of(getClass().getResource("/fixtures/database/" + name).toURI());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Path malformedFixture(String name) {
        try {
            return Path.of(getClass().getResource("/fixtures/malformed/" + name).toURI());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Path playerPath(UUID uuid) {
        return dataDirectory.resolve(QuestProgressPersistence.pathFor(uuid));
    }

    private static final class CorruptingWriteStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;

        private CorruptingWriteStorage(Path directory) {
            delegate = new DirectoryWorldStorage(directory);
        }

        @Override public boolean isAvailable() { return delegate.isAvailable(); }
        @Override public Optional<Path> getDataDirectory() { return delegate.getDataDirectory(); }
        @Override public Optional<String> getDisabledReason() { return delegate.getDisabledReason(); }
        @Override public boolean exists(String path) throws IOException { return delegate.exists(path); }
        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException { return delegate.read(path, reader); }
        @Override public List<String> readLines(String path) throws IOException { return delegate.readLines(path); }
        @Override public List<String> list(String path, String suffix) throws IOException { return delegate.list(path, suffix); }
        @Override public boolean delete(String path) throws IOException { return delegate.delete(path); }
        @Override public void appendLine(String path, String line) throws IOException { delegate.appendLine(path, line); }
        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException { delegate.writeAtomically(path, writer); }
        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator)
            throws IOException {
            delegate.writeAtomically(path, output -> output.write("{\"broken\":"
                .getBytes(StandardCharsets.UTF_8)), validator);
        }
        @Override public Optional<Path> backup(String path) throws IOException { return delegate.backup(path); }
        @Override public void flush() throws IOException { delegate.flush(); }
    }

}
