package com.github.postyizhan.betterquesting.platform.fml.mixin.client;

import com.github.postyizhan.betterquesting.platform.fml.client.LoginSyncClientWiring;
import net.minecraft.NetClientHandler;
import net.minecraft.Packet;
import net.minecraft.Packet1Login;
import net.minecraft.Packet255KickDisconnect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetClientHandler.class)
public abstract class NetClientHandlerLoginSyncMixin {
    @Inject(
        method = "handleLogin(Lnet/minecraft/Packet1Login;)V",
        at = @At("RETURN"))
    private void betterquesting$onLogin(Packet1Login packet, CallbackInfo ci) {
        LoginSyncClientWiring.onLogin((NetClientHandler) (Object) this);
    }

    @Inject(
        method = "quitWithPacket(Lnet/minecraft/Packet;)V",
        at = @At("HEAD"))
    private void betterquesting$onQuit(Packet packet, CallbackInfo ci) {
        terminate();
    }

    @Inject(
        method = "handleKickDisconnect(Lnet/minecraft/Packet255KickDisconnect;)V",
        at = @At("HEAD"))
    private void betterquesting$onKick(Packet255KickDisconnect packet, CallbackInfo ci) {
        terminate();
    }

    @Inject(
        method = "handleErrorMessage(Ljava/lang/String;[Ljava/lang/Object;)V",
        at = @At("HEAD"))
    private void betterquesting$onNetworkError(
        String message,
        Object[] arguments,
        CallbackInfo ci
    ) {
        terminate();
    }

    @Inject(method = "disconnect()V", at = @At("HEAD"))
    private void betterquesting$onDisconnect(CallbackInfo ci) {
        terminate();
    }

    @Inject(method = "cleanup()V", at = @At("HEAD"))
    private void betterquesting$onCleanup(CallbackInfo ci) {
        terminate();
    }

    private void terminate() {
        LoginSyncClientWiring.onTerminal((NetClientHandler) (Object) this);
    }
}
