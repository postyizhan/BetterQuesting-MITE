package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
import com.github.postyizhan.betterquesting.api.registry.IFactoryData;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestInstance;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.github.postyizhan.betterquesting.questing.tasks.TaskRegistry;
import com.github.postyizhan.betterquesting.storage.QuestDatabasePersistence;
import com.github.postyizhan.betterquesting.storage.migration.MigrationReport;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnresolvedTaskMigrationReportTest {
    private static final UUID QUEST = new UUID(200L, 300L);

    @TempDir
    Path dataDirectory;

    @Test
    void productionLoadPreservesOpaqueTaskAndPersistsDeduplicatedUnresolvedFactoryReport()
        throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), questDocument(),
            StandardCharsets.UTF_8);
        Files.writeString(dataDirectory.resolve(MigrationReport.PATH), existingReport(),
            StandardCharsets.UTF_8);
        QuestDatabase quests = new QuestDatabase();
        QuestLineDatabase lines = new QuestLineDatabase();
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests, lines, "test-build");

        lifecycle.onServerStarted();

        QuestInstance quest = assertInstanceOf(QuestInstance.class, quests.get(QUEST));
        TaskPlaceholder placeholder = assertInstanceOf(TaskPlaceholder.class, quest.getTasks().getValue(7));
        NBTTagCompound original = placeholder.getTaskConfigData();
        assertEquals("bq_standard:retrieval", original.getString("taskID"));
        assertEquals("missingmod:crystal",
            ((NBTTagCompound) original.getTagList("requiredItems").tagAt(0)).getString("id"));

        JsonObject report = JsonDocuments.parseObject(Files.readString(
            dataDirectory.resolve(MigrationReport.PATH), StandardCharsets.UTF_8));
        assertEquals("keep-me", report.get("operatorNote:8").getAsString());
        assertEquals("future-data", report.getAsJsonObject("issues:9")
            .getAsJsonObject("0:10").get("extension:8").getAsString());
        JsonObject issue = report.getAsJsonObject("issues:9").getAsJsonObject("1:10");
        assertEquals("unresolved_task_factory", issue.get("kind:8").getAsString());
        assertEquals(QUEST.toString(), issue.get("quest:8").getAsString());
        assertEquals(7, issue.get("taskIndex:3").getAsInt());
        assertEquals("bq_standard:retrieval", issue.get("factory:8").getAsString());

        byte[] firstReport = Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH));
        QuestDatabaseLifecycle second = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), new QuestDatabase(), new QuestLineDatabase(), "test-build");
        second.onServerStarted();
        assertArrayEquals(firstReport, Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH)));
        assertTrue(second.lastMigrationReport().isPresent());
        assertEquals(MigrationReport.Status.UNCHANGED, second.lastMigrationReport().orElseThrow().status());
    }

    @Test
    void futureMigrationReportIsPreservedAndBlocksOnlyNewReportEntries() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), questDocument(),
            StandardCharsets.UTF_8);
        byte[] future = existingReport().replace("\"1\"", "\"2\"")
            .getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve(MigrationReport.PATH), future);
        QuestDatabase quests = new QuestDatabase();
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests, new QuestLineDatabase(), "test-build");

        lifecycle.onServerStarted();

        assertTrue(quests.isEmpty());
        assertArrayEquals(future, Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH)));
        MigrationReport.Update update = lifecycle.lastMigrationReport().orElseThrow();
        assertEquals(MigrationReport.Status.BLOCKED, update.status());
        assertTrue(update.quarantinePath().isPresent());
        assertTrue(Files.exists(dataDirectory.resolve(update.quarantinePath().orElseThrow())));
    }

    @Test
    void reportIoFailureDoesNotLeaveLoadedQuestsActive() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), questDocument(),
            StandardCharsets.UTF_8);
        Files.createDirectory(dataDirectory.resolve(MigrationReport.PATH));
        QuestDatabase quests = new QuestDatabase();
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests, new QuestLineDatabase(), "test-build");

        assertThrows(IOException.class, lifecycle::onServerStarted);
        assertTrue(quests.isEmpty());
    }

    @Test
    void invalidExistingReportIsValidatedEvenWhenNoPlaceholderWasLoaded() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), emptyQuestDocument(),
            StandardCharsets.UTF_8);
        byte[] original = "{\"mitePortFormat:8\":\"1\",\"issues:9\":[]}".getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve(MigrationReport.PATH), original);
        QuestDatabase quests = new QuestDatabase();
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests, new QuestLineDatabase(), "test-build");

        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
            lifecycle.onServerStarted());
        assertTrue(quests.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH)));
        lifecycle.onWorldSave();
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH)));
    }

    @Test
    void malformedReportWithPlaceholderFailsClosedAndSuppressesLaterDatabaseWrites() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), questDocument(),
            StandardCharsets.UTF_8);
        byte[] original = "{\"mitePortFormat:8\":\"1\",\"issues:9\":{\"0:10\":{}}}".getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve(MigrationReport.PATH), original);
        QuestDatabase quests = new QuestDatabase();
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests, new QuestLineDatabase(), "test-build");

        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
            lifecycle.onServerStarted());
        assertTrue(quests.isEmpty());
        lifecycle.onWorldSave();
        lifecycle.onServerStopping();
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH)));
        assertArrayEquals(questDocument().getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(dataDirectory.resolve(QuestDatabasePersistence.PATH)));
    }

    @Test
    void negativeTaskIndexReportIsQuarantinedWithoutOverwritingSource() throws IOException {
        assertReportIsQuarantinedWithoutOverwritingSource(
            ("{\"format:8\":\"3.1.0\",\"build:8\":\"old-build\",\"mitePortFormat:8\":\"1\","
                + "\"issues:9\":{\"0:10\":{\"kind:8\":\"unresolved_task_factory\",\"quest:8\":\""
                + QUEST + "\",\"taskIndex:3\":-1,\"factory:8\":\"missing:task\"}}}")
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deeplyNestedReportIsQuarantinedWithoutOverwritingSource() throws IOException {
        assertReportIsQuarantinedWithoutOverwritingSource(deepReport(140).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void oversizedReportIsQuarantinedWithoutOverwritingSource() throws IOException {
        assertReportIsQuarantinedWithoutOverwritingSource(oversizedReport());
    }

    @Test
    void registeredFactoryThatCannotConstructIsNotReported() throws IOException {
        ResourceKey id = ResourceKey.parse("test:construction-failure-report");
        if (TaskRegistry.INSTANCE.getFactory(id) == null) TaskRegistry.INSTANCE.register(new FailingTaskFactory(id));
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), questDocument(id.toString()),
            StandardCharsets.UTF_8);
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), new QuestDatabase(), new QuestLineDatabase(), "test-build");

        lifecycle.onServerStarted();

        assertTrue(lifecycle.lastMigrationReport().isEmpty());
        assertTrue(Files.notExists(dataDirectory.resolve(MigrationReport.PATH)));
    }

    @Test
    void reportWriteFailureCanRetrySameLifecycleWithoutLeavingDatabaseBound() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), questDocument(),
            StandardCharsets.UTF_8);
        FailingReportStorage storage = new FailingReportStorage(dataDirectory, 1);
        QuestDatabase quests = new QuestDatabase();
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            storage, quests, new QuestLineDatabase(), "test-build");

        assertThrows(IOException.class, lifecycle::onServerStarted);
        assertTrue(quests.isEmpty());
        assertTrue(lifecycle.isWritesDisabled());
        lifecycle.onServerStarted();
        assertTrue(quests.containsKey(QUEST));
        assertTrue(Files.exists(dataDirectory.resolve(MigrationReport.PATH)));
    }

    private static String questDocument() {
        return questDocument("bq_standard:retrieval");
    }

    private static String questDocument(String factory) {
        return "{\"format:8\":\"3.1.0\",\"build:8\":\"upstream\",\"questDatabase:9\":{"
            + "\"0:10\":{\"questIDHigh:4\":200,\"questIDLow:4\":300,\"properties:10\":{},"
            + "\"preRequisites:9\":{},\"rewards:9\":{},\"tasks:9\":{\"0:10\":{"
            + "\"taskID:8\":\"" + factory + "\",\"index:3\":7,\"requiredItems:9\":{"
            + "\"0:10\":{\"id:8\":\"missingmod:crystal\",\"Count:3\":1}}}}}},"
            + "\"questLines:9\":{}}";
    }

    private static String emptyQuestDocument() {
        return "{\"format:8\":\"3.1.0\",\"build:8\":\"upstream\",\"questDatabase:9\":{},\"questLines:9\":{}}";
    }

    private static String existingReport() {
        return "{\"format:8\":\"3.1.0\",\"build:8\":\"old-build\",\"mitePortFormat:8\":\"1\","
            + "\"operatorNote:8\":\"keep-me\",\"issues:9\":{\"0:10\":{"
            + "\"kind:3\":12,\"extension:8\":\"future-data\"}}}";
    }

    private static String deepReport(int depth) {
        StringBuilder report = new StringBuilder("{\"mitePortFormat:8\":\"1\",\"issues:9\":{");
        for (int index = 0; index < depth; index++) report.append("\"x:10\":{");
        report.append("\"value:8\":\"x\"");
        for (int index = 0; index < depth; index++) report.append('}');
        return report.append('}').toString();
    }

    private static byte[] oversizedReport() {
        byte[] oversized = new byte[MigrationReport.MAX_DOCUMENT_BYTES + 1];
        Arrays.fill(oversized, (byte) 'x');
        return oversized;
    }

    private void assertReportIsQuarantinedWithoutOverwritingSource(byte[] original) throws IOException {
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), emptyQuestDocument(),
            StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve(MigrationReport.PATH), original);
        QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), new QuestDatabase(), new QuestLineDatabase(), "test-build");

        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
            lifecycle.onServerStarted());
        assertEquals(MigrationReport.Status.BLOCKED,
            lifecycle.lastMigrationReport().orElseThrow().status());
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(MigrationReport.PATH)));
    }

    private static final class FailingTaskFactory implements IFactoryData<ITask, NBTTagCompound> {
        private final ResourceKey id;
        private FailingTaskFactory(ResourceKey id) { this.id = id; }
        @Override public ResourceKey getRegistryName() { return id; }
        @Override public ITask createNew() { throw new IllegalStateException("construction failure"); }
        @Override public ITask loadFromData(NBTTagCompound data) { throw new IllegalStateException("construction failure"); }
    }

    private static final class FailingReportStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private int failures;
        private FailingReportStorage(Path path, int failures) {
            delegate = new DirectoryWorldStorage(path);
            this.failures = failures;
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
        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator) throws IOException {
            if (path.equals(MigrationReport.PATH) && failures-- > 0) throw new IOException("injected report write failure");
            delegate.writeAtomically(path, writer, validator);
        }
        @Override public Optional<Path> backup(String path) throws IOException { return delegate.backup(path); }
        @Override public void flush() throws IOException { delegate.flush(); }
    }
}
