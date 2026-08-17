package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.storage.ILifeDatabase;
import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
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
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.fml.client.LoginSyncClientWiringTest.ReplayFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import moddedmite.rustedironcore.network.Packet;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;

class LoginSyncServerWiringTest {
    private static final UUID TOKEN =
        UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID PLAYER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void protocolVersionOneClaimsNoFeatures() {
        assertEquals(1, LoginSyncProtocol.CAPABILITIES.protocolVersion());
        assertEquals(3, LoginSyncProtocol.CAPABILITIES.dataFormatVersion());
        assertEquals(0L, LoginSyncProtocol.CAPABILITIES.supportedFeatureBits());
        assertEquals(0L, LoginSyncProtocol.CAPABILITIES.requiredFeatureBits());
        assertEquals(0L, LoginSyncProtocol.LIMITS.knownFeatureBits());
        assertEquals(0L, LoginSyncProtocol.LIMITS.reservedFeatureBits());
        assertEquals(32766, LoginSyncProtocol.MAX_BULK_FRAME_BYTES);
        assertEquals(
            LoginSyncProtocol.MAX_BULK_FRAME_BYTES,
            LoginSyncProtocol.LOGIN_FRAME_HEADER_BYTES
                + LoginSyncProtocol.FRAGMENT_WIRE_HEADER_BYTES
                + LoginSyncProtocol.FRAGMENT_LIMITS.maxFragmentBytes());
    }

    @Test
    void typedLifeCapturePreservesStoredValueAndUsesTheCanonicalBulkEnvelope() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        List<LoginSyncFrame> sent = new ArrayList<>();
        ILifeDatabase lives = livesReturning(Integer.MIN_VALUE);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> sent.add(LoginSyncTransportPackets.extract(packet).orElseThrow()),
            (server, handler, recipient) -> snapshot("life"),
            (server, handler, recipient) -> List.of(),
            (server, handler, recipient) -> LoginSyncServerWiring.captureLifePayload(
                PlayerIdentityResolution.local(new PlayerIdentity(PLAYER_ID, "alice")), lives),
            LoginSyncProtocol.FRAGMENT_LIMITS);
        Object server = new Object();
        Object handler = new Object();

        wiring.bind(server, handler);
        wiring.receive(server, handler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));

        assertEquals(3, sent.size());
        assertEquals(LoginSyncFrame.Type.SERVER_HELLO, sent.get(0).type());
        assertEquals(LoginSyncFrame.Type.SETTINGS, sent.get(1).type());
        byte[] fragmentBytes = sent.get(2).bulkFragment().orElseThrow();
        byte[] envelopeBytes = LoginSyncProtocol.FRAGMENT_CODEC.decode(fragmentBytes)
            .orElseThrow().bytes();
        LoginBulkPayload envelope = LoginBulkPayloadCodec.decode(envelopeBytes).orElseThrow();
        assertEquals("betterquesting:life_sync", envelope.id());
        assertEquals(new LoginLifeSnapshot(Integer.MIN_VALUE), envelope.life());
        assertTrue(owner.current(server, handler).isPresent());
    }

    @Test
    void authoritativeNamePayloadUsesTheTypedPipelineAndIsAlwaysSentLast() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        List<LoginSyncFrame> sent = new ArrayList<>();
        LoginNameSnapshot name = new LoginNameSnapshot(PLAYER_ID, "Alice");
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> sent.add(LoginSyncTransportPackets.extract(packet).orElseThrow()),
            (server, handler, recipient) -> snapshot("name-last"),
            (server, handler, recipient) -> List.of(),
            (server, handler, recipient) -> Optional.of(
                LoginBulkPayload.life(new LoginLifeSnapshot(7))),
            (server, handler, recipient) -> Optional.of(LoginBulkPayload.name(name)),
            LoginSyncProtocol.FRAGMENT_LIMITS);
        Object server = new Object();
        Object handler = new Object();

        wiring.bind(server, handler);
        wiring.receive(server, handler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));

        assertEquals(4, sent.size());
        LoginBulkPayload firstTyped = decodeSingleFragment(sent.get(2));
        LoginBulkPayload lastTyped = decodeSingleFragment(sent.get(3));
        assertEquals(new LoginLifeSnapshot(7), firstTyped.life());
        assertEquals(name, lastTyped.name());
        assertEquals("betterquesting:login_name", lastTyped.id());
    }

    @Test
    void missingOrFailingRequiredNameCaptureSendsNoFramesAndClosesTheBinding() {
        Object server = new Object();
        Object handler = new Object();
        LoginSyncFrame hello = LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES));

        LoginSyncConnectionOwner missingOwner = owner(LoginSyncSession.Role.SERVER);
        List<LoginSyncFrame> missingSent = new ArrayList<>();
        LoginSyncServerWiring missing = new LoginSyncServerWiring(
            missingOwner,
            (recipient, packet) -> missingSent.add(
                LoginSyncTransportPackets.extract(packet).orElseThrow()),
            (serverOwner, boundHandler, recipient) -> snapshot("missing-name"),
            (serverOwner, boundHandler, recipient) -> List.of(),
            (serverOwner, boundHandler, recipient) -> Optional.of(
                LoginBulkPayload.life(new LoginLifeSnapshot(3))),
            (serverOwner, boundHandler, recipient) -> Optional.empty(),
            LoginSyncProtocol.FRAGMENT_LIMITS);
        missing.bind(server, handler);
        missing.receive(server, handler, new Object(), hello);

        assertTrue(missingSent.isEmpty());
        assertTrue(missingOwner.current(server, handler).isEmpty());

        LoginSyncConnectionOwner failingOwner = owner(LoginSyncSession.Role.SERVER);
        List<LoginSyncFrame> failingSent = new ArrayList<>();
        LoginSyncServerWiring failing = new LoginSyncServerWiring(
            failingOwner,
            (recipient, packet) -> failingSent.add(
                LoginSyncTransportPackets.extract(packet).orElseThrow()),
            (serverOwner, boundHandler, recipient) -> snapshot("failing-name"),
            (serverOwner, boundHandler, recipient) -> List.of(new byte[] {1}),
            (serverOwner, boundHandler, recipient) -> Optional.empty(),
            (serverOwner, boundHandler, recipient) -> {
                throw new IllegalArgumentException("name capture failed");
            },
            LoginSyncProtocol.FRAGMENT_LIMITS);
        failing.bind(server, handler);
        failing.receive(server, handler, new Object(), hello);

        assertTrue(failingSent.isEmpty());
        assertTrue(failingOwner.current(server, handler).isEmpty());
    }

    @Test
    void unresolvedIdentityOmitsOnlyLifeWhileSettingsStillComplete() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        List<LoginSyncFrame> sent = new ArrayList<>();
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> sent.add(LoginSyncTransportPackets.extract(packet).orElseThrow()),
            (server, handler, recipient) -> snapshot("settings-only"),
            (server, handler, recipient) -> List.of(),
            (server, handler, recipient) -> LoginSyncServerWiring.captureLifePayload(
                PlayerIdentityResolution.unsupportedUsername(null), livesReturning(7)),
            LoginSyncProtocol.FRAGMENT_LIMITS);
        Object server = new Object();
        Object handler = new Object();

        wiring.bind(server, handler);
        wiring.receive(server, handler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));

        assertEquals(List.of(LoginSyncFrame.Type.SERVER_HELLO, LoginSyncFrame.Type.SETTINGS),
            sent.stream().map(LoginSyncFrame::type).toList());
        assertTrue(owner.current(server, handler).isPresent());
    }

    @Test
    void preNameDataFormatPeerCannotNegotiate() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(owner, (recipient, packet) -> { });
        Object server = new Object();
        Object handler = new Object();
        wiring.bind(server, handler);

        wiring.receive(server, handler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, new HandshakeCapabilities(1, 2, 0L, 0L))));

        assertTrue(owner.current(server, handler).isEmpty());
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

        assertEquals(2, sent.size());
        assertSame(player, sent.get(0).recipient());
        LoginSyncFrame serverHello = LoginSyncTransportPackets.extract(sent.get(0).packet())
            .orElseThrow();
        LoginSyncFrame settings = LoginSyncTransportPackets.extract(sent.get(1).packet())
            .orElseThrow();
        assertEquals(LoginSyncFrame.Type.SERVER_HELLO, serverHello.type());
        assertEquals(LoginSyncSession.Outcome.ACCEPTED, client.receive(serverHello).outcome());
        assertEquals(LoginSyncFrame.Type.SETTINGS, settings.type());
        assertEquals(LoginSyncSession.Outcome.PUBLISHED, client.receive(settings).outcome());
        assertEquals(LoginSyncSession.State.PUBLISHED, client.state());
        assertEquals(LoginSyncSession.State.READY, serverSession.state());
    }

    @Test
    void duplicateHelloReplaysIdenticalHelloSettingsAndTransferIdsWithoutRecapture() {
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        List<LoginSyncFrame> sent = new ArrayList<>();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger sources = new AtomicInteger();
        LoginSettingsSnapshot settings = snapshot("cached");
        FragmentAssemblyLimits limits = limits();
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> sent.add(LoginSyncTransportPackets.extract(packet).orElseThrow()),
            (server, handler, recipient) -> {
                captures.incrementAndGet();
                return settings;
            },
            (server, handler, recipient) -> {
                sources.incrementAndGet();
                return List.of(new byte[] {1, 2, 3, 4});
            },
            limits);
        Object server = new Object();
        Object handler = new Object();
        LoginSyncFrame hello = LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES));

        wiring.bind(server, handler);
        LoginSyncBulkSyncOrchestrator orchestrator = wiring.currentOrchestrator(
            server, handler).orElseThrow();
        wiring.receive(server, handler, new Object(), hello);
        List<LoginSyncFrame> first = List.copyOf(sent);
        wiring.receive(server, handler, new Object(), hello);

        assertEquals(1, captures.get());
        assertEquals(1, sources.get());
        assertEquals(4, first.size());
        assertEquals(first, sent.subList(first.size(), sent.size()));
        assertEquals(LoginSyncFrame.Type.SERVER_HELLO, first.get(0).type());
        assertEquals(settings, first.get(1).settings().orElseThrow());
        assertEquals(LoginSyncFrame.Type.BULK_FRAGMENT, first.get(2).type());
        assertSame(orchestrator, wiring.currentOrchestrator(server, handler).orElseThrow());
    }

    @Test
    void completedInitialSequenceSurvivesDuplicateHelloReplayWithoutRepublishing() {
        FragmentAssemblyLimits limits = limits();
        ReplayFixture client = new ReplayFixture(limits);
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> client.receive(packet),
            (server, handler, recipient) -> snapshot("replay"),
            (server, handler, recipient) -> List.of(new byte[] {1, 2, 3, 4}),
            limits);
        Object server = new Object();
        Object handler = new Object();
        Object player = new Object();

        wiring.bind(server, handler);
        wiring.receive(server, handler, player, client.hello());
        assertTrue(client.isLive());
        assertEquals(1, client.publications());

        client.advance(limits.idleTimeoutNanos());
        wiring.receive(server, handler, player, client.hello());

        assertTrue(client.isLive());
        assertEquals(1, client.publications());
        assertTrue(owner.current(server, handler).isPresent());
    }

    @Test
    void serverReplayBytesAcceptExactBoundRejectBoundaryPlusOneAndRelease() {
        long replayBound = 2L * (QuestingFragmentCodec.HEADER_BYTES + 1L);
        FragmentAssemblyLimits replayLimits = limits(replayBound);
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> { },
            (server, handler, recipient) -> snapshot("bounded"),
            (server, handler, recipient) -> List.of(new byte[] {1}, new byte[] {2}),
            replayLimits);
        Object server = new Object();
        Object handler = new Object();
        LoginSyncFrame hello = LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES));

        wiring.bind(server, handler);
        wiring.receive(server, handler, new Object(), hello);

        assertTrue(owner.current(server, handler).isPresent());
        assertEquals(replayBound, wiring.retainedReplayBytes(server, handler));
        wiring.receive(server, handler, new Object(), hello);
        assertTrue(owner.current(server, handler).isPresent());
        assertEquals(replayBound, wiring.retainedReplayBytes(server, handler));

        wiring.rebind(server, handler);
        assertEquals(0L, wiring.retainedReplayBytes(server, handler));
        wiring.receive(server, handler, new Object(), hello);
        assertEquals(replayBound, wiring.retainedReplayBytes(server, handler));
        wiring.unbind(server, handler);
        assertEquals(0L, wiring.retainedReplayBytes(server, handler));

        wiring.bind(server, handler);
        wiring.receive(server, handler, new Object(), hello);
        assertEquals(replayBound, wiring.retainedReplayBytes(server, handler));
        wiring.closeAll(server);
        assertEquals(0L, wiring.retainedReplayBytes(server, handler));

        LoginSyncConnectionOwner overflowOwner = owner(LoginSyncSession.Role.SERVER);
        LoginSyncServerWiring overflow = new LoginSyncServerWiring(
            overflowOwner,
            (recipient, packet) -> { },
            (serverOwner, boundHandler, recipient) -> snapshot("overflow"),
            (serverOwner, boundHandler, recipient) ->
                List.of(new byte[] {1, 2}, new byte[] {3}),
            replayLimits);
        assertEquals(replayBound + 1L,
            2L * QuestingFragmentCodec.HEADER_BYTES + 3L);
        overflow.bind(server, handler);
        overflow.receive(server, handler, new Object(), hello);

        assertTrue(overflowOwner.current(server, handler).isEmpty());
        assertEquals(0L, overflow.retainedReplayBytes(server, handler));
    }

    @Test
    void rebindLogoutAndStopEachCloseExactlyTheOwnedOrchestrator() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = owner(LoginSyncSession.Role.SERVER, clears);
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner, (recipient, packet) -> { });
        Object server = new Object();
        Object handler = new Object();

        wiring.bind(server, handler);
        LoginSyncBulkSyncOrchestrator first = wiring.currentOrchestrator(
            server, handler).orElseThrow();
        wiring.rebind(server, handler);
        LoginSyncBulkSyncOrchestrator second = wiring.currentOrchestrator(
            server, handler).orElseThrow();

        assertNotSame(first, second);
        assertTrue(first.isClosed());
        assertEquals(1, clears.get());
        wiring.unbind(server, handler);
        wiring.unbind(server, handler);
        assertTrue(second.isClosed());
        assertEquals(2, clears.get());

        wiring.bind(server, handler);
        LoginSyncBulkSyncOrchestrator third = wiring.currentOrchestrator(
            server, handler).orElseThrow();
        wiring.closeAll(server);
        wiring.closeAll(server);
        assertTrue(third.isClosed());
        assertEquals(3, clears.get());
    }

    @Test
    void captureSourceSendAndTeardownCallbacksRunOutsideTheWiringLock() {
        AtomicReference<LoginSyncServerWiring> reference = new AtomicReference<>();
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger lockedCallbacks = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, handler) -> new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                ignored -> { },
                () -> {
                    if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                        lockedCallbacks.incrementAndGet();
                    }
                    clears.incrementAndGet();
                }));
        LoginSyncServerWiring wiring = new LoginSyncServerWiring(
            owner,
            (recipient, packet) -> {
                if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                    lockedCallbacks.incrementAndGet();
                }
            },
            (server, handler, recipient) -> {
                if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                    lockedCallbacks.incrementAndGet();
                }
                return snapshot("lock-free");
            },
            (server, handler, recipient) -> {
                if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                    lockedCallbacks.incrementAndGet();
                }
                return List.of(new byte[] {1});
            },
            (server, handler, recipient) -> {
                if (reference.get().isLifecycleLockHeldByCurrentThread()) {
                    lockedCallbacks.incrementAndGet();
                }
                return Optional.empty();
            },
            limits());
        reference.set(wiring);
        Object server = new Object();
        Object handler = new Object();

        wiring.bind(server, handler);
        wiring.receive(server, handler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));
        wiring.unbind(server, handler);

        assertEquals(1, clears.get());
        assertEquals(0, lockedCallbacks.get());
    }

    @Test
    void captureAndSourceFailuresCloseOnlyTheirExpectedBindings() {
        Object server = new Object();
        Object failedHandler = new Object();
        Object healthyHandler = new Object();
        LoginSyncConnectionOwner captureOwner = owner(LoginSyncSession.Role.SERVER);
        LoginSyncServerWiring captureFailure = new LoginSyncServerWiring(
            captureOwner,
            (recipient, packet) -> { },
            (serverOwner, handler, recipient) -> {
                throw new IllegalStateException("capture failed");
            },
            (serverOwner, handler, recipient) -> List.of(),
            limits());
        captureFailure.bind(server, failedHandler);
        captureFailure.bind(server, healthyHandler);
        LoginSyncSession healthyCapture = captureOwner.current(
            server, healthyHandler).orElseThrow();

        captureFailure.receive(server, failedHandler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));

        assertTrue(captureOwner.current(server, failedHandler).isEmpty());
        assertSame(healthyCapture, captureOwner.current(server, healthyHandler).orElseThrow());

        LoginSyncConnectionOwner sourceOwner = owner(LoginSyncSession.Role.SERVER);
        LoginSyncServerWiring sourceFailure = new LoginSyncServerWiring(
            sourceOwner,
            (recipient, packet) -> { },
            (serverOwner, handler, recipient) -> snapshot("source"),
            (serverOwner, handler, recipient) -> {
                throw new IllegalStateException("source failed");
            },
            limits());
        sourceFailure.bind(server, failedHandler);
        sourceFailure.bind(server, healthyHandler);
        LoginSyncSession healthySource = sourceOwner.current(server, healthyHandler).orElseThrow();

        sourceFailure.receive(server, failedHandler, new Object(), LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, LoginSyncProtocol.CAPABILITIES)));

        assertTrue(sourceOwner.current(server, failedHandler).isEmpty());
        assertSame(healthySource, sourceOwner.current(server, healthyHandler).orElseThrow());
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

    private static FragmentAssemblyLimits limits() {
        return limits(1_000L);
    }

    private static FragmentAssemblyLimits limits(long maxReservedBytes) {
        return new FragmentAssemblyLimits(2, 8, 4, 2, maxReservedBytes, 8, 10L);
    }

    private static LoginSettingsSnapshot snapshot(String name) {
        return new LoginSettingsSnapshot(
            name, 1, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }

    private static LoginBulkPayload decodeSingleFragment(LoginSyncFrame frame) {
        byte[] fragmentBytes = frame.bulkFragment().orElseThrow();
        byte[] envelopeBytes = LoginSyncProtocol.FRAGMENT_CODEC.decode(fragmentBytes)
            .orElseThrow().bytes();
        return LoginBulkPayloadCodec.decode(envelopeBytes).orElseThrow();
    }

    private static ILifeDatabase livesReturning(int value) {
        return new ILifeDatabase() {
            @Override
            public int getLives(UUID uuid) {
                assertEquals(PLAYER_ID, uuid);
                return value;
            }

            @Override
            public void setLives(UUID uuid, int lives) {
                throw new UnsupportedOperationException();
            }

            @Override
            public NBTTagCompound writeToNBT(NBTTagCompound nbt, List<UUID> users) {
                return nbt;
            }

            @Override
            public void readFromNBT(NBTTagCompound nbt, boolean merge) {
            }

            @Override
            public void reset() {
            }
        };
    }

    private record Sent(Object recipient, Packet packet) {
    }
}
