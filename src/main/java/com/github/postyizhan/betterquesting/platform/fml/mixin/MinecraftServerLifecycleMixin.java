package com.github.postyizhan.betterquesting.platform.fml.mixin;

import com.github.postyizhan.betterquesting.platform.fml.CommonBootstrap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerLifecycleMixin {
    /**
     * Runtime-only bridge: MinecraftServer is not constructible in the pure-JVM test suite, so the
     * JSON/default/quarantine contract is covered by QuestSettingsLifecycleTest while this mapped
     * method descriptor remains verified by compilation against the MITE jar.
     */
    @Inject(method = "saveAllWorlds", at = @At("RETURN"))
    private void betterquesting$onWorldSave(boolean flush, boolean saveAll, CallbackInfo ci) {
        CommonBootstrap.onWorldSave((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void betterquesting$onServerStopping(CallbackInfo ci) {
        CommonBootstrap.onServerStopping((MinecraftServer) (Object) this);
    }
}
