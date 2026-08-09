package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.network.probe.ProbePackets;
import java.io.IOException;
import moddedmite.rustedironcore.api.event.Handlers;
import moddedmite.rustedironcore.api.event.listener.IInitializationListener;
import net.minecraft.server.MinecraftServer;

public final class CommonBootstrap {
    private static boolean initialized;

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

    public static void onServerStopping(MinecraftServer server) {
        BetterQuestingMod.LOGGER.info("Server stop probe observed");
        // The storage object is world-lifetime bound, so it must not survive into the next session.
        ServerIdentityContext.unbind();
    }
}
