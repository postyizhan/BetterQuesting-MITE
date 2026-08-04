package com.github.postyizhan.betterquesting;

import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.platform.fml.CommonBootstrap;
import net.fabricmc.api.ModInitializer;
import net.xiaoyu233.fml.ModResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class BetterQuestingMod implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger(BetterQuestingConstants.MOD_ID);

    @Override
    public void onInitialize() {
        ModResourceManager.addResourcePackDomain(BetterQuestingConstants.RESOURCE_DOMAIN);
        CommonBootstrap.initialize();
        LOGGER.info("BetterQuesting platform probe bootstrap initialized");
    }
}
