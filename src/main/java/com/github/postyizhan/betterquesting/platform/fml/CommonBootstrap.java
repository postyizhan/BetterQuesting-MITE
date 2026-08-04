package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.network.probe.ProbePackets;
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
            }
        });
    }

    public static void onServerStopping(MinecraftServer server) {
        BetterQuestingMod.LOGGER.info("Server stop probe observed");
    }
}
