package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.network.probe.ProbePackets;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.github.postyizhan.betterquesting.questing.party.PartyManager;
import com.github.postyizhan.betterquesting.storage.LifeDatabase;
import com.github.postyizhan.betterquesting.storage.NameCache;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import java.io.IOException;
import moddedmite.rustedironcore.api.event.Handlers;
import moddedmite.rustedironcore.api.event.listener.IInitializationListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

public final class CommonBootstrap {
    private static boolean initialized;
    private static MinecraftServer questSettingsServer;
    private static QuestSettingsLifecycle questSettingsLifecycle;
    private static MinecraftServer questDatabaseServer;
    private static QuestDatabaseLifecycle questDatabaseLifecycle;
    private static MinecraftServer partyManagerServer;
    private static PartyManagerLifecycle partyManagerLifecycle;
    private static MinecraftServer nameCacheServer;
    private static NameCacheLifecycle nameCacheLifecycle;
    private static MinecraftServer lifeDatabaseServer;
    private static LifeDatabaseLifecycle lifeDatabaseLifecycle;

    private CommonBootstrap() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ProbePackets.register();
        Handlers.Initialization.register(new IInitializationListener() {
            @Override
            public void onServerStarted(MinecraftServer server) {
                BetterQuestingMod.LOGGER.info("Dedicated/integrated server start probe observed");
                loadIdentityMappings(server);
                loadQuestSettings(server);
                loadQuestDatabases(server);
                loadParties(server);
                loadNameCache(server);
                loadLifeDatabase(server);
            }
        });
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
        try {
            MiteWorldStorage storage = MiteWorldStorage.resolve(server);
            if (!storage.isAvailable()) {
                throw new IOException("BetterQuesting quest database storage is unavailable: "
                    + storage.getDisabledReason().orElse("unknown reason"));
            }
            QuestDatabaseLifecycle lifecycle = new QuestDatabaseLifecycle(
                storage, QuestDatabase.INSTANCE, QuestLineDatabase.INSTANCE, currentBuild());
            lifecycle.onServerStarted();
            questDatabaseServer = server;
            questDatabaseLifecycle = lifecycle;
            BetterQuestingMod.LOGGER.info("Loaded BetterQuesting QuestDatabase.json");
        } catch (IOException | RuntimeException failure) {
            questDatabaseServer = null;
            questDatabaseLifecycle = null;
            QuestDatabase.INSTANCE.clear();
            QuestLineDatabase.INSTANCE.clear();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting QuestDatabase.json could not be loaded; quest databases remain empty",
                failure);
        }
    }

    /**
     * Binds the identity service after world load, which is the earliest point where the overworld
     * save handler can supply a world directory.
     *
     * <p>Failures are logged and leave the context unbound rather than propagating. Throwing here
     * would abort server start inside a third-party listener, and an unbound context already refuses
     * to serve identities, so no caller can mistake a load failure for "no mappings exist".
     */
    private static void loadIdentityMappings(MinecraftServer server) {
        try {
            ServerIdentityContext.bind(server);
        } catch (IOException | RuntimeException failure) {
            ServerIdentityContext.unbind();
            BetterQuestingMod.LOGGER.error(
                "BetterQuesting identity mappings could not be loaded; identity-keyed features stay disabled",
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
        saveQuestSettings(server, worldBeingDeleted);
        saveQuestDatabases(server, worldBeingDeleted);
        saveParties(server, worldBeingDeleted);
        saveNameCache(server, worldBeingDeleted);
        saveLifeDatabase(server, worldBeingDeleted);
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

    private static void saveQuestDatabases(MinecraftServer server, boolean worldBeingDeleted) {
        if (server != questDatabaseServer || questDatabaseLifecycle == null) return;
        if (worldBeingDeleted) {
            try {
                questDatabaseLifecycle.onWorldSave(true);
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestDatabase.json could not be discarded during world deletion", failure);
            } finally {
                questDatabaseServer = null;
                questDatabaseLifecycle = null;
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
            }
        }
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
        if (server == questDatabaseServer && questDatabaseLifecycle != null) {
            try {
                questDatabaseLifecycle.onServerStopping(worldBeingDeleted);
                questDatabaseServer = null;
                questDatabaseLifecycle = null;
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestDatabase.json could not be flushed during server stop",
                    failure);
                if (!questDatabaseLifecycle.isRetryOnWorldSave()) {
                    questDatabaseServer = null;
                    questDatabaseLifecycle = null;
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
        // The storage object is world-lifetime bound, so it must not survive into the next session.
        ServerIdentityContext.unbind();
    }

    private static String currentBuild() {
        return FabricLoader.getInstance().getModContainer(BetterQuestingConstants.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
