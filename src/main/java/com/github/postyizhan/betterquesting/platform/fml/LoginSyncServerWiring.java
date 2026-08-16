package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncConnectionOwner;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncFrame;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncProtocol;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncSession;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncTransportPackets;
import java.util.Optional;
import moddedmite.rustedironcore.network.Network;
import moddedmite.rustedironcore.network.Packet;
import net.minecraft.EntityPlayer;
import net.minecraft.NetServerHandler;
import net.minecraft.ServerPlayer;
import net.minecraft.server.MinecraftServer;

public final class LoginSyncServerWiring {
    @FunctionalInterface
    interface Sender {
        void send(Object recipient, Packet packet);
    }

    private static final LoginSyncServerWiring PRODUCTION = new LoginSyncServerWiring(
        new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS),
        (recipient, packet) -> Network.sendToClient((ServerPlayer) recipient, packet));

    private final LoginSyncConnectionOwner owner;
    private final Sender sender;

    LoginSyncServerWiring(LoginSyncConnectionOwner owner, Sender sender) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
        if (owner.role() != LoginSyncSession.Role.SERVER) {
            throw new IllegalArgumentException("server wiring requires a server connection owner");
        }
    }

    public static void registerReader() {
        try {
            LoginSyncTransportPackets.registerServer(LoginSyncServerWiring::receiveFromRic);
        } catch (RuntimeException | Error failure) {
            logFailure("register the server login-sync reader", failure);
        }
    }

    public static void onNetworkBound(MinecraftServer server, NetServerHandler handler) {
        if (server == null || handler == null) {
            return;
        }
        PRODUCTION.bind(server, handler);
    }

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PRODUCTION.unbind(player.mcServer, player.playerNetServerHandler);
    }

    public static void onServerStopping(MinecraftServer server) {
        PRODUCTION.closeAll(server);
    }

    private static void receiveFromRic(EntityPlayer player, LoginSyncFrame frame) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        PRODUCTION.receive(
            serverPlayer.mcServer,
            serverPlayer.playerNetServerHandler,
            serverPlayer,
            frame);
    }

    void bind(Object serverOwner, Object handler) {
        if (serverOwner == null || handler == null) {
            return;
        }
        try {
            owner.bind(serverOwner, handler);
        } catch (RuntimeException | Error failure) {
            logFailure("bind a server login-sync connection", failure);
        }
    }

    void receive(Object serverOwner, Object handler, Object recipient, LoginSyncFrame frame) {
        if (serverOwner == null || handler == null || recipient == null || frame == null
            || frame.type() != LoginSyncFrame.Type.CLIENT_HELLO) {
            return;
        }

        LoginSyncSession expected = null;
        try {
            expected = owner.current(serverOwner, handler).orElse(null);
            if (expected == null) {
                return;
            }
            Optional<LoginSyncSession.ReceiveResult> received = owner.receive(handler, frame);
            if (received.isEmpty() || received.orElseThrow().response().isEmpty()) {
                return;
            }
            if (owner.current(serverOwner, handler).orElse(null) != expected) {
                return;
            }
            Packet response = LoginSyncTransportPackets.s2c(
                received.orElseThrow().response().orElseThrow());
            if (LoginSyncTransportPackets.isRejected(response)) {
                throw new IllegalStateException("server login-sync response was rejected by transport");
            }
            sender.send(recipient, response);
        } catch (RuntimeException | Error failure) {
            unbindExpected(serverOwner, handler, expected);
            logFailure("receive or respond to a server login-sync frame", failure);
        }
    }

    void unbind(Object serverOwner, Object handler) {
        if (serverOwner == null || handler == null) {
            return;
        }
        try {
            owner.unbind(serverOwner, handler);
        } catch (RuntimeException | Error failure) {
            logFailure("tear down a server login-sync connection", failure);
        }
    }

    void closeAll(Object serverOwner) {
        if (serverOwner == null) {
            return;
        }
        try {
            owner.closeAll(serverOwner);
        } catch (RuntimeException | Error failure) {
            logFailure("close server login-sync connections", failure);
        }
    }

    private void unbindExpected(Object serverOwner, Object handler, LoginSyncSession expected) {
        try {
            if (expected == null) {
                owner.unbind(serverOwner, handler);
            } else {
                owner.unbind(serverOwner, handler, expected);
            }
        } catch (RuntimeException | Error teardownFailure) {
            logFailure("fail closed after a server login-sync failure", teardownFailure);
        }
    }

    private static void logFailure(String operation, Throwable failure) {
        try {
            BetterQuestingMod.LOGGER.error("BetterQuesting could not {}", operation, failure);
        } catch (RuntimeException | Error ignored) {
        }
    }
}
