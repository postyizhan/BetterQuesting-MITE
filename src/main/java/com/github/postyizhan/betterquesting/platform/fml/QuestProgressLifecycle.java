package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.platform.api.DirtyPlayerSink;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.storage.QuestProgressPersistence;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

/** World lifecycle for canonical per-player quest progress files. */
public final class QuestProgressLifecycle implements DirtyPlayerSink {
    public enum State { WRITABLE, RETRY_PENDING, WRITE_DISABLED }

    private final WorldStorage storage;
    private final QuestDatabase quests;
    private final QuestProgressPersistence persistence;
    private final Set<UUID> dirtyPlayers = new LinkedHashSet<>();
    private final Map<UUID, NBTTagCompound> stopRetrySnapshots = new LinkedHashMap<>();
    private boolean retryOnWorldSave;
    private boolean progressWritesEnabled;
    private boolean preserveStateWhenWriteDisabled;
    private boolean stopCallbackPending;

    public QuestProgressLifecycle(WorldStorage storage, QuestDatabase quests) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.quests = Objects.requireNonNull(quests, "quests");
        this.persistence = new QuestProgressPersistence(quests, storage);
        quests.setDirtyPlayerSink(this);
    }

    public synchronized QuestProgressPersistence.LoadReport onServerStarted() throws IOException {
        try {
            QuestProgressPersistence.LoadReport report = persistence.load();
            dirtyPlayers.clear();
            stopRetrySnapshots.clear();
            retryOnWorldSave = false;
            preserveStateWhenWriteDisabled = false;
            stopCallbackPending = false;
            progressWritesEnabled = report.status() == QuestProgressPersistence.LoadStatus.ABSENT
                || report.status() == QuestProgressPersistence.LoadStatus.LOADED;
            quests.setDirtyPlayerSink(progressWritesEnabled ? this : DirtyPlayerSink.NO_OP);
            return report;
        } catch (IOException | RuntimeException failure) {
            discardWorldState();
            retryOnWorldSave = false;
            progressWritesEnabled = false;
            throw failure;
        }
    }

    @Override
    public synchronized void markDirty(UUID uuid) {
        if (progressWritesEnabled && uuid != null) dirtyPlayers.add(uuid);
    }

    @Override
    public synchronized void markDirty(Collection<UUID> uuids) {
        if (uuids == null) return;
        for (UUID uuid : uuids) markDirty(uuid);
    }

    public synchronized Set<UUID> dirtyPlayersSnapshot() {
        return Set.copyOf(dirtyPlayers);
    }

    public void onWorldSave() throws IOException {
        onWorldSave(false);
    }

    public synchronized void onWorldSave(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted) {
            discardWorldState();
            return;
        }
        if (!progressWritesEnabled) {
            stopCallbackPending = false;
            if (!preserveStateWhenWriteDisabled) discardWorldState();
            return;
        }
        if (!retryOnWorldSave) {
            Map<UUID, NBTTagCompound> snapshots;
            try {
                snapshots = snapshotDirtyPlayers();
            } catch (RuntimeException failure) {
                disableWritesAfterSnapshotRefusal(false);
                throw failure;
            }
            saveSnapshots(snapshots);
            return;
        }
        try {
            try {
                mergeCurrentDirtySnapshots();
            } catch (RuntimeException failure) {
                disableWritesAfterSnapshotRefusal(true);
                throw failure;
            }
            saveSnapshots(stopRetrySnapshots);
            storage.flush();
            retryOnWorldSave = false;
            stopCallbackPending = false;
            discardWorldState();
        } catch (IOException | RuntimeException failure) {
            if (progressWritesEnabled) {
                retryOnWorldSave = true;
                stopCallbackPending = true;
            }
            throw failure;
        }
    }

    public void onServerStopping() throws IOException {
        onServerStopping(false);
    }

    public synchronized void onServerStopping(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted) {
            discardWorldState();
            return;
        }
        if (!progressWritesEnabled) {
            stopCallbackPending = false;
            if (!preserveStateWhenWriteDisabled) discardWorldState();
            return;
        }
        try {
            Map<UUID, NBTTagCompound> snapshots;
            try {
                snapshots = snapshotDirtyPlayers();
            } catch (RuntimeException failure) {
                disableWritesAfterSnapshotRefusal(true);
                throw failure;
            }
            stopRetrySnapshots.clear();
            stopRetrySnapshots.putAll(snapshots);
            saveSnapshots(stopRetrySnapshots);
            storage.flush();
        } catch (IOException | RuntimeException failure) {
            if (progressWritesEnabled) {
                retryOnWorldSave = true;
                stopCallbackPending = true;
            }
            throw failure;
        }
        retryOnWorldSave = false;
        stopCallbackPending = false;
        discardWorldState();
    }

    public synchronized boolean isRetryOnWorldSave() {
        return retryOnWorldSave;
    }

    public synchronized State state() {
        if (!progressWritesEnabled) return State.WRITE_DISABLED;
        return retryOnWorldSave ? State.RETRY_PENDING : State.WRITABLE;
    }

    public synchronized boolean isStopCallbackPending() {
        return stopCallbackPending;
    }

    public synchronized boolean isLiveStatePreserved() {
        return preserveStateWhenWriteDisabled;
    }

    private Map<UUID, NBTTagCompound> snapshotDirtyPlayers() {
        List<UUID> players = dirtyPlayers.stream()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
        Map<UUID, NBTTagCompound> snapshots = new LinkedHashMap<>();
        for (UUID player : players) snapshots.put(player, persistence.snapshotPlayer(player));
        return snapshots;
    }

    private void saveSnapshots(Map<UUID, NBTTagCompound> snapshots) throws IOException {
        for (var iterator = snapshots.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<UUID, NBTTagCompound> entry = iterator.next();
            persistence.savePlayer(entry.getKey(), entry.getValue());
            iterator.remove();
            dirtyPlayers.remove(entry.getKey());
        }
    }

    private void mergeCurrentDirtySnapshots() {
        for (UUID player : dirtyPlayersSnapshot()) {
            if (!stopRetrySnapshots.containsKey(player)) {
                stopRetrySnapshots.put(player, persistence.snapshotPlayer(player));
            }
        }
    }

    private void disableWritesAfterSnapshotRefusal(boolean callbackPending) {
        progressWritesEnabled = false;
        retryOnWorldSave = false;
        preserveStateWhenWriteDisabled = true;
        stopCallbackPending = callbackPending;
        quests.setDirtyPlayerSink(DirtyPlayerSink.NO_OP);
    }

    private void discardWorldState() {
        persistence.clearProgress();
        dirtyPlayers.clear();
        stopRetrySnapshots.clear();
        progressWritesEnabled = false;
        preserveStateWhenWriteDisabled = false;
        retryOnWorldSave = false;
        stopCallbackPending = false;
        quests.setDirtyPlayerSink(DirtyPlayerSink.NO_OP);
    }
}
