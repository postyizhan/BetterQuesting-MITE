package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.storage.QuestLootPersistence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** World-bound analysis lifecycle; stage 3 deliberately has no QuestLoot save callback. */
public final class QuestLootLifecycle {
    public enum State { NEW, WRITE_DISABLED, CLOSED }

    @FunctionalInterface
    interface Analyzer {
        QuestLootPersistence.AnalysisResult analyze() throws IOException;
    }

    private final Analyzer analyzer;
    private State state = State.NEW;
    private QuestLootPersistence.AnalysisResult analysis;

    public QuestLootLifecycle(Path worldRoot) {
        this(new QuestLootPersistence(Objects.requireNonNull(worldRoot, "worldRoot")));
    }

    public QuestLootLifecycle(QuestLootPersistence persistence) {
        this(Objects.requireNonNull(persistence, "persistence")::analyze);
    }

    QuestLootLifecycle(Analyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    public synchronized QuestLootPersistence.AnalysisResult onServerStarted() throws IOException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("QuestLoot lifecycle is closed");
        }
        if (analysis != null) return analysis;
        try {
            analysis = analyzer.analyze();
            state = State.WRITE_DISABLED;
            return analysis;
        } catch (IOException | RuntimeException failure) {
            state = State.WRITE_DISABLED;
            throw failure;
        }
    }

    public synchronized void onWorldSave() {
        onWorldSave(false);
    }

    public synchronized void onWorldSave(boolean worldBeingDeleted) {
        if (worldBeingDeleted) close();
    }

    public synchronized void onServerStopping() {
        close();
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Optional<QuestLootPersistence.AnalysisResult> analysis() {
        return Optional.ofNullable(analysis);
    }

    public synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    public synchronized void close() {
        analysis = null;
        state = State.CLOSED;
    }
}
