package com.github.postyizhan.betterquesting.network.handshake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HandshakeSessionTest {
    private static final HandshakeLimits LIMITS = new HandshakeLimits(8, 0b0011L, 0b0100L);
    private static final HandshakeCapabilities CLIENT_CAPABILITIES =
        new HandshakeCapabilities(1, 7, 0b0011L, 0b0001L);
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void compatibleSessionsReachReadyWithImmutableIntersection() {
        HandshakeSession client = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        HandshakeSession server = new HandshakeSession(
            new HandshakeCapabilities(1, 7, 0b1011L, 0b0010L), LIMITS);

        HandshakeHello clientHello = client.start(TOKEN);
        HandshakeHello serverHello = server.start(TOKEN);
        assertEquals(HandshakeSession.ReceiveOutcome.READY, client.receive(serverHello).outcome());
        assertEquals(HandshakeSession.ReceiveOutcome.READY, server.receive(clientHello).outcome());

        HandshakeNegotiation result = client.negotiation().orElseThrow();
        assertEquals(1, result.protocolVersion());
        assertEquals(7, result.dataFormatVersion());
        assertEquals(0b0011L, result.featureBits());
        assertSame(result, client.negotiation().orElseThrow());
        assertThrows(UnsupportedOperationException.class,
            () -> result.features().add(0L));
    }

    @Test
    void sessionsAreIsolatedAndOutOfOrderDoesNotAdvanceState() {
        HandshakeSession first = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        HandshakeSession second = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        HandshakeHello hello = first.start(TOKEN);

        assertEquals(HandshakeSession.ReceiveOutcome.OUT_OF_ORDER,
            second.receive(hello).outcome());
        assertEquals(HandshakeSession.State.NEW, second.state());
        second.start(TOKEN);
        assertEquals(HandshakeSession.ReceiveOutcome.READY, second.receive(hello).outcome());
        assertEquals(HandshakeSession.State.HELLO_SENT, first.state());
    }

    @Test
    void duplicateAndConflictingMessagesCannotRefreshReadyState() {
        HandshakeSession session = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        HandshakeHello local = session.start(TOKEN);
        HandshakeHello remote = hello(TOKEN, 1, 7, 0b0011L, 0b0001L);
        assertEquals(HandshakeSession.ReceiveOutcome.READY, session.receive(remote).outcome());
        HandshakeNegotiation result = session.negotiation().orElseThrow();

        assertEquals(HandshakeSession.ReceiveOutcome.DUPLICATE,
            session.receive(hello(TOKEN, 1, 7, 0b0011L, 0b0001L)).outcome());
        assertEquals(HandshakeSession.ReceiveOutcome.CONFLICT,
            session.receive(hello(TOKEN, 1, 7, 0b0001L, 0b0001L)).outcome());
        assertEquals(HandshakeSession.ReceiveOutcome.CONFLICT,
            session.receive(hello(OTHER_TOKEN, 1, 7, 0b0011L, 0b0001L)).outcome());
        assertEquals(HandshakeSession.State.READY, session.state());
        assertSame(result, session.negotiation().orElseThrow());
        assertEquals(local, session.localHello());
    }

    @Test
    void incompatibleVersionsFailClosedAndCannotBeRetried() {
        HandshakeSession session = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        session.start(TOKEN);

        HandshakeSession.ReceiveResult rejected = session.receive(hello(TOKEN, 2, 7, 0b0011L, 0L));
        assertEquals(HandshakeSession.ReceiveOutcome.REJECTED, rejected.outcome());
        assertEquals(HandshakeSession.State.FAILED, session.state());
        assertEquals(HandshakeSession.FailureReason.PROTOCOL_VERSION_MISMATCH,
            session.failureReason().orElseThrow());
        HandshakeSession.ReceiveResult ignored = session.receive(hello(TOKEN, 1, 7, 0b0011L, 0L));
        assertEquals(HandshakeSession.ReceiveOutcome.IGNORED, ignored.outcome());
        assertEquals(HandshakeSession.State.FAILED, session.state());
        assertEquals(HandshakeSession.FailureReason.PROTOCOL_VERSION_MISMATCH,
            session.failureReason().orElseThrow());
        assertTrue(session.negotiation().isEmpty());
    }

    @Test
    void dataFormatAndBothRequiredFeaturesMustBeCompatible() {
        HandshakeSession format = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        format.start(TOKEN);
        assertEquals(HandshakeSession.FailureReason.DATA_FORMAT_VERSION_MISMATCH,
            format.receive(hello(TOKEN, 1, 8, 0b0011L, 0L)).failureReason().orElseThrow());

        HandshakeSession required = new HandshakeSession(
            new HandshakeCapabilities(1, 7, 0b0011L, 0b0010L), LIMITS);
        required.start(TOKEN);
        assertEquals(HandshakeSession.FailureReason.REQUIRED_FEATURE_UNSUPPORTED,
            required.receive(hello(TOKEN, 1, 7, 0b0001L, 0L)).failureReason().orElseThrow());

        HandshakeSession peerRequired = new HandshakeSession(
            new HandshakeCapabilities(1, 7, 0b0001L, 0b0001L), LIMITS);
        peerRequired.start(TOKEN);
        assertEquals(HandshakeSession.FailureReason.REQUIRED_FEATURE_UNSUPPORTED,
            peerRequired.receive(hello(TOKEN, 1, 7, 0b0011L, 0b0010L)).failureReason().orElseThrow());

        HandshakeSession differentRequirements = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        differentRequirements.start(TOKEN);
        assertEquals(HandshakeSession.ReceiveOutcome.READY,
            differentRequirements.receive(hello(TOKEN, 1, 7, 0b0011L, 0b0010L)).outcome());
    }

    @Test
    void unknownOptionalBitsAreIgnoredSymmetricallyButUnknownRequiredBitsFailClosed() {
        HandshakeSession optional = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        optional.start(TOKEN);
        assertEquals(HandshakeSession.ReceiveOutcome.READY,
            optional.receive(hello(TOKEN, 1, 7, 0b1011L, 0b0001L)).outcome());
        assertEquals(0b0011L, optional.negotiation().orElseThrow().featureBits());

        HandshakeSession localOptional = new HandshakeSession(
            new HandshakeCapabilities(1, 7, 0b1001L, 0b0001L), LIMITS);
        localOptional.start(TOKEN);
        assertEquals(HandshakeSession.ReceiveOutcome.READY,
            localOptional.receive(hello(TOKEN, 1, 7, 0b0011L, 0b0001L)).outcome());
        assertEquals(0b0001L, localOptional.negotiation().orElseThrow().featureBits());

        HandshakeSession required = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        required.start(TOKEN);
        assertEquals(HandshakeSession.FailureReason.UNKNOWN_REQUIRED_FEATURE,
            required.receive(hello(TOKEN, 1, 7, 0b1011L, 0b1000L)).failureReason().orElseThrow());
    }

    @Test
    void reservedAndOutOfWidthBitsAreRejectedWithoutPartialNegotiation() {
        HandshakeSession reserved = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        reserved.start(TOKEN);
        assertEquals(HandshakeSession.FailureReason.RESERVED_FEATURE_BITS,
            reserved.receive(hello(TOKEN, 1, 7, 0b0100L, 0L)).failureReason().orElseThrow());
        assertTrue(reserved.negotiation().isEmpty());

        HandshakeSession tooWide = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        tooWide.start(TOKEN);
        assertEquals(HandshakeSession.FailureReason.FEATURE_BITS_OUT_OF_RANGE,
            tooWide.receive(hello(TOKEN, 1, 7, 1L << 8, 0L)).failureReason().orElseThrow());
    }

    @Test
    void rejectsInvalidValuesLimitsAndLocalFeatureConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> new HandshakeCapabilities(0, 1, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeCapabilities(-1, 1, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeCapabilities(1, 0, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeCapabilities(1, 1, -1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeCapabilities(1, 1, 1L, 2L));

        assertThrows(IllegalArgumentException.class, () -> new HandshakeLimits(0, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeLimits(64, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeLimits(8, 1L << 8, 0L));
        assertThrows(IllegalArgumentException.class, () -> new HandshakeLimits(8, 1L, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new HandshakeSession(new HandshakeCapabilities(1, 1, 1L << 8, 0L), LIMITS));
        assertThrows(IllegalArgumentException.class,
            () -> new HandshakeSession(new HandshakeCapabilities(1, 1, 0b0100L, 0L), LIMITS));
        assertThrows(IllegalArgumentException.class,
            () -> new HandshakeSession(new HandshakeCapabilities(1, 1, 0b1000L, 0b1000L), LIMITS));

        HandshakeLimits widest = new HandshakeLimits(63, 1L << 62, 0L);
        HandshakeSession boundary = new HandshakeSession(
            new HandshakeCapabilities(Integer.MAX_VALUE, Integer.MAX_VALUE, 1L << 62, 1L << 62), widest);
        boundary.start(TOKEN);
        assertEquals(HandshakeSession.ReceiveOutcome.READY,
            boundary.receive(hello(TOKEN, Integer.MAX_VALUE, Integer.MAX_VALUE, 1L << 62, 1L << 62)).outcome());
    }

    @Test
    void closeDisconnectAndResetClearStateAndPermanentlyInvalidateSession() {
        HandshakeSession session = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        session.start(TOKEN);
        session.receive(hello(TOKEN, 1, 7, 0b0011L, 0L));
        session.close();
        session.disconnect();
        session.reset();
        assertEquals(HandshakeSession.State.CLOSED, session.state());
        assertTrue(session.negotiation().isEmpty());
        assertTrue(session.failureReason().isEmpty());
        assertThrows(IllegalStateException.class, () -> session.start(TOKEN));
        assertThrows(IllegalStateException.class,
            () -> session.receive(hello(TOKEN, 1, 7, 0b0011L, 0L)));
    }

    @Test
    void allStateAccessIsOwnedByTheCreatingThread() throws Exception {
        HandshakeSession session = new HandshakeSession(CLIENT_CAPABILITIES, LIMITS);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                session.start(TOKEN);
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });
        other.start();
        other.join();
        assertTrue(thrown.get() instanceof IllegalStateException);
        assertEquals(HandshakeSession.State.NEW, session.state());
    }

    @Test
    void helloCarriesAnImmutableConnectionToken() {
        UUID token = UUID.randomUUID();
        HandshakeHello hello = new HandshakeHello(token, CLIENT_CAPABILITIES);

        assertEquals(token, hello.connectionToken());
        assertEquals(token, hello.token());
        assertEquals(token, HandshakeHelloCodec.decode(
            HandshakeHelloCodec.encode(hello)).orElseThrow().connectionToken());
    }

    private static HandshakeHello hello(
        UUID token, int protocol, int format, long supported, long required
    ) {
        return new HandshakeHello(token, new HandshakeCapabilities(protocol, format, supported, required));
    }
}
