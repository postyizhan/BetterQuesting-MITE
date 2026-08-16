package com.github.postyizhan.betterquesting.platform.fml.client;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncConnectionOwner;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncFrame;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncProtocol;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncSession;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncTransportPackets;
import java.util.Objects;
import moddedmite.rustedironcore.network.Network;
import moddedmite.rustedironcore.network.Packet;
import net.minecraft.EntityClientPlayerMP;
import net.minecraft.EntityPlayer;
import net.minecraft.NetClientHandler;

public final class LoginSyncClientWiring {
    @FunctionalInterface
    interface Sender {
        void send(Packet packet);
    }

    private static final LoginSyncClientWiring PRODUCTION = new LoginSyncClientWiring(
        new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS),
        () -> LoginSyncTransportPackets.registerClient(LoginSyncClientWiring::receiveFromRic),
        Network::sendToServer);

    private final LoginSyncConnectionOwner owner;
    private final Runnable readerRegistration;
    private final Sender sender;
    private boolean readerRegistered;

    LoginSyncClientWiring(
        LoginSyncConnectionOwner owner,
        Runnable readerRegistration,
        Sender sender
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.readerRegistration = Objects.requireNonNull(readerRegistration, "readerRegistration");
        this.sender = Objects.requireNonNull(sender, "sender");
        if (owner.role() != LoginSyncSession.Role.CLIENT) {
            throw new IllegalArgumentException("client wiring requires a client connection owner");
        }
    }

    public static void onLogin(NetClientHandler handler) {
        PRODUCTION.login(handler);
    }

    public static void onTerminal(NetClientHandler handler) {
        PRODUCTION.terminal(handler);
    }

    private static void receiveFromRic(EntityPlayer player, LoginSyncFrame frame) {
        if (!(player instanceof EntityClientPlayerMP clientPlayer)) {
            return;
        }
        PRODUCTION.receive(clientPlayer.sendQueue, frame);
    }

    void login(Object handler) {
        if (handler == null) {
            return;
        }
        if (!ensureReaderRegistered()) {
            terminal(handler);
            return;
        }

        LoginSyncSession session = null;
        try {
            if (owner.current(handler).isPresent()) {
                return;
            }
            session = owner.bind(handler);
            Packet hello = LoginSyncTransportPackets.c2s(session.start());
            if (LoginSyncTransportPackets.isRejected(hello)) {
                throw new IllegalStateException("client login-sync hello was rejected by transport");
            }
            sender.send(hello);
        } catch (RuntimeException | Error failure) {
            unbindExpected(handler, session);
            logFailure("start a client login-sync connection", failure);
        }
    }

    void receive(Object handler, LoginSyncFrame frame) {
        if (handler == null || frame == null
            || frame.type() != LoginSyncFrame.Type.SERVER_HELLO) {
            return;
        }
        LoginSyncSession expected = null;
        try {
            expected = owner.current(handler).orElse(null);
            if (expected == null) {
                return;
            }
            owner.receive(handler, frame);
        } catch (RuntimeException | Error failure) {
            unbindExpected(handler, expected);
            logFailure("receive a client login-sync frame", failure);
        }
    }

    void terminal(Object handler) {
        if (handler == null) {
            return;
        }
        try {
            owner.unbind(handler);
        } catch (RuntimeException | Error failure) {
            logFailure("tear down a client login-sync connection", failure);
        }
    }

    private synchronized boolean ensureReaderRegistered() {
        if (readerRegistered) {
            return true;
        }
        try {
            readerRegistration.run();
            readerRegistered = true;
            return true;
        } catch (RuntimeException | Error failure) {
            logFailure("register the client login-sync reader", failure);
            return false;
        }
    }

    private void unbindExpected(Object handler, LoginSyncSession expected) {
        try {
            if (expected == null) {
                owner.unbind(handler);
            } else {
                owner.unbind(handler, expected);
            }
        } catch (RuntimeException | Error teardownFailure) {
            logFailure("fail closed after a client login-sync failure", teardownFailure);
        }
    }

    private static void logFailure(String operation, Throwable failure) {
        try {
            BetterQuestingMod.LOGGER.error("BetterQuesting could not {}", operation, failure);
        } catch (RuntimeException | Error ignored) {
        }
    }
}
