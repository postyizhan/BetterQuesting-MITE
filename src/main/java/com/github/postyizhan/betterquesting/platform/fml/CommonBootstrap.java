package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.network.probe.ProbePackets;
import com.github.postyizhan.betterquesting.platform.api.DirtyPlayerSink;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.github.postyizhan.betterquesting.questing.party.PartyManager;
import com.github.postyizhan.betterquesting.storage.LifeDatabase;
import com.github.postyizhan.betterquesting.storage.NameCache;
import com.github.postyizhan.betterquesting.storage.QuestLootPersistence;
import com.github.postyizhan.betterquesting.storage.QuestProgressPersistence;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import com.github.postyizhan.betterquesting.storage.migration.MigrationReport;
import java.io.IOException;
import java.util.Objects;
import moddedmite.rustedironcore.api.event.Handlers;
import moddedmite.rustedironcore.api.event.events.PlayerLoggedInEvent;
import moddedmite.rustedironcore.api.event.events.PlayerLoggedOutEvent;
import moddedmite.rustedironcore.api.event.listener.IInitializationListener;
import moddedmite.rustedironcore.api.event.listener.IPlayerEventListener;
import net.minecraft.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.FishModLoader;

public final class CommonBootstrap {
    private static boolean initialized;
    private static MinecraftServer questSettingsServer;
    private static QuestSettingsLifecycle questSettingsLifecycle;
    private static Object questDatabaseServer;
    private static QuestDatabaseLifecycle questDatabaseLifecycle;
    private static Object questDatabaseFailedServer;
    private static QuestDatabaseLifecycle questDatabaseFailedLifecycle;
    private static boolean questDatabaseStopPending;
    private static boolean questProgressWritesBlocked;
    private static Object questProgressServer;
    private static QuestProgressLifecycle questProgressLifecycle;
    private static MinecraftServer partyManagerServer;
    private static PartyManagerLifecycle partyManagerLifecycle;
    private static MinecraftServer nameCacheServer;
    private static NameCacheLifecycle nameCacheLifecycle;
    private static MinecraftServer lifeDatabaseServer;
    private static LifeDatabaseLifecycle lifeDatabaseLifecycle;
    private static Object questLootServer;
    private static QuestLootLifecycle questLootLifecycle;

    private CommonBootstrap() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ProbePackets.register();
        LoginSyncServerWiring.registerReader();
        Handlers.PlayerEvent.register(new IPlayerEventListener() {
            @Override
            public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
                updatePlayerNameCache(event.player());
            }

            @Override
            public void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
                LoginSyncServerWiring.onPlayerLoggedOut(event.player());
            }
        });
        Handlers.Initialization.register(new IInitializationListener() {
            @Override
            public void onServerStarted(MinecraftServer server) {
                BetterQuestingMod.LOGGER.info("Dedicated/integrated server start probe observed");
                loadIdentityMappings(server);
                loadQuestSettings(server);
                loadQuestDatabases(server);
                loadQuestProgress(server);
                loadParties(server);
                loadNameCache(server);
                loadLifeDatabase(server);
                loadQuestLoot(server);
            }
        });
    }

    private static void updatePlayerNameCache(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.mcServer;
        var identities = ServerIdentityContext.current(server).orElse(null);
        String reportedName = player.getEntityName();
        PlayerNameCacheConsumer.Outcome outcome = PlayerNameCacheConsumer.consume(
            server, reportedName, identities,
            identities == null ? null : () -> new MitePlayerIdentityAdapter(identities).resolve(player),
            ServerIdentityContext::current, nameCacheServer, nameCacheLifecycle, NameCache.INSTANCE);
        if (outcome == PlayerNameCacheConsumer.Outcome.UNRESOLVED) {
            BetterQuestingMod.LOGGER.warn(
                "Skipped BetterQuesting NameCache update for unresolved player username '{}'",
                reportedName);
        }
    }

    private static void loadQuestLoot(MinecraftServer server) {
        try {
            QuestLootPersistence.AnalysisResult result;
            if (server == questLootServer && questLootLifecycle != null) {
                result = questLootLifecycle.onServerStarted();
            } else {
                QuestLootLifecycle lifecycle = new QuestLootLifecycle(
                    MiteWorldStorage.resolveQuestLootRoot(server));
                result = startQuestLootLifecycle(server, lifecycle);
            }
            LoadLog decision = questLootLoadLog(result);
            if (decision.warning()) {
                BetterQuestingMod.LOGGER.warn(decision.message());
            } else {
                BetterQuestingMod.LOGGER.info(decision.message());
            }
        } catch (IOException | RuntimeException failure) {
            unbindQuestLoot();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting QuestLoot.json analysis failed closed; loot writes remain disabled",
                failure);
        }
    }

    static LoadLog questLootLoadLog(QuestLootPersistence.AnalysisResult result) {
        return switch (result.status()) {
            case ABSENT -> new LoadLog(false,
                "BetterQuesting QuestLoot.json absent; no loot data loaded and writes disabled");
            case BLOCKED -> new LoadLog(true,
                "BetterQuesting QuestLoot.json preserved but not loaded; exact backup copied to "
                    + result.backupPath().orElseThrow() + "; writes disabled: " + result.detail());
            case QUARANTINED -> new LoadLog(true,
                "BetterQuesting QuestLoot.json rejected; source preserved and evidence copied to "
                    + result.evidencePath().orElseThrow() + "; writes disabled: " + result.detail());
            case OVERSIZED -> new LoadLog(true,
                "BetterQuesting QuestLoot.json exceeded the 8 MiB analysis bound and was preserved; "
                    + "writes disabled");
        };
    }

    private static void loadParties(MinecraftServer server) {
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting party storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            PartyManagerLifecycle lifecycle = new PartyManagerLifecycle(
                storage, PartyManager.INSTANCE, currentBuild());
            lifecycle.onServerStarted();
            partyManagerServer = server;
            partyManagerLifecycle = lifecycle;
            BetterQuestingMod.LOGGER.info("Loaded BetterQuesting QuestingParties.json");
        } catch (IOException | RuntimeException failure) {
            partyManagerServer = null;
            partyManagerLifecycle = null;
            PartyManager.INSTANCE.reset();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting QuestingParties.json could not be loaded; parties remain empty",
                failure);
        }
    }

    private static void loadQuestDatabases(MinecraftServer server) {
        if (server == questDatabaseFailedServer && questDatabaseFailedLifecycle != null
            && questDatabaseFailedLifecycle.isSessionBlocked()) {
            QuestDatabase.INSTANCE.clear();
            QuestLineDatabase.INSTANCE.clear();
            questProgressWritesBlocked = true;
            BetterQuestingMod.LOGGER.warn(
                "BetterQuesting QuestDatabase.json remains blocked for this server session");
            return;
        }
        QuestDatabaseLifecycle lifecycle = server == questDatabaseFailedServer
            ? questDatabaseFailedLifecycle : null;
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting quest database storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            if (lifecycle == null) {
                lifecycle = new QuestDatabaseLifecycle(
                    storage, QuestDatabase.INSTANCE, QuestLineDatabase.INSTANCE, currentBuild());
            }
            questDatabaseFailedLifecycle = lifecycle;
            JsonDocumentStore.Outcome outcome = lifecycle.onServerStarted();
            logMigrationReport(lifecycle.lastMigrationReport());
            if (outcome == JsonDocumentStore.Outcome.QUARANTINED) {
                questDatabaseFailedServer = server;
                questDatabaseServer = null;
                questDatabaseLifecycle = null;
                questDatabaseStopPending = false;
                questProgressWritesBlocked = true;
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestDatabase.json was rejected; quest database writes remain disabled for this server session");
                return;
            }
            questDatabaseServer = server;
            questDatabaseLifecycle = lifecycle;
            questDatabaseFailedServer = null;
            questDatabaseFailedLifecycle = null;
            questDatabaseStopPending = false;
            questProgressWritesBlocked = false;
            BetterQuestingMod.LOGGER.info("Loaded BetterQuesting QuestDatabase.json");
        } catch (IOException | RuntimeException failure) {
            questDatabaseServer = null;
            questDatabaseLifecycle = null;
            questDatabaseFailedServer = server;
            questDatabaseFailedLifecycle = lifecycle;
            questDatabaseStopPending = false;
            questProgressWritesBlocked = true;
            QuestDatabase.INSTANCE.clear();
            QuestLineDatabase.INSTANCE.clear();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting QuestDatabase.json could not be loaded; quest databases remain empty",
                failure);
        }
    }

    private static void logMigrationReport(java.util.Optional<MigrationReport.Update> report) {
        report.ifPresent(update -> {
            switch (update.status()) {
                case RECORDED -> BetterQuestingMod.LOGGER.warn(
                    "Preserved {} task placeholders requiring migration review; recorded {} new "
                        + "occurrences in {}",
                    update.observed(), update.added(), MigrationReport.PATH);
                case UNCHANGED -> BetterQuestingMod.LOGGER.warn(
                    "Preserved {} task placeholders requiring migration review; all occurrences "
                        + "are already recorded in {}",
                    update.observed(), MigrationReport.PATH);
                case BLOCKED -> BetterQuestingMod.LOGGER.error(
                    "Preserved {} task placeholders requiring migration review, but {} was rejected as {}; "
                        + "quarantine copy: {}",
                    update.observed(), MigrationReport.PATH,
                    update.blockReason().orElse(MigrationReport.BlockReason.MALFORMED),
                    update.quarantinePath().orElse("unavailable"));
            }
        });
    }

    private static void loadQuestProgress(MinecraftServer server) {
        if (server != questDatabaseServer || questDatabaseLifecycle == null) {
            QuestDatabase.INSTANCE.setDirtyPlayerSink(DirtyPlayerSink.NO_OP);
            BetterQuestingMod.LOGGER.warn(
                "BetterQuesting quest progress was not loaded because QuestDatabase.json is unavailable");
            return;
        }
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting quest progress storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, QuestDatabase.INSTANCE);
            QuestProgressPersistence.LoadReport report = lifecycle.onServerStarted();
            questProgressServer = server;
            questProgressLifecycle = lifecycle;
            questProgressWritesBlocked = lifecycle.state() == QuestProgressLifecycle.State.WRITE_DISABLED;
            switch (report.status()) {
                case LOADED -> {
                    BetterQuestingMod.LOGGER.info(
                        "Loaded BetterQuesting progress for {} explicit UUID players", report.loadedPlayers().size());
                    logLegacyProgressMigration(report);
                }
                case ABSENT -> {
                    BetterQuestingMod.LOGGER.info(
                        "BetterQuesting QuestProgress directory absent; using empty progress");
                    logLegacyProgressMigration(report);
                }
                case QUARANTINED -> BetterQuestingMod.LOGGER.warn(
                    "BetterQuesting per-player progress was rejected without a partial merge: {}",
                    report.issues());
                case BLOCKED -> BetterQuestingMod.LOGGER.warn(
                    "BetterQuesting progress was preserved but not loaded because its semantics are unsupported: {}",
                    report.issues());
                case OVERSIZED -> BetterQuestingMod.LOGGER.warn(
                    "BetterQuesting QuestProgress input exceeded the bounded read and was preserved: {}",
                    report.issues());
            }
        } catch (IOException | RuntimeException failure) {
            questProgressServer = null;
            questProgressLifecycle = null;
            questProgressWritesBlocked = false;
            QuestDatabase.INSTANCE.values().forEach(quest -> quest.resetUser(null, true));
            QuestDatabase.INSTANCE.setDirtyPlayerSink(DirtyPlayerSink.NO_OP);
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting player progress could not be loaded; progress remains empty", failure);
        }
    }

    private static void logLegacyProgressMigration(QuestProgressPersistence.LoadReport report) {
        report.legacyMigration()
            .filter(migration -> migration.status() == QuestProgressPersistence.MigrationStatus.MIGRATED)
            .ifPresent(migration -> BetterQuestingMod.LOGGER.warn(
                "Migrated completion-only QuestProgress.json into {} UUID files; exact source backup: {}. "
                    + "The original is retained and ignored only while all migration digests validate; "
                    + "legacy UUIDs were retained without automatic identity mapping",
                migration.discoveredUuids().size(), migration.backupPath().orElseThrow()));
    }

    /**
     * Binds the identity service after world load, which is the earliest point where the overworld
     * save handler can supply a world directory.
     *
     * <p>Failures are logged and leave the caller without a binding rather than propagating.
     * Throwing here would abort server start inside a third-party listener, and
     * {@code current(server)} already refuses to serve identities for an unbound caller, so no
     * caller can mistake a load failure for "no mappings exist".
     */
    private static void loadIdentityMappings(MinecraftServer server) {
        try {
            ServerIdentityContext.bind(server);
        } catch (IOException | RuntimeException failure) {
            ServerIdentityContext.retire(server);
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting identity mappings could not be loaded for this server; "
                    + "its identity-keyed features stay disabled",
                failure);
        }
    }

    private static void loadNameCache(MinecraftServer server) {
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting name cache storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            NameCacheLifecycle lifecycle = new NameCacheLifecycle(storage, NameCache.INSTANCE, currentBuild());
            JsonDocumentStore.Outcome outcome = lifecycle.onServerStarted();
            nameCacheServer = server;
            nameCacheLifecycle = lifecycle;
            logIdentityDatabaseLoad(outcome, "NameCache.json", "name cache");
        } catch (IOException | RuntimeException failure) {
            nameCacheServer = null;
            nameCacheLifecycle = null;
            NameCache.INSTANCE.reset();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting NameCache.json could not be loaded; name cache remains empty", failure);
        }
    }

    private static void loadLifeDatabase(MinecraftServer server) {
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting life database storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            LifeDatabaseLifecycle lifecycle = new LifeDatabaseLifecycle(
                storage, LifeDatabase.INSTANCE, currentBuild());
            JsonDocumentStore.Outcome outcome = lifecycle.onServerStarted();
            lifeDatabaseServer = server;
            lifeDatabaseLifecycle = lifecycle;
            logIdentityDatabaseLoad(outcome, "LifeDatabase.json", "life database");
        } catch (IOException | RuntimeException failure) {
            lifeDatabaseServer = null;
            lifeDatabaseLifecycle = null;
            LifeDatabase.INSTANCE.reset();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting LifeDatabase.json could not be loaded; life database remains empty", failure);
        }
    }

    private static void logIdentityDatabaseLoad(JsonDocumentStore.Outcome outcome, String document,
        String emptyState) {
        LoadLog decision = identityDatabaseLoadLog(outcome, document, emptyState);
        if (decision.warning()) {
            BetterQuestingMod.LOGGER.warn(decision.message());
        } else {
            BetterQuestingMod.LOGGER.info(decision.message());
        }
    }

    static LoadLog identityDatabaseLoadLog(JsonDocumentStore.Outcome outcome, String document,
        String emptyState) {
        return switch (outcome) {
            case LOADED -> new LoadLog(false, "Loaded BetterQuesting " + document);
            case ABSENT -> new LoadLog(false,
                "BetterQuesting " + document + " absent; using default-empty " + emptyState);
            case QUARANTINED -> new LoadLog(true,
                "BetterQuesting " + document + " quarantined; " + emptyState + " empty, writes disabled");
        };
    }

    record LoadLog(boolean warning, String message) {
    }

    private static void loadQuestSettings(MinecraftServer server) {
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting settings storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(
                storage, QuestSettings.INSTANCE, currentBuild());
            lifecycle.onServerStarted();
            questSettingsServer = server;
            questSettingsLifecycle = lifecycle;
            BetterQuestingMod.LOGGER.info("Loaded BetterQuesting QuestSettings.json");
        } catch (IOException | RuntimeException failure) {
            questSettingsServer = null;
            questSettingsLifecycle = null;
            QuestSettings.INSTANCE.reset();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting QuestSettings.json could not be loaded; defaults remain active",
                failure);
        }
    }

    public static void onWorldSave(MinecraftServer server) {
        onWorldSave(server, false);
    }

    public static void onWorldSave(MinecraftServer server, boolean worldBeingDeleted) {
        if (server == null) {
            return;
        }
        if (worldBeingDeleted) {
            ServerIdentityContext.retire(server);
        }
        saveQuestSettings(server, worldBeingDeleted);
        onQuestWorldSave(server, worldBeingDeleted);
        saveParties(server, worldBeingDeleted);
        saveNameCache(server, worldBeingDeleted);
        saveLifeDatabase(server, worldBeingDeleted);
        onQuestLootWorldSave(server, worldBeingDeleted);
    }

    static QuestLootPersistence.AnalysisResult startQuestLootLifecycle(Object owner,
        QuestLootLifecycle lifecycle) throws IOException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (owner == questLootServer && questLootLifecycle != null) {
            return questLootLifecycle.onServerStarted();
        }
        unbindQuestLoot();
        QuestLootPersistence.AnalysisResult result = lifecycle.onServerStarted();
        questLootServer = owner;
        questLootLifecycle = lifecycle;
        return result;
    }

    static void onQuestLootWorldSave(Object owner, boolean worldBeingDeleted) {
        if (owner != questLootServer || questLootLifecycle == null) return;
        questLootLifecycle.onWorldSave(worldBeingDeleted);
        if (worldBeingDeleted) {
            questLootServer = null;
            questLootLifecycle = null;
        }
    }

    static void onQuestLootServerStopping(Object owner) {
        if (owner != questLootServer || questLootLifecycle == null) return;
        questLootLifecycle.onServerStopping();
        questLootServer = null;
        questLootLifecycle = null;
    }

    private static void unbindQuestLoot() {
        if (questLootLifecycle != null) questLootLifecycle.close();
        questLootServer = null;
        questLootLifecycle = null;
    }

    private static void saveQuestSettings(MinecraftServer server, boolean worldBeingDeleted) {
        if (server != questSettingsServer || questSettingsLifecycle == null) return;
        if (worldBeingDeleted) {
            try {
                questSettingsLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestSettings.json could not be discarded during world deletion", failure);
            } finally {
                questSettingsServer = null;
                questSettingsLifecycle = null;
            }
            return;
        }
        boolean retryingStopSave = questSettingsLifecycle.isRetryOnWorldSave();
        try {
            questSettingsLifecycle.onWorldSave();
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error("BetterQuesting QuestSettings.json could not be saved", failure);
        } finally {
            if (retryingStopSave) {
                questSettingsServer = null;
                questSettingsLifecycle = null;
            }
        }
    }

    static void bindQuestLifecycles(Object owner, QuestDatabaseLifecycle database,
        QuestProgressLifecycle progress) {
        if (database != null && database.isWritesDisabled()) {
            questDatabaseServer = null;
            questDatabaseLifecycle = null;
            questDatabaseFailedServer = owner;
            questDatabaseFailedLifecycle = database;
        } else {
            questDatabaseServer = owner;
            questDatabaseLifecycle = database;
            questDatabaseFailedServer = null;
            questDatabaseFailedLifecycle = null;
        }
        questDatabaseStopPending = false;
        questProgressServer = owner;
        questProgressLifecycle = progress;
        questProgressWritesBlocked = (database != null && database.isWritesDisabled())
            || (progress != null && progress.state() == QuestProgressLifecycle.State.WRITE_DISABLED);
    }

    static void onQuestWorldSave(Object owner, boolean worldBeingDeleted) {
        if (!worldBeingDeleted && owner == questDatabaseFailedServer) {
            discardFailedQuestDatabases(owner);
            return;
        }
        if (worldBeingDeleted) {
            retireQuestLifecycles(owner);
            return;
        }
        if (questProgressWritesBlocked) {
            retireQuestLifecycles(owner);
            return;
        }
        if (questDatabaseStopPending && questProgressLifecycle == null) {
            saveQuestDatabases(owner, false);
            return;
        }
        boolean completingStop = owner == questProgressServer
            && questProgressLifecycle != null
            && questProgressLifecycle.isStopCallbackPending();
        if (!saveQuestProgress(owner, false)) {
            if (questProgressWritesBlocked) retireQuestLifecycles(owner);
            return;
        }
        if (completingStop && questDatabaseStopPending) {
            finishQuestDatabaseStop(owner);
        } else {
            saveQuestDatabases(owner, false);
        }
    }

    private static void saveQuestDatabases(Object server, boolean worldBeingDeleted) {
        if (server != questDatabaseServer || questDatabaseLifecycle == null) return;
        if (!worldBeingDeleted && questProgressWritesBlocked) return;
        if (worldBeingDeleted) {
            try {
                questDatabaseLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestDatabase.json could not be discarded during world deletion", failure);
            } finally {
                questDatabaseServer = null;
                questDatabaseLifecycle = null;
                questDatabaseStopPending = false;
            }
            return;
        }
        boolean retryingStopSave = questDatabaseLifecycle.isRetryOnWorldSave();
        try {
            questDatabaseLifecycle.onWorldSave();
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error("BetterQuesting QuestDatabase.json could not be saved", failure);
        } finally {
            if (retryingStopSave) {
                questDatabaseServer = null;
                questDatabaseLifecycle = null;
                questDatabaseStopPending = false;
            }
        }
    }

    private static boolean saveQuestProgress(Object server, boolean worldBeingDeleted) {
        if (server != questProgressServer || questProgressLifecycle == null) return false;
        if (worldBeingDeleted) {
            try {
                questProgressLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestProgress files could not be discarded during world deletion", failure);
            } finally {
                questProgressServer = null;
                questProgressLifecycle = null;
            }
            return true;
        }
        boolean writesEnabled = questProgressLifecycle.state() != QuestProgressLifecycle.State.WRITE_DISABLED;
        boolean pendingStopCallback = questProgressLifecycle.isStopCallbackPending();
        boolean completed = false;
        try {
            questProgressLifecycle.onWorldSave();
            completed = true;
        } catch (IOException | RuntimeException failure) {
            if (questProgressLifecycle.state() == QuestProgressLifecycle.State.WRITE_DISABLED) {
                questProgressWritesBlocked = true;
            }
            BetterQuestingMod.LOGGER.error("BetterQuesting QuestProgress files could not be saved", failure);
        } finally {
            if (pendingStopCallback && completed && !questProgressLifecycle.isStopCallbackPending()) {
                questProgressServer = null;
                questProgressLifecycle = null;
            }
        }
        return completed && writesEnabled;
    }

    private static void retireQuestLifecycles(Object owner) {
        if (owner != questProgressServer && owner != questDatabaseServer
            && owner != questDatabaseFailedServer) return;
        saveQuestProgress(owner, true);
        saveQuestDatabases(owner, true);
        endFailedQuestDatabaseSession(owner);
        questDatabaseStopPending = false;
        questProgressWritesBlocked = false;
    }

    private static void discardFailedQuestDatabases(Object owner) {
        if (owner != questDatabaseFailedServer) return;
        try {
            if (questDatabaseFailedLifecycle != null) {
                questDatabaseFailedLifecycle.onWorldSave(true);
            }
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting failed QuestDatabase lifecycle could not be discarded", failure);
        } finally {
            QuestDatabase.INSTANCE.clear();
            QuestLineDatabase.INSTANCE.clear();
        }
    }

    private static void endFailedQuestDatabaseSession(Object owner) {
        if (owner != questDatabaseFailedServer) return;
        discardFailedQuestDatabases(owner);
        questDatabaseFailedServer = null;
        questDatabaseFailedLifecycle = null;
        questProgressWritesBlocked = false;
    }

    private static void saveParties(MinecraftServer server, boolean worldBeingDeleted) {
        if (server != partyManagerServer || partyManagerLifecycle == null) return;
        if (worldBeingDeleted) {
            try {
                partyManagerLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestingParties.json could not be discarded during world deletion", failure);
            } finally {
                partyManagerServer = null;
                partyManagerLifecycle = null;
            }
            return;
        }
        boolean retryingStopSave = partyManagerLifecycle.isRetryOnWorldSave();
        try {
            partyManagerLifecycle.onWorldSave();
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error("BetterQuesting QuestingParties.json could not be saved", failure);
        } finally {
            if (retryingStopSave) {
                partyManagerServer = null;
                partyManagerLifecycle = null;
            }
        }
    }

    private static void saveNameCache(MinecraftServer server, boolean worldBeingDeleted) {
        if (server != nameCacheServer || nameCacheLifecycle == null) return;
        if (worldBeingDeleted) {
            try {
                nameCacheLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting NameCache.json could not be discarded during world deletion", failure);
            } finally {
                nameCacheServer = null;
                nameCacheLifecycle = null;
            }
            return;
        }
        boolean retryingStopSave = nameCacheLifecycle.isRetryOnWorldSave();
        try {
            nameCacheLifecycle.onWorldSave();
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error("BetterQuesting NameCache.json could not be saved", failure);
        } finally {
            if (retryingStopSave) {
                nameCacheServer = null;
                nameCacheLifecycle = null;
            }
        }
    }

    private static void saveLifeDatabase(MinecraftServer server, boolean worldBeingDeleted) {
        if (server != lifeDatabaseServer || lifeDatabaseLifecycle == null) return;
        if (worldBeingDeleted) {
            try {
                lifeDatabaseLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting LifeDatabase.json could not be discarded during world deletion", failure);
            } finally {
                lifeDatabaseServer = null;
                lifeDatabaseLifecycle = null;
            }
            return;
        }
        boolean retryingStopSave = lifeDatabaseLifecycle.isRetryOnWorldSave();
        try {
            lifeDatabaseLifecycle.onWorldSave();
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error("BetterQuesting LifeDatabase.json could not be saved", failure);
        } finally {
            if (retryingStopSave) {
                lifeDatabaseServer = null;
                lifeDatabaseLifecycle = null;
            }
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        onServerStopping(server, false);
    }

    public static void onServerStopping(MinecraftServer server, boolean worldBeingDeleted) {
        BetterQuestingMod.LOGGER.info("Server stop probe observed");
        LoginSyncServerWiring.onServerStopping(server);
        onQuestServerStopping(server, worldBeingDeleted);
        if (server == questSettingsServer && questSettingsLifecycle != null) {
            try {
                questSettingsLifecycle.onServerStopping(worldBeingDeleted);
                questSettingsServer = null;
                questSettingsLifecycle = null;
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestSettings.json could not be flushed during server stop",
                    failure);
                if (!questSettingsLifecycle.isRetryOnWorldSave()) {
                    questSettingsServer = null;
                    questSettingsLifecycle = null;
                }
            }
        }
        if (server == partyManagerServer && partyManagerLifecycle != null) {
            try {
                partyManagerLifecycle.onServerStopping(worldBeingDeleted);
                partyManagerServer = null;
                partyManagerLifecycle = null;
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestingParties.json could not be flushed during server stop",
                    failure);
                if (!partyManagerLifecycle.isRetryOnWorldSave()) {
                    partyManagerServer = null;
                    partyManagerLifecycle = null;
                }
            }
        }
        if (server == nameCacheServer && nameCacheLifecycle != null) {
            try {
                nameCacheLifecycle.onServerStopping(worldBeingDeleted);
                nameCacheServer = null;
                nameCacheLifecycle = null;
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting NameCache.json could not be flushed during server stop", failure);
                if (!nameCacheLifecycle.isRetryOnWorldSave()) {
                    nameCacheServer = null;
                    nameCacheLifecycle = null;
                }
            }
        }
        if (server == lifeDatabaseServer && lifeDatabaseLifecycle != null) {
            try {
                lifeDatabaseLifecycle.onServerStopping(worldBeingDeleted);
                lifeDatabaseServer = null;
                lifeDatabaseLifecycle = null;
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting LifeDatabase.json could not be flushed during server stop", failure);
                if (!lifeDatabaseLifecycle.isRetryOnWorldSave()) {
                    lifeDatabaseServer = null;
                    lifeDatabaseLifecycle = null;
                }
            }
        }
        onQuestLootServerStopping(server);
        ServerIdentityContext.retire(server);
    }

    static void onQuestServerStopping(Object server, boolean worldBeingDeleted) {
        boolean progressCommitted = false;
        if (server == questProgressServer && questProgressLifecycle != null) {
            QuestProgressLifecycle lifecycle = questProgressLifecycle;
            boolean writesEnabled = lifecycle.state() != QuestProgressLifecycle.State.WRITE_DISABLED;
            try {
                lifecycle.onServerStopping(worldBeingDeleted);
                progressCommitted = worldBeingDeleted || writesEnabled;
                if (!lifecycle.isStopCallbackPending()) {
                    questProgressServer = null;
                    questProgressLifecycle = null;
                }
            } catch (IOException | RuntimeException failure) {
                if (lifecycle.state() == QuestProgressLifecycle.State.WRITE_DISABLED) {
                    questProgressWritesBlocked = true;
                }
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestProgress files could not be flushed during server stop", failure);
                if (!lifecycle.isStopCallbackPending()) {
                    questProgressServer = null;
                    questProgressLifecycle = null;
                }
            }
        }
        if (worldBeingDeleted) {
            finishQuestDatabaseStop(server, true);
        } else if (progressCommitted) {
            finishQuestDatabaseStop(server);
        } else if (server == questDatabaseServer && questDatabaseLifecycle != null
            && server == questProgressServer && questProgressLifecycle != null
            && questProgressLifecycle.isStopCallbackPending()) {
            questDatabaseStopPending = true;
        } else {
            finishQuestDatabaseStop(server, true);
        }
        endFailedQuestDatabaseSession(server);
    }

    private static void finishQuestDatabaseStop(Object server) {
        finishQuestDatabaseStop(server, false);
    }

    private static void finishQuestDatabaseStop(Object server, boolean worldBeingDeleted) {
        if (server != questDatabaseServer || questDatabaseLifecycle == null) return;
        try {
            questDatabaseLifecycle.onServerStopping(worldBeingDeleted);
            questDatabaseServer = null;
            questDatabaseLifecycle = null;
            questDatabaseStopPending = false;
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting QuestDatabase.json could not be flushed during server stop", failure);
            if (!questDatabaseLifecycle.isRetryOnWorldSave()) {
                questDatabaseServer = null;
                questDatabaseLifecycle = null;
                questDatabaseStopPending = false;
            } else {
                questDatabaseStopPending = true;
            }
        }
    }

    private static String currentBuild() {
        return FishModLoader.getModContainer(BetterQuestingConstants.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
