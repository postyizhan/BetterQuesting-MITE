package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.platform.fml.QuestLootLifecycle;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestLootPersistenceTest {
    private static final String SOURCE_NAME = QuestLootPersistence.PATH;
    private static final Instant FIXED_TIME = Instant.parse("2025-06-07T08:09:10.123Z");
    private static final String FIXED_STAMP = "20250607-080910-123";

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultProviderRecognizesAndPreservesAllProductionStatuses() throws IOException {
        for (List<String> fixture : List.of(
            List.of("legacy", "{\"groups:9\":{\"opaque\":[1,2,3]}}"),
            List.of("current", "{\"mitePortFormat:8\":\"1\",\"futureShape\":[]}"),
            List.of("future", "{\"mitePortFormat:8\":\"2147483648\"}"))) {
            Path world = newWorld(fixture.get(0));
            byte[] original = fixture.get(1).getBytes(StandardCharsets.UTF_8);
            writeSource(world, original);

            QuestLootPersistence.AnalysisResult result =
                new QuestLootPersistence(world).analyze();

            assertEquals(QuestLootPersistence.Status.BLOCKED, result.status(), fixture.get(0));
            Path backup = result.backupPath().orElseThrow();
            assertTrue(backup.getFileName().toString().endsWith(".recognized.bak"));
            assertArrayEquals(original, Files.readAllBytes(backup));
            assertArrayEquals(original, sourceBytes(world));
            assertEquals(2, fileNames(world).size());
        }

        Path corruptWorld = newWorld("corrupt");
        byte[] corrupt = "{\"groups:9\":".getBytes(StandardCharsets.UTF_8);
        writeSource(corruptWorld, corrupt);
        QuestLootPersistence.AnalysisResult corruptResult =
            new QuestLootPersistence(corruptWorld).analyze();
        assertEquals(QuestLootPersistence.Status.QUARANTINED, corruptResult.status());
        Path evidence = corruptResult.evidencePath().orElseThrow();
        assertTrue(evidence.getFileName().toString().endsWith(".corrupt.evidence"));
        assertArrayEquals(corrupt, Files.readAllBytes(evidence));
        assertArrayEquals(corrupt, sourceBytes(corruptWorld));

        Path oversizedWorld = newWorld("oversized");
        byte[] oversized = new byte[(int) QuestLootPersistence.MAX_DOCUMENT_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        oversized[0] = '{';
        writeSource(oversizedWorld, oversized);
        QuestLootPersistence.AnalysisResult oversizedResult =
            new QuestLootPersistence(oversizedWorld).analyze();
        assertEquals(QuestLootPersistence.Status.OVERSIZED, oversizedResult.status());
        assertTrue(oversizedResult.artifactPath().isEmpty());
        assertArrayEquals(oversized, sourceBytes(oversizedWorld));
        assertEquals(List.of(SOURCE_NAME), fileNames(oversizedWorld));
    }

    @Test
    void absentSourceIsReportedWithoutCreatingFiles() throws IOException {
        Path world = newWorld("absent");

        QuestLootPersistence.AnalysisResult result = new QuestLootPersistence(world).analyze();

        assertEquals(QuestLootPersistence.Status.ABSENT, result.status());
        assertTrue(result.artifactPath().isEmpty());
        assertEquals(List.of(), fileNames(world));
    }

    @Test
    void exactEightMiBIsAnalyzedAndOneByteMoreIsUntouched() throws IOException {
        Path exactWorld = newWorld("exact-limit");
        byte[] prefix = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] exact = new byte[(int) QuestLootPersistence.MAX_DOCUMENT_BYTES];
        Arrays.fill(exact, (byte) ' ');
        System.arraycopy(prefix, 0, exact, 0, prefix.length);
        writeSource(exactWorld, exact);

        QuestLootPersistence.AnalysisResult exactResult =
            new QuestLootPersistence(exactWorld).analyze();

        assertEquals(QuestLootPersistence.Status.BLOCKED, exactResult.status());
        assertArrayEquals(exact, Files.readAllBytes(exactResult.backupPath().orElseThrow()));

        Path overWorld = newWorld("over-limit");
        byte[] over = Arrays.copyOf(exact, exact.length + 1);
        writeSource(overWorld, over);
        QuestLootPersistence.AnalysisResult overResult =
            new QuestLootPersistence(overWorld).analyze();
        assertEquals(QuestLootPersistence.Status.OVERSIZED, overResult.status());
        assertEquals(List.of(SOURCE_NAME), fileNames(overWorld));
    }

    @Test
    void invalidUtf8IsQuarantinedFromExactCapturedBytes() throws IOException {
        Path world = newWorld("utf8");
        byte[] original = join("{\"groups:9\":{},\"text\":\"".getBytes(StandardCharsets.UTF_8),
            new byte[] {(byte) 0xc3, (byte) 0x28}, "\"}".getBytes(StandardCharsets.UTF_8));
        writeSource(world, original);

        QuestLootPersistence.AnalysisResult result = new QuestLootPersistence(world).analyze();

        assertEquals(QuestLootPersistence.Status.QUARANTINED, result.status());
        assertTrue(result.detail().contains("UTF-8"), result.detail());
        assertArrayEquals(original, Files.readAllBytes(result.evidencePath().orElseThrow()));
        assertArrayEquals(original, sourceBytes(world));
    }

    @Test
    void commentAndUnquotedTokenAttacksCannotHideExcessiveDepth() throws IOException {
        String nested = "[".repeat(256) + "0" + "]".repeat(256);
        String unquoted = "{\"groups:9\":{},deep\":" + "[".repeat(129) + "0"
            + "]".repeat(129) + ",end\":0}";
        assertTrue(JsonDocuments.parseObject(unquoted).get("deep\"").isJsonArray());
        for (List<String> fixture : List.of(
            List.of("comment-depth", "{\"groups:9\":{},/* \" [ */\"deep\":" + nested + "}"),
            List.of("unquoted-depth", unquoted))) {
            Path world = newWorld(fixture.get(0));
            byte[] original = fixture.get(1).getBytes(StandardCharsets.UTF_8);
            writeSource(world, original);

            QuestLootPersistence.AnalysisResult result =
                new QuestLootPersistence(world).analyze();

            assertEquals(QuestLootPersistence.Status.QUARANTINED, result.status());
            assertTrue(result.detail().contains(fixture.get(0).startsWith("comment")
                ? "depth" : "unquoted"), result.detail());
            assertArrayEquals(original, Files.readAllBytes(result.evidencePath().orElseThrow()));
        }
    }

    @Test
    void unicodeWhitespaceUnquotedNameAttacksAreQuarantinedBeforeGsonRecursion()
        throws IOException {
        for (int depth : new int[] {129, 20_000}) {
            Path world = newWorld("unicode-whitespace-depth-" + depth);
            byte[] original = ambiguousUnquotedName(depth).getBytes(StandardCharsets.UTF_8);
            writeSource(world, original);

            QuestLootPersistence.AnalysisResult result =
                new QuestLootPersistence(world).analyze();

            assertEquals(QuestLootPersistence.Status.QUARANTINED, result.status());
            assertTrue(result.detail().contains("unquoted"), result.detail());
            assertArrayEquals(original, Files.readAllBytes(result.evidencePath().orElseThrow()));
            assertArrayEquals(original, sourceBytes(world));
        }
    }

    @Test
    void sourceAndWorldRootLinksPresentAtStartAreRejected() throws IOException {
        Path outside = newWorld("outside");
        byte[] original = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        Path outsideSource = outside.resolve("outside.json");
        Files.write(outsideSource, original);
        Path sourceLinkWorld = newWorld("source-link");
        createSymbolicLinkOrSkip(sourceLinkWorld.resolve(SOURCE_NAME), outsideSource);

        assertThrows(IOException.class,
            () -> new QuestLootPersistence(sourceLinkWorld).analyze());
        assertArrayEquals(original, Files.readAllBytes(outsideSource));
        assertEquals(1, entryCount(sourceLinkWorld));

        Path rootTarget = newWorld("root-target");
        writeSource(rootTarget, original);
        Path rootLink = temporaryDirectory.resolve("root-link");
        createSymbolicLinkOrSkip(rootLink, rootTarget);
        assertThrows(IOException.class, () -> new QuestLootPersistence(rootLink).analyze());
        assertEquals(List.of(SOURCE_NAME), fileNames(rootTarget));
    }

    @Test
    void nonRegularSourceIsRejectedWithoutEvidence() throws IOException {
        Path world = newWorld("directory-source");
        Files.createDirectory(world.resolve(SOURCE_NAME));

        assertThrows(IOException.class, () -> new QuestLootPersistence(world).analyze());

        assertEquals(1, entryCount(world));
    }

    @Test
    void collisionsNeverOverwriteExistingArtifactsAndKeepStatusSuffixes() throws IOException {
        for (List<String> fixture : List.of(
            List.of("recognized-collision", "{\"groups:9\":{}}", ".recognized.bak"),
            List.of("corrupt-collision", "{\"groups:9\":", ".corrupt.evidence"))) {
            Path world = newWorld(fixture.get(0));
            byte[] original = fixture.get(1).getBytes(StandardCharsets.UTF_8);
            byte[] prior = ("prior " + fixture.get(0)).getBytes(StandardCharsets.UTF_8);
            writeSource(world, original);
            Path existing = world.resolve(SOURCE_NAME + "." + FIXED_STAMP + fixture.get(2));
            Files.write(existing, prior);

            QuestLootPersistence.AnalysisResult result = fixedPersistence(world).analyze();

            QuestLootPersistence.Status expectedStatus = fixture.get(0).startsWith("recognized")
                ? QuestLootPersistence.Status.BLOCKED
                : QuestLootPersistence.Status.QUARANTINED;
            assertEquals(expectedStatus, result.status());
            Path artifact = result.artifactPath().orElseThrow();
            assertEquals(SOURCE_NAME + "." + FIXED_STAMP + "-1" + fixture.get(2),
                artifact.getFileName().toString());
            assertEquals(expectedStatus == QuestLootPersistence.Status.BLOCKED,
                result.backupPath().isPresent());
            assertEquals(expectedStatus == QuestLootPersistence.Status.QUARANTINED,
                result.evidencePath().isPresent());
            assertArrayEquals(prior, Files.readAllBytes(existing));
            assertArrayEquals(original, Files.readAllBytes(artifact));
            assertArrayEquals(original, sourceBytes(world));
        }
    }

    @Test
    void windowsAtomicMoveFileAlreadyExistsIsAvoidedByDirectFinalPublication()
        throws IOException {
        // Windows rejects the removed atomic move onto a reservation with
        // FileAlreadyExistsException. Recording CREATE_NEW calls proves this path creates only the
        // final name and therefore never reaches that provider operation.
        for (List<String> fixture : List.of(
            List.of("recognized-direct", "{\"groups:9\":{\"opaque\":[1,2,3]}}",
                ".recognized.bak"),
            List.of("corrupt-direct", "{\"groups:9\":", ".corrupt.evidence"))) {
            Path world = newWorld(fixture.get(0));
            byte[] original = fixture.get(1).getBytes(StandardCharsets.UTF_8);
            writeSource(world, original);
            AtomicInteger createCalls = new AtomicInteger();
            QuestLootPersistence.ArtifactAccess directAccess =
                new DelegatingArtifactAccess(realAccess()) {
                    @Override
                    public FileChannel createNew(Path path) throws IOException {
                        createCalls.incrementAndGet();
                        assertEquals(SOURCE_NAME + "." + FIXED_STAMP + fixture.get(2),
                            path.getFileName().toString());
                        return super.createNew(path);
                    }
                };

            QuestLootPersistence.AnalysisResult result = fixedPersistence(world,
                new QuestLootPersistence.NioDurability(), directAccess).analyze();

            assertEquals(fixture.get(0).startsWith("recognized")
                ? QuestLootPersistence.Status.BLOCKED
                : QuestLootPersistence.Status.QUARANTINED, result.status());
            Path artifact = result.artifactPath().orElseThrow();
            assertArrayEquals(original, Files.readAllBytes(artifact));
            assertArrayEquals(original, sourceBytes(world));
            assertEquals(1, createCalls.get());
            assertEquals(2, fileNames(world).size());
        }
    }

    @Test
    void fileAndDirectorySyncFailuresRemoveNewArtifacts() throws IOException {
        for (boolean failFileSync : List.of(true, false)) {
            Path world = newWorld(failFileSync ? "file-sync" : "directory-sync");
            byte[] original = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
            writeSource(world, original);
            QuestLootPersistence.Durability durability = new QuestLootPersistence.Durability() {
                @Override
                public void syncFile(FileChannel channel) throws IOException {
                    if (failFileSync) throw new IOException("injected file sync failure");
                    channel.force(true);
                }

                @Override
                public void syncDirectory(Path directory) throws IOException {
                    if (!failFileSync) throw new IOException("injected directory sync failure");
                }
            };

            IOException failure = assertThrows(IOException.class,
                () -> fixedPersistence(world, durability, realAccess()).analyze());

            assertTrue(failure.getMessage().contains("sync failure"));
            assertEquals(List.of(SOURCE_NAME), fileNames(world));
            assertArrayEquals(original, sourceBytes(world));
        }
    }

    @Test
    void cleanupFaultsAreSuppressedWithoutHidingThePrimaryFailure() throws IOException {
        Path world = newWorld("cleanup-fault");
        byte[] original = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        writeSource(world, original);
        QuestLootPersistence.Durability failingSync = new QuestLootPersistence.Durability() {
            @Override
            public void syncFile(FileChannel channel) throws IOException {
                throw new IOException("injected sync failure");
            }

            @Override
            public void syncDirectory(Path directory) {
            }
        };
        QuestLootPersistence.ArtifactAccess failingCleanup =
            new DelegatingArtifactAccess(realAccess()) {
                @Override
                public void deleteIfExists(Path path) throws IOException {
                    throw new IOException("injected cleanup failure: " + path.getFileName());
                }
            };

        IOException failure = assertThrows(IOException.class,
            () -> fixedPersistence(world, failingSync, failingCleanup).analyze());

        assertEquals("injected sync failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertArrayEquals(original, sourceBytes(world));
    }

    @Test
    void partialDirectWriteFailureCleansTargetBeforeAnExactRetry() throws IOException {
        Path world = newWorld("direct-write-fault");
        byte[] original = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        writeSource(world, original);
        AtomicInteger writeCalls = new AtomicInteger();
        QuestLootPersistence.ArtifactAccess transientWriteFailure =
            new DelegatingArtifactAccess(realAccess()) {
                @Override
                public int write(FileChannel channel, ByteBuffer buffer) throws IOException {
                    if (writeCalls.incrementAndGet() == 1) {
                        ByteBuffer partial = buffer.duplicate();
                        partial.limit(partial.position() + Math.min(5, partial.remaining()));
                        while (partial.hasRemaining()) super.write(channel, partial);
                        throw new IOException("injected partial direct-write failure");
                    }
                    return super.write(channel, buffer);
                }
            };
        QuestLootPersistence persistence = fixedPersistence(world,
            new QuestLootPersistence.NioDurability(), transientWriteFailure);

        IOException failure = assertThrows(IOException.class, persistence::analyze);

        assertEquals("injected partial direct-write failure", failure.getMessage());
        assertEquals(List.of(SOURCE_NAME), fileNames(world));
        assertArrayEquals(original, sourceBytes(world));

        QuestLootPersistence.AnalysisResult retried = persistence.analyze();
        QuestLootPersistence.AnalysisResult cached = persistence.analyze();

        assertSame(retried, cached);
        assertEquals(2, writeCalls.get());
        assertEquals(SOURCE_NAME + "." + FIXED_STAMP + ".recognized.bak",
            retried.backupPath().orElseThrow().getFileName().toString());
        assertArrayEquals(original, Files.readAllBytes(retried.backupPath().orElseThrow()));
        assertArrayEquals(original, sourceBytes(world));
        assertEquals(2, fileNames(world).size());
    }

    @Test
    void directWriteCleanupFailureIsSuppressedAndDoesNotDeleteCollision()
        throws IOException {
        Path world = newWorld("direct-write-cleanup-fault");
        byte[] original = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] prior = "prior backup".getBytes(StandardCharsets.UTF_8);
        byte[] partial = "part".getBytes(StandardCharsets.UTF_8);
        writeSource(world, original);
        Path collision = world.resolve(
            SOURCE_NAME + "." + FIXED_STAMP + ".recognized.bak");
        Files.write(collision, prior);
        QuestLootPersistence.ArtifactAccess failingAccess =
            new DelegatingArtifactAccess(realAccess()) {
                @Override
                public int write(FileChannel channel, ByteBuffer buffer) throws IOException {
                    ByteBuffer wrapped = ByteBuffer.wrap(partial);
                    while (wrapped.hasRemaining()) {
                        super.write(channel, wrapped);
                    }
                    throw new IOException("injected direct-write failure");
                }

                @Override
                public void deleteIfExists(Path path) throws IOException {
                    if (path.getFileName().toString().endsWith("-1.recognized.bak")) {
                        throw new IOException("injected reservation cleanup failure");
                    }
                    super.deleteIfExists(path);
                }
            };

        IOException failure = assertThrows(IOException.class,
            () -> fixedPersistence(world, new QuestLootPersistence.NioDurability(),
                failingAccess).analyze());

        assertEquals("injected direct-write failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("injected reservation cleanup failure",
            failure.getSuppressed()[0].getMessage());
        assertArrayEquals(prior, Files.readAllBytes(collision));
        assertArrayEquals(original, sourceBytes(world));
        assertArrayEquals(partial, Files.readAllBytes(world.resolve(
            SOURCE_NAME + "." + FIXED_STAMP + "-1.recognized.bak")));
        assertEquals(List.of(SOURCE_NAME,
            SOURCE_NAME + "." + FIXED_STAMP + "-1.recognized.bak",
            SOURCE_NAME + "." + FIXED_STAMP + ".recognized.bak"), fileNames(world));
    }

    @Test
    void failedBackupIsNotCachedAsLifecycleSuccess() throws IOException {
        Path world = newWorld("failed-lifecycle");
        writeSource(world, "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8));
        QuestLootPersistence persistence = new QuestLootPersistence(world,
            (directory, classification, bytes) -> {
                throw new IOException("injected backup failure");
            });
        QuestLootLifecycle lifecycle = new QuestLootLifecycle(persistence);

        IOException failure = assertThrows(IOException.class, lifecycle::onServerStarted);

        assertEquals("injected backup failure", failure.getMessage());
        assertEquals(QuestLootLifecycle.State.WRITE_DISABLED, lifecycle.state());
        assertTrue(lifecycle.analysis().isEmpty());
        assertThrows(IOException.class, lifecycle::onServerStarted);
        assertEquals(List.of(SOURCE_NAME), fileNames(world));
    }

    @Test
    void duplicateLifecycleStartCreatesOnlyOneExactBackup() throws IOException {
        Path world = newWorld("duplicate-lifecycle");
        byte[] original = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        writeSource(world, original);
        QuestLootLifecycle lifecycle = new QuestLootLifecycle(fixedPersistence(world));

        QuestLootPersistence.AnalysisResult first = lifecycle.onServerStarted();
        QuestLootPersistence.AnalysisResult second = lifecycle.onServerStarted();

        assertSame(first, second);
        assertEquals(2, fileNames(world).size());
        assertArrayEquals(original, Files.readAllBytes(first.backupPath().orElseThrow()));
        lifecycle.onWorldSave();
        assertEquals(2, fileNames(world).size());
        lifecycle.onServerStopping();
        assertTrue(lifecycle.isClosed());
        assertArrayEquals(original, sourceBytes(world));
    }

    private QuestLootPersistence fixedPersistence(Path world) {
        return fixedPersistence(world, new QuestLootPersistence.NioDurability(), realAccess());
    }

    private QuestLootPersistence fixedPersistence(Path world,
        QuestLootPersistence.Durability durability, QuestLootPersistence.ArtifactAccess access) {
        return new QuestLootPersistence(world, Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
            durability, access);
    }

    private static QuestLootPersistence.ArtifactAccess realAccess() {
        return new QuestLootPersistence.NioArtifactAccess();
    }

    private Path newWorld(String name) throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve(name));
    }

    private static void writeSource(Path world, byte[] bytes) throws IOException {
        Files.write(world.resolve(SOURCE_NAME), bytes);
    }

    private static byte[] sourceBytes(Path world) throws IOException {
        return Files.readAllBytes(world.resolve(SOURCE_NAME));
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static long entryCount(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.count();
        }
    }

    private static byte[] join(byte[] first, byte[] second, byte[] third) {
        byte[] joined = new byte[first.length + second.length + third.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        System.arraycopy(third, 0, joined, first.length + second.length, third.length);
        return joined;
    }

    private static String ambiguousUnquotedName(int depth) {
        return "{\"groups:9\":{},deep\u2003\":" + "[".repeat(depth) + "0"
            + "]".repeat(depth) + ",end\":0}";
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: "
                + unsupported.getMessage());
        }
    }

    private static class DelegatingArtifactAccess
        implements QuestLootPersistence.ArtifactAccess {
        private final QuestLootPersistence.ArtifactAccess delegate;

        DelegatingArtifactAccess(QuestLootPersistence.ArtifactAccess delegate) {
            this.delegate = delegate;
        }

        @Override
        public FileChannel createNew(Path path) throws IOException {
            return delegate.createNew(path);
        }

        @Override
        public int write(FileChannel channel, ByteBuffer buffer) throws IOException {
            return delegate.write(channel, buffer);
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            delegate.deleteIfExists(path);
        }
    }
}
