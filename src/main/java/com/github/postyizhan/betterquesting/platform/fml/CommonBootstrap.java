package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.network.probe.ProbePackets;
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
            }
        });
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
        if (server == null || server != questSettingsServer || questSettingsLifecycle == null) {
            return;
        }
        try {
            questSettingsLifecycle.onWorldSave();
        } catch (IOException | RuntimeException failure) {
            BetterQuestingMod.LOGGER.error("BetterQuesting QuestSettings.json could not be saved", failure);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        BetterQuestingMod.LOGGER.info("Server stop probe observed");
        if (server == questSettingsServer && questSettingsLifecycle != null) {
            try {
                questSettingsLifecycle.onServerStopping();
            } catch (IOException | RuntimeException failure) {
                BetterQuestingMod.LOGGER.error(
                    "BetterQuesting QuestSettings.json could not be flushed during server stop",
                    failure);
            }
        }
        questSettingsServer = null;
        questSettingsLifecycle = null;
        // The storage object is world-lifetime bound, so it must not survive into the next session.
        ServerIdentityContext.unbind();
    }

    private static String currentBuild() {
        return FabricLoader.getInstance().getModContainer(BetterQuestingConstants.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
