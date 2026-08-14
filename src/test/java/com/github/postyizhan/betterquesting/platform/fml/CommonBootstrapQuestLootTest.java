package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.storage.QuestLootPersistence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonBootstrapQuestLootTest {
    private final Object firstOwner = new Object();
    private final Object secondOwner = new Object();

    @TempDir
    Path firstWorld;

    @TempDir
    Path secondWorld;

    @AfterEach
    void clearBinding() {
        CommonBootstrap.onQuestLootServerStopping(firstOwner);
        CommonBootstrap.onQuestLootServerStopping(secondOwner);
    }

    @Test
    void mismatchedWorldCannotRetireBoundLifecycleAndRebindClosesPreviousWorld() throws Exception {
        QuestLootLifecycle first = lifecycle(firstWorld);
        CommonBootstrap.startQuestLootLifecycle(firstOwner, first);

        CommonBootstrap.onQuestLootWorldSave(secondOwner, true);
        CommonBootstrap.onQuestLootServerStopping(secondOwner);
        assertFalse(first.isClosed());

        QuestLootLifecycle second = lifecycle(secondWorld);
        CommonBootstrap.startQuestLootLifecycle(secondOwner, second);
        assertTrue(first.isClosed());

        CommonBootstrap.onQuestLootServerStopping(firstOwner);
        assertFalse(second.isClosed());
        CommonBootstrap.onQuestLootServerStopping(secondOwner);
        assertTrue(second.isClosed());
    }

    @Test
    void duplicateOwnerStartReturnsBoundReportWithoutAnalyzingCandidateWorld() throws Exception {
        byte[] recognized = "{\"groups:9\":{}}".getBytes(StandardCharsets.UTF_8);
        Files.write(firstWorld.resolve(QuestLootPersistence.PATH), recognized);
        Files.write(secondWorld.resolve(QuestLootPersistence.PATH), recognized);
        QuestLootLifecycle first = lifecycle(firstWorld);

        QuestLootPersistence.AnalysisResult initial =
            CommonBootstrap.startQuestLootLifecycle(firstOwner, first);
        QuestLootLifecycle unusedCandidate = lifecycle(secondWorld);
        QuestLootPersistence.AnalysisResult repeated =
            CommonBootstrap.startQuestLootLifecycle(firstOwner, unusedCandidate);

        assertSame(initial, repeated);
        assertFalse(first.isClosed());
        assertTrue(unusedCandidate.analysis().isEmpty());
        assertEquals(1, entryCount(firstWorld));
        assertEquals(1, entryCount(secondWorld));
    }

    @Test
    void failedStartIsNotBoundAndAnotherWorldCanStart() throws Exception {
        QuestLootLifecycle rejected = new QuestLootLifecycle(() -> {
            throw new java.io.IOException("injected analysis failure");
        });

        assertThrows(java.io.IOException.class,
            () -> CommonBootstrap.startQuestLootLifecycle(firstOwner, rejected));
        assertTrue(rejected.analysis().isEmpty());

        QuestLootPersistence.AnalysisResult accepted = CommonBootstrap.startQuestLootLifecycle(
            secondOwner, lifecycle(firstWorld));
        assertEquals(QuestLootPersistence.Status.BLOCKED, accepted.status());
    }

    @Test
    void transientFailureRetriesSameLifecycleThenCachesSuccessfulAnalysis() throws Exception {
        AtomicInteger analyzerCalls = new AtomicInteger();
        QuestLootPersistence.AnalysisResult eventual = new QuestLootPersistence.AnalysisResult(
            QuestLootPersistence.Status.BLOCKED,
            Optional.of(firstWorld.resolve("QuestLoot.json.test.recognized.bak")),
            "eventual success");
        QuestLootLifecycle lifecycle = new QuestLootLifecycle(() -> {
            if (analyzerCalls.incrementAndGet() == 1) {
                throw new java.io.IOException("transient analysis failure");
            }
            return eventual;
        });

        assertThrows(java.io.IOException.class,
            () -> CommonBootstrap.startQuestLootLifecycle(firstOwner, lifecycle));
        assertEquals(QuestLootLifecycle.State.WRITE_DISABLED, lifecycle.state());
        assertTrue(lifecycle.analysis().isEmpty());

        QuestLootPersistence.AnalysisResult retried =
            CommonBootstrap.startQuestLootLifecycle(firstOwner, lifecycle);
        QuestLootPersistence.AnalysisResult cached =
            CommonBootstrap.startQuestLootLifecycle(firstOwner, lifecycle);

        assertSame(eventual, retried);
        assertSame(retried, cached);
        assertEquals(2, analyzerCalls.get());
        assertEquals(QuestLootLifecycle.State.WRITE_DISABLED, lifecycle.state());
        assertSame(eventual, lifecycle.analysis().orElseThrow());
    }

    private static QuestLootLifecycle lifecycle(Path world) {
        return new QuestLootLifecycle(() -> new QuestLootPersistence.AnalysisResult(
            QuestLootPersistence.Status.BLOCKED,
            Optional.of(world.resolve("QuestLoot.json.test.recognized.bak")),
            "test legacy QuestLoot envelope recognized at " + world));
    }

    private static long entryCount(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return entries.count();
        }
    }
}
