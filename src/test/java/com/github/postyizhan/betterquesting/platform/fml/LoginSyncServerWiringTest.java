package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncConnectionOwner;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncFrame;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncProtocol;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncSession;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncTransportPackets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import moddedmite.rustedironcore.network.Packet;
import org.junit.jupiter.api.Test;

class LoginSyncServerWiringTest {
    private static final UUID TOKEN =
        UUID.fromString("00000000-0000-0000-0000-000000000041");

    @Test
    void protocolVersionOneClaimsNoFeatures() {
        assertEquals(1, LoginSyncProtocol.CAPABILITIES.protocolVersion());
        assertEquals(1, LoginSyncProtocol.CAPABILITIES.dataFormatVersion());
        assertEquals(0L, LoginSyncProtocol.CAPABILITIES.supportedFeatureBits());
        assertEquals(0L, LoginSyncProtocol.CAPABILITIES.requiredFeatureBits());
        assertEquals(0L, LoginSyncProtocol.LIMITS.knownFeatureBits());
        assertEquals(0L, LoginSyncProtocol.LIMITS.reservedFeatureBits());
    }

    @Test
    void exactServerAndHandlerBindingRoutesOnlyItsServerHelloToThePlayer() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        List<Sent> sent = new ArrayList<>();
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner, (recipient, packet) -> sent.add(new Sent(recipient, packet)));
        Object server = new String("server");
        Object equalButDifferentServer = new String("server");
        Object handler = new Object();
        Object player = new Object();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, LoginSyncProtocol.CAPABILITIES, LoginSyncProtocol.LIMITS);

        wiring.bind(server, handler);
        LoginSyncSession serverSession = owner.current(server, handler).orElseThrow();
        assertTrue(owner.current(equalButDifferentServer, handler).isEmpty());
        LoginSyncFrame clientHello = client.start(TOKEN);

        wiring.receive(equalButDifferentServer, handler, player, clientHello);
        assertTrue(sent.isEmpty());
        wiring.receive(server, handler, player, clientHello);

        assertEquals(1, sent.size());
        assertSame(player, sent.get(0).recipient());
        LoginSyncFrame serverHello = LoginSyncTransportPackets.extract(sent.get(0).packet())
            .orElseThrow();
        assertEquals(LoginSyncFrame.Type.SERVER_HELLO, serverHello.type());
        assertEquals(LoginSyncSession.Outcome.ACCEPTED, client.receive(serverHello).outcome());
        assertEquals(LoginSyncSession.State.READY, client.state());
        assertEquals(LoginSyncSession.State.READY, serverSession.state());
    }

    @Test
    void malformedWrongStaleAndUnboundInputCannotAdvanceOrSend() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        List<Packet> sent = new ArrayList<>();
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner, (recipient, packet) -> sent.add(packet));
        Object server = new Object();
        Object handler = new Object();
        Object player = new Object();
        wiring.bind(server, handler);
        LoginSyncSession session = owner.current(server, handler).orElseThrow();
        LoginSyncFrame validHello = LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES));

        wiring.receive(server, handler, player, null);
        wiring.receive(server, handler, player, LoginSyncFrame.serverHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));
        wiring.receive(new Object(), handler, player, validHello);
        wiring.receive(server, new Object(), player, validHello);

        assertEquals(LoginSyncSession.State.NEW, session.state());
        assertTrue(sent.isEmpty());
        wiring.unbind(server, handler);
        wiring.receive(server, handler, player, validHello);
        assertTrue(sent.isEmpty());
        assertEquals(0, owner.size());
    }

    @Test
    void incompatibleReceiveIsTerminalAndRemovesTheBinding() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER, clears);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(owner, (recipient, packet) -> { });
        Object server = new Object();
        Object handler = new Object();
        wiring.bind(server, handler);
        LoginSyncSession session = owner.current(server, handler).orElseThrow();
        HandshakeCapabilities incompatible = new HandshakeCapabilities(2, 1, 0L, 0L);

        wiring.receive(server, handler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, incompatible)));

        assertTrue(session.isClosed());
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
        assertTrue(owner.current(server, handler).isEmpty());
    }

    @Test
    void sendFailureClosesOnlyTheSessionWhoseResponseFailed() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER, clears);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(owner, (recipient, packet) -> {
            throw new AssertionError("send failed");
        });
        Object server = new Object();
        Object handler = new Object();
        wiring.bind(server, handler);
        LoginSyncSession failed = owner.current(server, handler).orElseThrow();

        assertDoesNotThrow(() -> wiring.receive(server, handler, new Object(),
            LoginSyncFrame.clientHello(new HandshakeHello(
                TOKEN, LoginSyncProtocol.CAPABILITIES))));

        assertTrue(failed.isClosed());
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());

        wiring.bind(server, handler);
        LoginSyncSession replacement = owner.current(server, handler).orElseThrow();
        wiring.unbind(server, new Object());
        assertFalse(replacement.isClosed());
    }

    @Test
    void staleSendFailureCannotCloseAReboundSession() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER, clears);
        Object server = new Object();
        Object handler = new Object();
        AtomicReference<LoginSyncSession> rebound = new AtomicReference<>();
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(owner, (recipient, packet) -> {
            rebound.set(owner.rebind(server, handler));
            throw new AssertionError("stale send failed");
        });
        wiring.bind(server, handler);
        LoginSyncSession original = owner.current(server, handler).orElseThrow();

        assertDoesNotThrow(() -> wiring.receive(server, handler, new Object(),
            LoginSyncFrame.clientHello(new HandshakeHello(
                TOKEN, LoginSyncProtocol.CAPABILITIES))));

        assertTrue(original.isClosed());
        assertEquals(1, clears.get());
        assertSame(rebound.get(), owner.current(server, handler).orElseThrow());
        assertFalse(rebound.get().isClosed());
        assertEquals(1, owner.size());
    }

    @Test
    void logoutAndStopCloseAllAreOrderIndependentAndIsolateTeardownFailures() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, handler) -> new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                ignored -> { },
                () -> {
                    clears.incrementAndGet();
                    throw new AssertionError("clear failed");
                }));
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(owner, (recipient, packet) -> { });
        Object server = new Object();
        Object firstHandler = new Object();
        Object secondHandler = new Object();
        wiring.bind(server, firstHandler);
        wiring.bind(server, secondHandler);

        assertDoesNotThrow(() -> wiring.unbind(server, firstHandler));
        assertDoesNotThrow(() -> wiring.closeAll(server));
        assertDoesNotThrow(() -> wiring.unbind(server, secondHandler));
        assertEquals(2, clears.get());
        assertEquals(0, owner.size());

        Object thirdHandler = new Object();
        wiring.bind(server, thirdHandler);
        LoginSyncSession active = owner.current(server, thirdHandler).orElseThrow();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS);
        wiring.receive(server, thirdHandler, new Object(), client.start(TOKEN));
        assertEquals(LoginSyncSession.State.READY, active.state());
        assertDoesNotThrow(() -> wiring.closeAll(server));
        assertDoesNotThrow(() -> wiring.unbind(server, thirdHandler));
        assertTrue(active.isClosed());
        assertEquals(3, clears.get());
        assertEquals(0, owner.size());
    }

    private static LoginSyncConnectionOwner owner(LoginSyncSession.Role role) {
        return owner(role, new AtomicInteger());
    }

    private static LoginSyncConnectionOwner owner(
        LoginSyncSession.Role role,
        AtomicInteger clears
    ) {
        return new LoginSyncConnectionOwner(
            role,
            (sessionRole, handler) -> new LoginSyncSession(
                sessionRole,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                ignored -> { },
                clears::incrementAndGet));
    }

    private record Sent(Object recipient, Packet packet) {
    }
}
