package com.github.postyizhan.betterquesting.platform.fml.mixin;

import com.github.postyizhan.betterquesting.platform.fml.CommonBootstrap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerLifecycleMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void betterquesting$onServerStopping(CallbackInfo ci) {
        CommonBootstrap.onServerStopping((MinecraftServer) (Object) this);
    }
}
