package com.github.postyizhan.betterquesting.platform.fml.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicInteger;
import moddedmite.rustedironcore.network.Packet;
import org.junit.jupiter.api.Test;

class LoginSyncClientWiringTest {
    @Test
    void readerRegistrationPrecedesOneHelloSendForTheExactHandler() {
        List<String> lifecycle = new ArrayList<>();
        List<Packet> sent = new ArrayList<>();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger());
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner,
            () -> lifecycle.add("reader"),
            packet -> {
                lifecycle.add("send");
                sent.add(packet);
            });
        Object handler = new String("handler");
        Object equalButDifferentHandler = new String("handler");

        wiring.login(handler);
        wiring.login(handler);

        assertEquals(List.of("reader", "send"), lifecycle);
        assertEquals(1, sent.size());
        LoginSyncFrame hello = LoginSyncTransportPackets.extract(sent.get(0)).orElseThrow();
        assertEquals(LoginSyncFrame.Type.CLIENT_HELLO, hello.type());
        assertEquals(LoginSyncProtocol.CAPABILITIES, hello.hello().orElseThrow().capabilities());
        assertTrue(owner.current(equalButDifferentHandler).isEmpty());
        assertEquals(LoginSyncSession.State.HELLO_SENT,
            owner.current(handler).orElseThrow().state());
    }

    @Test
    void reconnectReplacesTheOldSessionAndStaleCallbacksCannotTouchTheNewOne() {
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger registrations = new AtomicInteger();
        List<Packet> sent = new ArrayList<>();
        LoginSyncConnectionOwner owner = owner(clears);
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, registrations::incrementAndGet, sent::add);
        Object oldHandler = new Object();
        Object newHandler = new Object();
        wiring.login(oldHandler);
        LoginSyncSession oldSession = owner.current(oldHandler).orElseThrow();
        LoginSyncFrame oldHello = LoginSyncTransportPackets.extract(sent.get(0)).orElseThrow();

        wiring.login(newHandler);
        LoginSyncSession newSession = owner.current(newHandler).orElseThrow();
        LoginSyncFrame newHello = LoginSyncTransportPackets.extract(sent.get(1)).orElseThrow();

        assertTrue(oldSession.isClosed());
        assertEquals(1, clears.get());
        assertFalse(newSession.isClosed());
        wiring.terminal(oldHandler);
        wiring.receive(oldHandler, serverHello(oldHello));
        assertFalse(newSession.isClosed());
        assertEquals(LoginSyncSession.State.HELLO_SENT, newSession.state());

        wiring.receive(newHandler, serverHello(newHello));
        assertEquals(LoginSyncSession.State.READY, newSession.state());
        assertEquals(1, registrations.get());
        assertEquals(1, owner.size());
    }

    @Test
    void malformedWrongStaleAndUnboundInputCannotAdvanceTheActiveSession() {
        List<Packet> sent = new ArrayList<>();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger());
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, sent::add);
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncSession session = owner.current(handler).orElseThrow();
        LoginSyncFrame hello = LoginSyncTransportPackets.extract(sent.get(0)).orElseThrow();

        wiring.receive(handler, null);
        wiring.receive(handler, LoginSyncFrame.clientHello(hello.hello().orElseThrow()));
        wiring.receive(new Object(), serverHello(hello));

        assertEquals(LoginSyncSession.State.HELLO_SENT, session.state());
        assertEquals(1, owner.size());
        wiring.terminal(handler);
        wiring.receive(handler, serverHello(hello));
        assertTrue(session.isClosed());
        assertEquals(0, owner.size());
    }

    @Test
    void incompatibleServerHelloIsTerminalAndRemovesReceiveOwnership() {
        AtomicInteger clears = new AtomicInteger();
        List<Packet> sent = new ArrayList<>();
        LoginSyncConnectionOwner owner = owner(clears);
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, sent::add);
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncSession session = owner.current(handler).orElseThrow();
        LoginSyncFrame hello = LoginSyncTransportPackets.extract(sent.get(0)).orElseThrow();
        HandshakeCapabilities incompatible = new HandshakeCapabilities(2, 1, 0L, 0L);

        wiring.receive(handler, LoginSyncFrame.serverHello(new HandshakeHello(
            hello.connectionToken(), incompatible)));

        assertTrue(session.isClosed());
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
        wiring.receive(handler, serverHello(hello));
        assertEquals(0, owner.size());
    }

    @Test
    void registrationFailureCleansStaleBindingAndRetriesUntilOneSuccess() {
        AtomicInteger registrationAttempts = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner registrationOwner = owner(clears);
        LoginSyncClientWiring registrationFailure = new LoginSyncClientWiring(
            registrationOwner,
            () -> {
                if (registrationAttempts.incrementAndGet() == 1) {
                    throw new AssertionError("registration failed");
                }
            },
            packet -> sends.incrementAndGet());
        Object handler = new Object();
        LoginSyncSession stale = registrationOwner.bind(handler);

        assertDoesNotThrow(() -> registrationFailure.login(handler));
        assertEquals(1, registrationAttempts.get());
        assertEquals(0, sends.get());
        assertTrue(stale.isClosed());
        assertEquals(1, clears.get());
        assertEquals(0, registrationOwner.size());

        assertDoesNotThrow(() -> registrationFailure.login(handler));
        assertDoesNotThrow(() -> registrationFailure.login(handler));
        assertEquals(2, registrationAttempts.get());
        assertEquals(1, sends.get());
        assertEquals(1, registrationOwner.size());
    }

    @Test
    void sendFailureFailsClosedWithoutEscapingLogin() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner sendOwner = owner(clears);
        LoginSyncClientWiring sendFailure = new LoginSyncClientWiring(
            sendOwner, () -> { }, packet -> {
                throw new IllegalStateException("send failed");
            });
        Object handler = new Object();

        assertDoesNotThrow(() -> sendFailure.login(handler));
        assertEquals(1, clears.get());
        assertEquals(0, sendOwner.size());
    }

    @Test
    void allTerminalPathsShareIdempotentFailureIsolatedTeardown() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                ignored -> { },
                () -> {
                    clears.incrementAndGet();
                    throw new AssertionError("clear failed");
                }));
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, packet -> { });
        Object handler = new Object();
        wiring.login(handler);

        assertDoesNotThrow(() -> wiring.terminal(handler));
        assertDoesNotThrow(() -> wiring.terminal(handler));
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
    }

    private static LoginSyncConnectionOwner owner(AtomicInteger clears) {
        return new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                ignored -> { },
                clears::incrementAndGet));
    }

    private static LoginSyncFrame serverHello(LoginSyncFrame clientHello) {
        LoginSyncSession server = new LoginSyncSession(
            LoginSyncSession.Role.SERVER,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS);
        return server.receive(clientHello).response().orElseThrow();
    }
}
