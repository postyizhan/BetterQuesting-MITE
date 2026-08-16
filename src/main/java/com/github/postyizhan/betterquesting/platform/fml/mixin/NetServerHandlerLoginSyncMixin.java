package com.github.postyizhan.betterquesting.platform.fml.mixin;

import com.github.postyizhan.betterquesting.platform.fml.LoginSyncServerWiring;
import net.minecraft.INetworkManager;
import net.minecraft.NetServerHandler;
import net.minecraft.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetServerHandler.class)
public abstract class NetServerHandlerLoginSyncMixin {
    // Constructor return assigns the player's handler before the first Packet1Login is sent.
    @Inject(
        method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/INetworkManager;Lnet/minecraft/ServerPlayer;)V",
        at = @At("RETURN"))
    private void betterquesting$onNetworkBound(
        MinecraftServer server,
        INetworkManager networkManager,
        ServerPlayer player,
        CallbackInfo ci
    ) {
        LoginSyncServerWiring.onNetworkBound(server, (NetServerHandler) (Object) this);
    }
}
