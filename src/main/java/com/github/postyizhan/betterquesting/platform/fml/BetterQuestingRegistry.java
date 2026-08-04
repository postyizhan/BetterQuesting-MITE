package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import huix.glacier.api.entrypoint.IGameRegistry;
import huix.glacier.api.registry.MinecraftRegistry;

public final class BetterQuestingRegistry implements IGameRegistry {
    public static final MinecraftRegistry REGISTRY = new MinecraftRegistry(BetterQuestingConstants.MOD_ID).initAutoItemRegister();

    @Override
    public void onGameRegistry() {
        // Stage 1 intentionally registers no content.
    }
}
