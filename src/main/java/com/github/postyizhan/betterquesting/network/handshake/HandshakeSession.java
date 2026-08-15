package com.github.postyizhan.betterquesting.network.handshake;

import java.util.Objects;
import java.util.Optional;

public final class HandshakeSession {
    public enum State {
        NEW,
        HELLO_SENT,
        READY,
        FAILED,
        CLOSED
    }

    public enum ReceiveOutcome {
        READY,
        OUT_OF_ORDER,
        DUPLICATE,
        CONFLICT,
        REJECTED,
        IGNORED
    }

    public enum FailureReason {
        PROTOCOL_VERSION_MISMATCH,
        DATA_FORMAT_VERSION_MISMATCH,
        FEATURE_BITS_OUT_OF_RANGE,
        RESERVED_FEATURE_BITS,
        UNKNOWN_REQUIRED_FEATURE,
        REQUIRED_FEATURE_UNSUPPORTED
    }

    public record ReceiveResult(ReceiveOutcome outcome, Optional<FailureReason> failureReason) {
        public ReceiveResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }

    private final Thread owner;
    private final HandshakeCapabilities localCapabilities;
    private final HandshakeLimits limits;

    private State state = State.NEW;
    private HandshakeHello localHello;
    private HandshakeHello remoteHello;
    private HandshakeNegotiation negotiation;
    private FailureReason failureReason;

    public HandshakeSession(HandshakeCapabilities localCapabilities, HandshakeLimits limits) {
        this.owner = Thread.currentThread();
        this.localCapabilities = Objects.requireNonNull(localCapabilities, "localCapabilities");
        this.limits = Objects.requireNonNull(limits, "limits");

        long localSupported = localCapabilities.supportedFeatureBits();
        long localRequired = localCapabilities.requiredFeatureBits();
        if ((localSupported & ~limits.widthMask()) != 0L
            || (localSupported & limits.reservedFeatureBits()) != 0L
            || (localRequired & ~limits.knownFeatureBits()) != 0L) {
            throw new IllegalArgumentException("local capabilities contain unknown features");
        }
    }

    public HandshakeHello start() {
        checkOwner();
        if (state != State.NEW) {
            throw new IllegalStateException("handshake cannot be started in state " + state);
        }

        localHello = new HandshakeHello(localCapabilities);
        state = State.HELLO_SENT;
        return localHello;
    }

    public ReceiveResult receive(HandshakeHello hello) {
        checkOwner();
        if (state == State.CLOSED) {
            throw new IllegalStateException("handshake session is closed");
        }
        Objects.requireNonNull(hello, "hello");

        if (state == State.NEW) {
            return result(ReceiveOutcome.OUT_OF_ORDER, null);
        }
        if (state == State.FAILED) {
            return result(ReceiveOutcome.IGNORED, failureReason);
        }
        if (state == State.READY) {
            ReceiveOutcome outcome = remoteHello.equals(hello)
                ? ReceiveOutcome.DUPLICATE
                : ReceiveOutcome.CONFLICT;
            return result(outcome, null);
        }

        HandshakeCapabilities remote = hello.capabilities();
        FailureReason rejection = validate(remote);
        if (rejection != null) {
            state = State.FAILED;
            failureReason = rejection;
            negotiation = null;
            remoteHello = null;
            return result(ReceiveOutcome.REJECTED, rejection);
        }

        long negotiatedFeatures = localCapabilities.supportedFeatureBits()
            & remote.supportedFeatureBits()
            & limits.knownFeatureBits();
        negotiation = new HandshakeNegotiation(
            localCapabilities.protocolVersion(),
            localCapabilities.dataFormatVersion(),
            negotiatedFeatures);
        remoteHello = hello;
        state = State.READY;
        return result(ReceiveOutcome.READY, null);
    }

    public State state() {
        checkOwner();
        return state;
    }

    public HandshakeHello localHello() {
        checkOwner();
        return localHello;
    }

    public Optional<HandshakeNegotiation> negotiation() {
        checkOwner();
        return Optional.ofNullable(negotiation);
    }

    public Optional<FailureReason> failureReason() {
        checkOwner();
        return Optional.ofNullable(failureReason);
    }

    public void close() {
        invalidate();
    }

    public void disconnect() {
        invalidate();
    }

    public void reset() {
        invalidate();
    }

    private FailureReason validate(HandshakeCapabilities remote) {
        if (remote.protocolVersion() != localCapabilities.protocolVersion()) {
            return FailureReason.PROTOCOL_VERSION_MISMATCH;
        }
        if (remote.dataFormatVersion() != localCapabilities.dataFormatVersion()) {
            return FailureReason.DATA_FORMAT_VERSION_MISMATCH;
        }
        if ((remote.supportedFeatureBits() & ~limits.widthMask()) != 0L) {
            return FailureReason.FEATURE_BITS_OUT_OF_RANGE;
        }
        if ((remote.supportedFeatureBits() & limits.reservedFeatureBits()) != 0L) {
            return FailureReason.RESERVED_FEATURE_BITS;
        }
        if ((remote.requiredFeatureBits() & ~limits.knownFeatureBits()) != 0L) {
            return FailureReason.UNKNOWN_REQUIRED_FEATURE;
        }
        if ((localCapabilities.requiredFeatureBits() & ~remote.supportedFeatureBits()) != 0L
            || (remote.requiredFeatureBits() & ~localCapabilities.supportedFeatureBits()) != 0L) {
            return FailureReason.REQUIRED_FEATURE_UNSUPPORTED;
        }
        return null;
    }

    private void invalidate() {
        checkOwner();
        state = State.CLOSED;
        localHello = null;
        remoteHello = null;
        negotiation = null;
        failureReason = null;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("handshake session accessed from a non-owner thread");
        }
    }

    private static ReceiveResult result(ReceiveOutcome outcome, FailureReason failureReason) {
        return new ReceiveResult(outcome, Optional.ofNullable(failureReason));
    }
}
