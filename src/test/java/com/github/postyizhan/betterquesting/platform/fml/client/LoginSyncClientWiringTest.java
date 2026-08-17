package com.github.postyizhan.betterquesting.platform.fml.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.client.state.ClientLifeState;
import com.github.postyizhan.betterquesting.client.state.ClientPlayerNameState;
import com.github.postyizhan.betterquesting.client.state.ClientQuestSettingsState;
import com.github.postyizhan.betterquesting.network.fragment.BoundedFragmenter;
import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragment;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragmentCodec;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.sync.LoginBulkPayload;
import com.github.postyizhan.betterquesting.network.sync.LoginBulkPayloadCodec;
import com.github.postyizhan.betterquesting.network.sync.LoginLifeSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginNameSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginSettingsSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncBulkSyncOrchestrator;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncConnectionOwner;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncFrame;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncProtocol;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncSession;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncTransportPackets;
import com.github.postyizhan.betterquesting.storage.LifeDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import moddedmite.rustedironcore.network.Packet;
import org.junit.jupiter.api.Test;

public class LoginSyncClientWiringTest {
    private static final UUID AUTHORITATIVE_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000051");

    @Test
    void productionStatePublishesOnlyAfterHandshakeAndTracksTheConnectionLease() {
        ClientQuestSettingsState state = new ClientQuestSettingsState();
        List<Packet> sent = new ArrayList<>();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(state, () -> { }, sent::add);
        Object handler = new Object();
        Object oldWorld = new Object();
        Object newWorld = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = LoginSyncTransportPackets.extract(sent.get(0)).orElseThrow();
        LoginSettingsSnapshot published = snapshot("client-state");

        wiring.receive(handler, LoginSyncFrame.settings(hello.connectionToken(), published));
        assertTrue(state.current().isEmpty());

        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(published));
        wiring.receive(handler, server.sendSettings(published));

        assertEquals(published, state.current().orElseThrow());
        wiring.worldChanged(oldWorld, newWorld);
        assertEquals(published, state.current().orElseThrow());
        wiring.terminal(handler);
        assertTrue(state.current().isEmpty());
    }

    @Test
    void productionStateReconnectPublishesReplacementAndIgnoresOldTeardown() {
        ClientQuestSettingsState state = new ClientQuestSettingsState();
        List<Packet> sent = new ArrayList<>();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(state, () -> { }, sent::add);
        Object oldHandler = new Object();
        Object newHandler = new Object();

        wiring.login(oldHandler);
        LoginSyncFrame oldHello = LoginSyncTransportPackets.extract(sent.get(0)).orElseThrow();
        LoginSyncSession oldServer = server();
        wiring.receive(oldHandler, oldServer.receive(oldHello).response().orElseThrow());
        wiring.receive(oldHandler, oldServer.sendSettings(snapshot("old-state")));
        assertEquals(snapshot("old-state"), state.current().orElseThrow());

        wiring.login(newHandler);
        assertTrue(state.current().isEmpty());
        LoginSyncFrame newHello = LoginSyncTransportPackets.extract(sent.get(1)).orElseThrow();
        LoginSyncSession newServer = server();
        wiring.receive(newHandler, newServer.receive(newHello).response().orElseThrow());
        LoginSettingsSnapshot replacement = snapshot("new-state");
        wiring.receive(newHandler, newServer.sendSettings(replacement));

        wiring.terminal(oldHandler);
        wiring.receive(oldHandler, oldServer.sendSettings(snapshot("delayed-old")));
        assertEquals(replacement, state.current().orElseThrow());
        wiring.worldUnload();
        assertTrue(state.current().isEmpty());
    }

    @Test
    void typedLifePublishesOnlyAfterSettingsAndFragmentedReassemblyWithoutDatabaseWrites() {
        ClientQuestSettingsState settings = new ClientQuestSettingsState();
        ClientLifeState life = new ClientLifeState();
        FragmentAssemblyLimits limits = lifeLimits();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            settings, life, () -> { }, packet -> { }, limits);
        Object handler = new Object();
        int authoritativeLives = LifeDatabase.INSTANCE.getLives(AUTHORITATIVE_ID);
        try {
            wiring.login(handler);
            LoginSyncFrame prematureHello = wiring.outboundHello(handler).orElseThrow();
            sendBulk(wiring, handler, prematureHello.connectionToken(), limits,
                70L, lifeEnvelope(99), 0L);
            assertTrue(settings.current().isEmpty());
            assertTrue(life.current().isEmpty());

            wiring.login(handler);
            LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
            LoginSyncSession server = server();
            wiring.receive(handler, server.receive(hello).response().orElseThrow());
            wiring.receive(handler, server.sendSettings(snapshot("typed-life")));
            byte[] encoded = lifeEnvelope(-17);
            List<QuestingFragment> fragments = new BoundedFragmenter(limits).split(71L, encoded);
            QuestingFragmentCodec codec = new QuestingFragmentCodec(limits);

            long nowNanos = 0L;
            for (int index = fragments.size() - 1; index > 0; index--) {
                wiring.receive(handler, LoginSyncFrame.bulkFragment(
                    hello.connectionToken(), codec.encode(fragments.get(index))), nowNanos++);
                assertTrue(life.current().isEmpty());
            }
            wiring.receive(handler, LoginSyncFrame.bulkFragment(
                hello.connectionToken(), codec.encode(fragments.get(0))), nowNanos);

            assertEquals(-17, life.current().orElseThrow().lives());
            assertEquals(snapshot("typed-life"), settings.current().orElseThrow());
            assertEquals(authoritativeLives, LifeDatabase.INSTANCE.getLives(AUTHORITATIVE_ID));
        } finally {
            wiring.worldUnload();
            LifeDatabase.INSTANCE.reset();
        }
    }

    @Test
    void typedNamePublishesOnlyAfterCompleteValidationAndAllCleanupPathsClearItsLease() {
        ClientQuestSettingsState settings = new ClientQuestSettingsState();
        ClientLifeState life = new ClientLifeState();
        ClientPlayerNameState names = new ClientPlayerNameState();
        FragmentAssemblyLimits limits = nameLimits();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            settings, life, names, () -> { }, packet -> { }, limits);
        Object handler = new Object();
        LoginSyncFrame hello = readyTypedClient(wiring, handler, "typed-name");
        LoginNameSnapshot expected = new LoginNameSnapshot(AUTHORITATIVE_ID, "Alice");
        List<QuestingFragment> fragments = new BoundedFragmenter(limits).split(
            75L, nameEnvelope(expected));
        QuestingFragmentCodec codec = new QuestingFragmentCodec(limits);

        long nowNanos = 0L;
        for (int index = fragments.size() - 1; index > 0; index--) {
            wiring.receive(handler, LoginSyncFrame.bulkFragment(
                hello.connectionToken(), codec.encode(fragments.get(index))), nowNanos++);
            assertTrue(names.current().isEmpty());
        }
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            hello.connectionToken(), codec.encode(fragments.get(0))), nowNanos);

        assertEquals(expected, names.current().orElseThrow());
        wiring.terminal(handler);
        assertTrue(names.current().isEmpty());

        LoginSyncFrame unloadHello = readyTypedClient(wiring, handler, "name-unload");
        sendBulk(wiring, handler, unloadHello.connectionToken(), limits,
            76L, nameEnvelope(expected), 20L);
        assertEquals(expected, names.current().orElseThrow());
        wiring.worldUnload();
        assertTrue(names.current().isEmpty());
    }

    @Test
    void nameDuplicatesAreIdempotentWhileConflictAndDelayedOldFragmentsFailClosed() {
        ClientQuestSettingsState settings = new ClientQuestSettingsState();
        ClientLifeState life = new ClientLifeState();
        ClientPlayerNameState names = new ClientPlayerNameState();
        FragmentAssemblyLimits limits = nameLimits();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            settings, life, names, () -> { }, packet -> { }, limits);
        Object oldHandler = new Object();
        LoginSyncFrame oldHello = readyTypedClient(wiring, oldHandler, "old-name");
        LoginNameSnapshot first = new LoginNameSnapshot(AUTHORITATIVE_ID, "Alice");
        sendBulk(wiring, oldHandler, oldHello.connectionToken(), limits,
            77L, nameEnvelope(first), 0L);
        LoginNameSnapshot firstPublished = names.current().orElseThrow();
        sendBulk(wiring, oldHandler, oldHello.connectionToken(), limits,
            78L, nameEnvelope(first), 20L);
        assertSame(firstPublished, names.current().orElseThrow());

        Object replacementHandler = new Object();
        LoginSyncFrame replacementHello = readyTypedClient(
            wiring, replacementHandler, "replacement-name");
        assertTrue(names.current().isEmpty());
        LoginNameSnapshot replacement = new LoginNameSnapshot(AUTHORITATIVE_ID, "ALICE");
        sendBulk(wiring, replacementHandler, replacementHello.connectionToken(), limits,
            79L, nameEnvelope(replacement), 40L);
        sendBulk(wiring, oldHandler, oldHello.connectionToken(), limits,
            80L, nameEnvelope(new LoginNameSnapshot(AUTHORITATIVE_ID, "Delayed")), 60L);
        wiring.terminal(oldHandler);
        assertEquals(replacement, names.current().orElseThrow());

        sendBulk(wiring, replacementHandler, replacementHello.connectionToken(), limits,
            81L, nameEnvelope(new LoginNameSnapshot(AUTHORITATIVE_ID, "Conflict")), 80L);
        assertTrue(wiring.currentOrchestrator(replacementHandler).isEmpty());
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());
        assertTrue(names.current().isEmpty());
    }

    @Test
    void exactLifeDuplicateIsIdempotentWhileConflictAndMalformedPayloadFailClosed() {
        ClientQuestSettingsState settings = new ClientQuestSettingsState();
        ClientLifeState life = new ClientLifeState();
        FragmentAssemblyLimits limits = lifeLimits();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            settings, life, () -> { }, packet -> { }, limits);
        Object handler = new Object();
        LoginSyncFrame hello = readyTypedClient(wiring, handler, "duplicates");

        sendBulk(wiring, handler, hello.connectionToken(), limits, 81L, lifeEnvelope(4), 0L);
        LoginLifeSnapshot first = life.current().orElseThrow();
        sendBulk(wiring, handler, hello.connectionToken(), limits, 82L, lifeEnvelope(4), 20L);

        assertSame(first, life.current().orElseThrow());
        assertTrue(wiring.currentOrchestrator(handler).isPresent());

        sendBulk(wiring, handler, hello.connectionToken(), limits, 83L, lifeEnvelope(5), 40L);
        assertTrue(wiring.currentOrchestrator(handler).isEmpty());
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());

        LoginSyncFrame replacement = readyTypedClient(wiring, handler, "malformed");
        sendBulk(wiring, handler, replacement.connectionToken(), limits,
            84L, lifeEnvelope(3), 60L);
        assertEquals(3, life.current().orElseThrow().lives());
        sendBulk(wiring, handler, replacement.connectionToken(), limits, 87L,
            new byte[] {1, 2, 3, 4}, 80L);
        assertTrue(wiring.currentOrchestrator(handler).isEmpty());
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());
    }

    @Test
    void replayOverflowAfterLifePublicationClearsBothStatesAndAssembly() {
        byte[] envelope = lifeEnvelope(11);
        FragmentAssemblyLimits sizingLimits = lifeLimits();
        QuestingFragmentCodec sizingCodec = new QuestingFragmentCodec(sizingLimits);
        long lifeReplayBytes = new BoundedFragmenter(sizingLimits).split(85L, envelope)
            .stream().mapToLong(fragment -> sizingCodec.encode(fragment).length).sum();
        long replayBound = lifeReplayBytes
            + 7L * (QuestingFragmentCodec.HEADER_BYTES + 1L);
        FragmentAssemblyLimits limits = lifeLimits(replayBound);
        ClientQuestSettingsState settings = new ClientQuestSettingsState();
        ClientLifeState life = new ClientLifeState();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            settings, life, () -> { }, packet -> { }, limits);
        Object handler = new Object();
        LoginSyncFrame hello = readyTypedClient(wiring, handler, "overflow-life");
        sendBulk(wiring, handler, hello.connectionToken(), limits,
            85L, envelope, 0L);
        assertEquals(11, life.current().orElseThrow().lives());

        QuestingFragmentCodec codec = new QuestingFragmentCodec(limits);
        for (int index = 0; index < 7; index++) {
            wiring.receive(handler, LoginSyncFrame.bulkFragment(
                hello.connectionToken(), codec.encode(new QuestingFragment(
                    86L, 8, index, 8, new byte[] {(byte) index}))), 20L + index);
        }
        assertEquals(replayBound, wiring.retainedReplayBytes(handler));
        LoginSyncBulkSyncOrchestrator active = wiring.currentOrchestrator(handler).orElseThrow();
        assertTrue(active.hasActiveAssemblies());
        wiring.receive(handler, LoginSyncFrame.bulkFragment(hello.connectionToken(), codec.encode(
            new QuestingFragment(86L, 8, 7, 8, new byte[] {7}))), 27L);

        assertTrue(wiring.currentOrchestrator(handler).isEmpty());
        assertEquals(0L, wiring.retainedReplayBytes(handler));
        assertFalse(active.hasActiveAssemblies());
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());
    }

    @Test
    void lifeReconnectStaleTeardownTerminalAndWorldTransitionsFollowTheActiveLease() {
        ClientQuestSettingsState settings = new ClientQuestSettingsState();
        ClientLifeState life = new ClientLifeState();
        FragmentAssemblyLimits limits = lifeLimits();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            settings, life, () -> { }, packet -> { }, limits);
        Object oldHandler = new Object();
        LoginSyncFrame oldHello = readyTypedClient(wiring, oldHandler, "old");
        sendBulk(wiring, oldHandler, oldHello.connectionToken(), limits,
            91L, lifeEnvelope(2), 0L);

        Object newHandler = new Object();
        wiring.login(newHandler);
        assertTrue(life.current().isEmpty());
        LoginSyncFrame newHello = wiring.outboundHello(newHandler).orElseThrow();
        LoginSyncSession newServer = server();
        wiring.receive(newHandler, newServer.receive(newHello).response().orElseThrow());
        wiring.receive(newHandler, newServer.sendSettings(snapshot("new")));
        sendBulk(wiring, newHandler, newHello.connectionToken(), limits,
            92L, lifeEnvelope(Integer.MAX_VALUE), 20L);

        wiring.terminal(oldHandler);
        sendBulk(wiring, oldHandler, oldHello.connectionToken(), limits,
            93L, lifeEnvelope(-99), 40L);
        assertEquals(Integer.MAX_VALUE, life.current().orElseThrow().lives());

        Object oldWorld = new Object();
        Object currentWorld = new Object();
        wiring.worldChanged(oldWorld, currentWorld);
        assertEquals(Integer.MAX_VALUE, life.current().orElseThrow().lives());
        wiring.worldChanged(oldWorld, null);
        assertEquals(Integer.MAX_VALUE, life.current().orElseThrow().lives());

        sendBulk(wiring, newHandler, UUID.randomUUID(), limits,
            94L, lifeEnvelope(8), 60L);
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());

        LoginSyncFrame finalHello = readyTypedClient(wiring, newHandler, "final");
        sendBulk(wiring, newHandler, finalHello.connectionToken(), limits,
            95L, lifeEnvelope(6), 80L);
        assertEquals(6, life.current().orElseThrow().lives());
        wiring.terminal(newHandler);
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());

        LoginSyncFrame unloadHello = readyTypedClient(wiring, newHandler, "unload");
        sendBulk(wiring, newHandler, unloadHello.connectionToken(), limits,
            96L, lifeEnvelope(7), 100L);
        assertEquals(7, life.current().orElseThrow().lives());
        wiring.worldChanged(currentWorld, null);
        wiring.worldChanged(currentWorld, null);
        wiring.terminal(newHandler);
        assertTrue(settings.current().isEmpty());
        assertTrue(life.current().isEmpty());
    }

    @Test
    void productionStateFailureAndInvalidInputsPublishNothing() {
        ClientQuestSettingsState registrationState = new ClientQuestSettingsState();
        LoginSyncClientWiring registrationFailure = new LoginSyncClientWiring(
            registrationState,
            () -> {
                throw new IllegalStateException("registration failed");
            },
            packet -> { });
        assertDoesNotThrow(() -> registrationFailure.login(new Object()));
        assertTrue(registrationState.current().isEmpty());

        ClientQuestSettingsState sendState = new ClientQuestSettingsState();
        LoginSyncClientWiring sendFailure = new LoginSyncClientWiring(
            sendState, () -> { }, packet -> {
                throw new IllegalStateException("send failed");
            });
        assertDoesNotThrow(() -> sendFailure.login(new Object()));
        assertTrue(sendState.current().isEmpty());

        ClientQuestSettingsState inputState = new ClientQuestSettingsState();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            inputState, () -> { }, packet -> { });
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        wiring.receive(handler, LoginSyncFrame.settings(
            hello.connectionToken(), snapshot("out-of-order")));
        assertTrue(inputState.current().isEmpty());

        LoginSyncSession malformedServer = server();
        wiring.receive(handler, malformedServer.receive(hello).response().orElseThrow());
        wiring.receive(handler, new LoginSyncFrame(
            LoginSyncFrame.Direction.SERVER_TO_CLIENT,
            LoginSyncFrame.Type.SETTINGS,
            hello.connectionToken(),
            new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> new LoginSyncFrame(
            LoginSyncFrame.Direction.SERVER_TO_CLIENT,
            LoginSyncFrame.Type.SETTINGS,
            hello.connectionToken(),
            new byte[LoginSyncFrame.MAX_PAYLOAD_BYTES + 1]));
        assertTrue(inputState.current().isEmpty());

        wiring.receive(handler, LoginSyncFrame.settings(
            UUID.randomUUID(), snapshot("wrong-token")));
        assertTrue(inputState.current().isEmpty());

        wiring.login(handler);
        LoginSyncFrame failedHello = wiring.outboundHello(handler).orElseThrow();
        HandshakeCapabilities incompatible = new HandshakeCapabilities(2, 1, 0L, 0L);
        wiring.receive(handler, LoginSyncFrame.serverHello(new HandshakeHello(
            failedHello.connectionToken(), incompatible)));
        assertTrue(inputState.current().isEmpty());

        wiring.login(handler);
        LoginSyncFrame conflictHello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(conflictHello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot("first")));
        wiring.receive(handler, server.sendSettings(snapshot("conflict")));
        assertTrue(inputState.current().isEmpty());
    }

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

    @Test
    void onlyTheBoundTokenAfterHandshakeCanPublishBulkAndDuplicatesAreIdempotent() {
        AtomicInteger publications = new AtomicInteger();
        List<byte[]> published = new ArrayList<>();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger());
        FragmentAssemblyLimits limits = limits();
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner,
            () -> { },
            packet -> { },
            limits,
            payload -> {
                publications.incrementAndGet();
                published.add(payload.clone());
            });
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot("bulk")));
        QuestingFragmentCodec codec = new QuestingFragmentCodec(limits);
        QuestingFragment first = new QuestingFragment(51L, 4, 1, 2, new byte[] {3, 4});
        QuestingFragment second = new QuestingFragment(51L, 4, 0, 2, new byte[] {1, 2});

        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            UUID.randomUUID(), codec.encode(first)));
        assertEquals(0, publications.get());
        assertTrue(owner.current(handler).isEmpty());

        wiring.login(handler);
        LoginSyncFrame freshHello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession freshServer = server();
        wiring.receive(handler, freshServer.receive(freshHello).response().orElseThrow());
        wiring.receive(handler, freshServer.sendSettings(snapshot("bulk")));
        UUID token = freshHello.connectionToken();
        wiring.receive(handler, LoginSyncFrame.bulkFragment(token, codec.encode(second)), 0L);
        wiring.receive(handler, LoginSyncFrame.bulkFragment(token, codec.encode(second)), 1L);
        wiring.receive(handler, LoginSyncFrame.bulkFragment(token, codec.encode(first)), 2L);

        assertEquals(1, publications.get());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, published.get(0));
        wiring.receive(handler, LoginSyncFrame.bulkFragment(token, codec.encode(first)), 3L);
        assertTrue(owner.current(handler).isPresent());
        assertEquals(1, publications.get());
        wiring.receive(handler, LoginSyncFrame.bulkFragment(token, codec.encode(
            new QuestingFragment(51L, 4, 1, 2, new byte[] {3, 5}))), 4L);
        assertTrue(owner.current(handler).isEmpty());
        assertEquals(1, publications.get());
    }

    @Test
    void clientReplayBytesAcceptExactBoundRejectBoundaryPlusOneAndRelease() {
        long replayBound = 4L * QuestingFragmentCodec.HEADER_BYTES + 16L;
        FragmentAssemblyLimits replayLimits = new FragmentAssemblyLimits(
            6, 8, 4, 2, replayBound, 8, 10L);
        AtomicInteger publications = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger());
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, packet -> { }, replayLimits,
            payload -> publications.incrementAndGet());
        QuestingFragmentCodec codec = new QuestingFragmentCodec(replayLimits);
        Object handler = new Object();
        LoginSyncFrame hello = readyClient(wiring, handler, "bounded");
        byte[] first = codec.encode(
            new QuestingFragment(61L, 5, 0, 1, new byte[] {1, 2, 3, 4, 5}));
        byte[] second = codec.encode(
            new QuestingFragment(62L, 5, 0, 1, new byte[] {6, 7, 8, 9, 10}));
        byte[] third = codec.encode(
            new QuestingFragment(63L, 5, 0, 1, new byte[] {11, 12, 13, 14, 15}));
        byte[] partial = codec.encode(
            new QuestingFragment(64L, 2, 0, 2, new byte[] {16}));

        wiring.receive(handler, LoginSyncFrame.bulkFragment(hello.connectionToken(), first), 0L);
        wiring.receive(handler, LoginSyncFrame.bulkFragment(hello.connectionToken(), second), 1L);
        wiring.receive(handler, LoginSyncFrame.bulkFragment(hello.connectionToken(), third), 2L);
        wiring.receive(handler, LoginSyncFrame.bulkFragment(hello.connectionToken(), partial), 3L);

        assertTrue(owner.current(handler).isPresent());
        assertEquals(3, publications.get());
        assertEquals(replayBound, wiring.retainedReplayBytes(handler));
        assertEquals(replayBound,
            wiring.currentOrchestrator(handler).orElseThrow().reservedBytes());
        wiring.receive(handler, LoginSyncFrame.bulkFragment(hello.connectionToken(), first), 4L);
        assertTrue(owner.current(handler).isPresent());
        assertEquals(3, publications.get());
        assertEquals(replayBound, wiring.retainedReplayBytes(handler));
        assertEquals(replayBound,
            wiring.currentOrchestrator(handler).orElseThrow().reservedBytes());

        Object reboundHandler = new Object();
        LoginSyncFrame reboundHello = readyClient(wiring, reboundHandler, "rebound");
        assertEquals(0L, wiring.retainedReplayBytes(handler));
        assertEquals(0L, wiring.retainedReplayBytes(reboundHandler));
        wiring.receive(reboundHandler, LoginSyncFrame.bulkFragment(
            reboundHello.connectionToken(), partial), 5L);
        assertEquals(QuestingFragmentCodec.HEADER_BYTES + 1L,
            wiring.retainedReplayBytes(reboundHandler));
        wiring.terminal(reboundHandler);
        assertEquals(0L, wiring.retainedReplayBytes(reboundHandler));

        Object closeHandler = new Object();
        LoginSyncFrame closeHello = readyClient(wiring, closeHandler, "close");
        wiring.receive(closeHandler, LoginSyncFrame.bulkFragment(
            closeHello.connectionToken(), partial), 6L);
        assertEquals(QuestingFragmentCodec.HEADER_BYTES + 1L,
            wiring.retainedReplayBytes(closeHandler));
        wiring.worldUnload();
        assertEquals(0L, wiring.retainedReplayBytes(closeHandler));

        AtomicInteger overflowPublications = new AtomicInteger();
        LoginSyncConnectionOwner overflowOwner = owner(new AtomicInteger());
        LoginSyncClientWiring overflow = new LoginSyncClientWiring(
            overflowOwner, () -> { }, packet -> { }, replayLimits,
            payload -> overflowPublications.incrementAndGet());
        Object overflowHandler = new Object();
        LoginSyncFrame overflowHello = readyClient(overflow, overflowHandler, "overflow");
        byte[] boundaryPlusOne = codec.encode(
            new QuestingFragment(65L, 6, 0, 1, new byte[] {1, 2, 3, 4, 5, 6}));
        assertEquals(replayBound + 1L,
            (long) first.length + second.length + boundaryPlusOne.length + partial.length);
        overflow.receive(overflowHandler, LoginSyncFrame.bulkFragment(
            overflowHello.connectionToken(), first), 0L);
        overflow.receive(overflowHandler, LoginSyncFrame.bulkFragment(
            overflowHello.connectionToken(), second), 1L);
        overflow.receive(overflowHandler, LoginSyncFrame.bulkFragment(
            overflowHello.connectionToken(), boundaryPlusOne), 2L);
        overflow.receive(overflowHandler, LoginSyncFrame.bulkFragment(
            overflowHello.connectionToken(), partial), 3L);

        assertEquals(3, overflowPublications.get());
        assertTrue(overflowOwner.current(overflowHandler).isEmpty());
        assertEquals(0L, overflow.retainedReplayBytes(overflowHandler));
    }

    @Test
    void malformedPreHandshakeAndConflictingFragmentsFailClosed() {
        AtomicInteger publications = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger());
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, packet -> { }, limits(),
            payload -> publications.incrementAndGet());
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        QuestingFragmentCodec codec = new QuestingFragmentCodec(limits());

        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            hello.connectionToken(), codec.encode(
                new QuestingFragment(1L, 1, 0, 1, new byte[] {1}))));
        assertTrue(owner.current(handler).isEmpty());

        wiring.login(handler);
        LoginSyncFrame malformedHello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession malformedServer = server();
        wiring.receive(handler, malformedServer.receive(malformedHello).response().orElseThrow());
        wiring.receive(handler, malformedServer.sendSettings(snapshot("malformed")));
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            malformedHello.connectionToken(), new byte[] {9, 8, 7}));
        assertTrue(owner.current(handler).isEmpty());

        wiring.login(handler);
        LoginSyncFrame conflictHello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession conflictServer = server();
        wiring.receive(handler, conflictServer.receive(conflictHello).response().orElseThrow());
        wiring.receive(handler, conflictServer.sendSettings(snapshot("conflict")));
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            conflictHello.connectionToken(), codec.encode(
                new QuestingFragment(2L, 2, 0, 2, new byte[] {1}))));
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            conflictHello.connectionToken(), codec.encode(
                new QuestingFragment(2L, 2, 0, 2, new byte[] {2}))));
        assertTrue(owner.current(handler).isEmpty());
        assertEquals(0, publications.get());
    }

    @Test
    void oneTransientPublicationFailureRetriesAndPersistentFailureCloses() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<byte[]> immutablePayload = new AtomicReference<>();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger());
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, packet -> { }, limits(), payload -> {
                if (attempts.incrementAndGet() == 1) {
                    immutablePayload.set(payload.clone());
                    payload[0] ^= 1;
                    throw new IllegalStateException("transient");
                }
                assertArrayEquals(immutablePayload.get(), payload);
            });
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot("retry")));
        QuestingFragment fragment = new QuestingFragment(2L, 1, 0, 1, new byte[] {5});
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            hello.connectionToken(), new QuestingFragmentCodec(limits()).encode(fragment)));
        assertEquals(2, attempts.get());
        assertTrue(owner.current(handler).isPresent());

        LoginSyncConnectionOwner failingOwner = owner(new AtomicInteger());
        LoginSyncClientWiring failing = new LoginSyncClientWiring(
            failingOwner, () -> { }, packet -> { }, limits(), payload -> {
                throw new IllegalStateException("persistent");
            });
        Object failingHandler = new Object();
        failing.login(failingHandler);
        LoginSyncFrame failingHello = failing.outboundHello(failingHandler).orElseThrow();
        LoginSyncSession failingServer = server();
        failing.receive(failingHandler,
            failingServer.receive(failingHello).response().orElseThrow());
        failing.receive(failingHandler, failingServer.sendSettings(snapshot("fail")));
        failing.receive(failingHandler, LoginSyncFrame.bulkFragment(
            failingHello.connectionToken(), new QuestingFragmentCodec(limits()).encode(fragment)));
        assertTrue(failingOwner.current(failingHandler).isEmpty());
    }

    @Test
    void exactClientTimeoutAndWorldDisconnectDoNotLeakOldAssemblyOrSettings() {
        LoginSyncSettingsCapture settings = new LoginSyncSettingsCapture();
        LoginSyncConnectionOwner owner = owner(new AtomicInteger(), settings);
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, packet -> { }, limits(), payload -> { });
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot("world-a")));
        QuestingFragment partial = new QuestingFragment(3L, 2, 0, 2, new byte[] {1});
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            hello.connectionToken(), new QuestingFragmentCodec(limits()).encode(partial)), 0L);
        assertEquals(1, wiring.currentOrchestrator(handler).orElseThrow().activeTransferCount());
        wiring.tick(9L);
        assertEquals(1, wiring.currentOrchestrator(handler).orElseThrow().activeTransferCount());
        wiring.tick(10L);
        assertEquals(0, wiring.currentOrchestrator(handler).orElseThrow().activeTransferCount());
        assertEquals(snapshot("world-a"), settings.current().orElseThrow());
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            hello.connectionToken(), new QuestingFragmentCodec(limits()).encode(
                new QuestingFragment(4L, 2, 0, 2, new byte[] {1}))), 11L);
        LoginSyncBulkSyncOrchestrator oldOrchestrator = wiring.currentOrchestrator(
            handler).orElseThrow();
        assertTrue(oldOrchestrator.reservedBytes() > 0L);

        wiring.worldUnload();
        assertTrue(settings.current().isEmpty());
        assertTrue(owner.current(handler).isEmpty());
        assertTrue(oldOrchestrator.isClosed());
        assertEquals(0L, oldOrchestrator.reservedBytes());

        wiring.login(handler);
        LoginSyncFrame newHello = wiring.outboundHello(handler).orElseThrow();
        assertFalse(newHello.connectionToken().equals(hello.connectionToken()));
        assertTrue(wiring.currentSettings(handler).isEmpty());
        assertEquals(0, wiring.currentOrchestrator(handler).orElseThrow().activeTransferCount());
    }

    @Test
    void dimensionTransitionPreservesBindingWhileTrueUnloadClearsExactlyOnce() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(clears);
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner, () -> { }, packet -> { });
        Object handler = new Object();
        Object oldWorld = new Object();
        Object newWorld = new Object();
        wiring.login(handler);
        LoginSyncSession session = owner.current(handler).orElseThrow();

        wiring.worldChanged(oldWorld, newWorld);

        assertSame(session, owner.current(handler).orElseThrow());
        assertFalse(session.isClosed());
        assertEquals(0, clears.get());

        wiring.worldChanged(newWorld, null);
        wiring.worldChanged(newWorld, null);
        wiring.terminal(handler);

        assertTrue(session.isClosed());
        assertTrue(owner.current(handler).isEmpty());
        assertEquals(1, clears.get());
    }

    @Test
    void senderPublicationAndTeardownCallbacksAreOutsideWiringLock() {
        AtomicReference<LoginSyncClientWiring> reference = new AtomicReference<>();
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger lockedCallbacks = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                ignored -> {
                    if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                        lockedCallbacks.incrementAndGet();
                    }
                },
                () -> {
                    if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                        lockedCallbacks.incrementAndGet();
                    }
                    clears.incrementAndGet();
                }));
        LoginSyncClientWiring wiring = new LoginSyncClientWiring(
            owner,
            () -> { },
            packet -> {
                if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                    lockedCallbacks.incrementAndGet();
                }
            },
            limits(),
            payload -> {
                if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                    lockedCallbacks.incrementAndGet();
                }
            });
        reference.set(wiring);
        Object handler = new Object();
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot("lock")));
        wiring.receive(handler, LoginSyncFrame.bulkFragment(
            hello.connectionToken(), new QuestingFragmentCodec(limits()).encode(
                new QuestingFragment(4L, 1, 0, 1, new byte[] {1}))));
        wiring.terminal(handler);
        assertEquals(1, clears.get());
        assertEquals(0, lockedCallbacks.get());
    }

    public static final class ReplayFixture {
        private final AtomicInteger publications = new AtomicInteger();
        private final LoginSyncConnectionOwner owner;
        private final LoginSyncClientWiring wiring;
        private final Object handler = new Object();
        private long nowNanos;

        public ReplayFixture(FragmentAssemblyLimits limits) {
            owner = owner(new AtomicInteger());
            wiring = new LoginSyncClientWiring(
                owner, () -> { }, packet -> { }, limits,
                payload -> publications.incrementAndGet());
            wiring.login(handler);
        }

        public LoginSyncFrame hello() {
            return wiring.outboundHello(handler).orElseThrow();
        }

        public void receive(Packet packet) {
            wiring.receive(
                handler,
                LoginSyncTransportPackets.extract(packet).orElseThrow(),
                nowNanos++);
        }

        public void advance(long nanos) {
            nowNanos += nanos;
        }

        public boolean isLive() {
            return owner.current(handler).isPresent();
        }

        public int publications() {
            return publications.get();
        }
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

    private static LoginSyncConnectionOwner owner(
        AtomicInteger clears,
        LoginSyncSettingsCapture settings
    ) {
        return new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                settings::apply,
                () -> {
                    clears.incrementAndGet();
                    settings.clear();
                }));
    }

    private static FragmentAssemblyLimits limits() {
        return limits(1_000L);
    }

    private static FragmentAssemblyLimits lifeLimits() {
        return lifeLimits(1_000L);
    }

    private static FragmentAssemblyLimits lifeLimits(long maxReservedBytes) {
        return new FragmentAssemblyLimits(7, 64, 10, 2, maxReservedBytes, 8, 10L);
    }

    private static FragmentAssemblyLimits nameLimits() {
        return new FragmentAssemblyLimits(7, 128, 20, 2, 4_096L, 8, 10L);
    }

    private static byte[] lifeEnvelope(int lives) {
        return LoginBulkPayloadCodec.encode(
            LoginBulkPayload.life(new LoginLifeSnapshot(lives)));
    }

    private static byte[] nameEnvelope(LoginNameSnapshot snapshot) {
        return LoginBulkPayloadCodec.encode(LoginBulkPayload.name(snapshot));
    }

    private static LoginSyncFrame readyTypedClient(
        LoginSyncClientWiring wiring,
        Object handler,
        String settingsName
    ) {
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot(settingsName)));
        return hello;
    }

    private static void sendBulk(
        LoginSyncClientWiring wiring,
        Object handler,
        UUID token,
        FragmentAssemblyLimits limits,
        long transferId,
        byte[] payload,
        long firstNanos
    ) {
        QuestingFragmentCodec codec = new QuestingFragmentCodec(limits);
        List<QuestingFragment> fragments = new BoundedFragmenter(limits).split(
            transferId, payload);
        for (int index = 0; index < fragments.size(); index++) {
            wiring.receive(handler, LoginSyncFrame.bulkFragment(
                token, codec.encode(fragments.get(index))), firstNanos + index);
        }
    }

    private static FragmentAssemblyLimits limits(long maxReservedBytes) {
        return new FragmentAssemblyLimits(2, 8, 4, 2, maxReservedBytes, 8, 10L);
    }

    private static LoginSettingsSnapshot snapshot(String name) {
        return new LoginSettingsSnapshot(
            name, 1, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }

    private static LoginSyncSession server() {
        return new LoginSyncSession(
            LoginSyncSession.Role.SERVER,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS);
    }

    private static LoginSyncFrame readyClient(
        LoginSyncClientWiring wiring,
        Object handler,
        String settingsName
    ) {
        wiring.login(handler);
        LoginSyncFrame hello = wiring.outboundHello(handler).orElseThrow();
        LoginSyncSession server = server();
        wiring.receive(handler, server.receive(hello).response().orElseThrow());
        wiring.receive(handler, server.sendSettings(snapshot(settingsName)));
        return hello;
    }

    private static LoginSyncFrame serverHello(LoginSyncFrame clientHello) {
        LoginSyncSession server = new LoginSyncSession(
            LoginSyncSession.Role.SERVER,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS);
        return server.receive(clientHello).response().orElseThrow();
    }

    private static final class LoginSyncSettingsCapture {
        private LoginSettingsSnapshot snapshot;

        private void apply(LoginSettingsSnapshot candidate) {
            snapshot = candidate;
        }

        private void clear() {
            snapshot = null;
        }

        private Optional<LoginSettingsSnapshot> current() {
            return Optional.ofNullable(snapshot);
        }
    }
}
