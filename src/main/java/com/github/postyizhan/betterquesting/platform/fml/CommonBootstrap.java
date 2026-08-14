package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.network.probe.ProbePackets;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
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
            }
        });
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
        // The storage object is world-lifetime bound, so it must not survive into the next session.
        ServerIdentityContext.unbind();
    }

    private static String currentBuild() {
        return FabricLoader.getInstance().getModContainer(BetterQuestingConstants.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
