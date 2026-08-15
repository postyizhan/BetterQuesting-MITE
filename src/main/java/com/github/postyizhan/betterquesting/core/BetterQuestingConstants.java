package com.github.postyizhan.betterquesting.core;

import net.minecraft.ResourceLocation;

public final class BetterQuestingConstants {
    public static final String MOD_ID = "betterquesting";
    public static final String RESOURCE_DOMAIN = MOD_ID;
    // Network channels are logical identifiers, not loadable resource files. The two-argument
    // constructor delegates with verify=true (bytecode: iconst_1), which enqueues the instance into
    // ResourceLocation.resources_to_verify; the integrated server then checks existence every 20
    // ticks and renders a persistent red "Resource not found" message on the client HUD because no
    // such file exists. Passing verify=false explicitly is required here. See docs/platform-probes.md.
    // Packet250CustomPayload reads channel names with a 20-character maximum.
    public static final ResourceLocation PROBE_C2S_CHANNEL = new ResourceLocation(MOD_ID, "pc2s", false);
    public static final ResourceLocation PROBE_S2C_CHANNEL = new ResourceLocation(MOD_ID, "ps2c", false);

    private BetterQuestingConstants() {
    }
}
