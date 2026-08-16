package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.fragment.BoundedFragmentAssembler;
import com.github.postyizhan.betterquesting.network.fragment.BoundedFragmenter;
import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragment;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragmentCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import moddedmite.rustedironcore.network.Packet;

/**
 * Coordinates one login connection's bulk payload state after the strict login handshake.
 *
 * <p>The session owns handshake state while this object owns the fragmenter, codec and assembler.
 * A new instance is required for a rebind; this is what prevents fragments retained by an old
 * connection from completing a transfer on a new connection.</p>
 */
public final class LoginSyncBulkSyncOrchestrator implements AutoCloseable {
    @FunctionalInterface
    public interface PayloadPublication {
        /** Publishes one complete, immutable logical payload. */
        void publish(byte[] payload);
    }

    public enum Outcome {
        ACCEPTED,
        FRAGMENTS_CREATED,
        PUBLISHED,
        DUPLICATE,
        APPLICATION_FAILED,
        REJECTED,
        NOT_READY,
        CLOSED
    }

    public enum RejectionReason {
        MALFORMED,
        OVERSIZED,
        WRONG_ROLE,
        NOT_READY,
        CLOSED,
        INVALID_PAYLOAD,
        FRAGMENT_REJECTED
    }

    /** Result returned by outbound fragment generation and inbound payload application. */
    public static final class Result {
        private final Outcome outcome;
        private final RejectionReason rejectionReason;
        private final BoundedFragmentAssembler.RejectionReason fragmentRejectionReason;
        private final List<QuestingFragment> fragments;
        private final byte[] payload;
        private final LoginSyncBulkSyncOrchestrator retryOwner;
        private boolean retryClaimed;

        private Result(
            Outcome outcome,
            RejectionReason rejectionReason,
            BoundedFragmentAssembler.RejectionReason fragmentRejectionReason,
            List<QuestingFragment> fragments,
            byte[] payload,
            LoginSyncBulkSyncOrchestrator retryOwner
        ) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.rejectionReason = rejectionReason;
            this.fragmentRejectionReason = fragmentRejectionReason;
            this.fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
            this.payload = payload == null ? null : payload.clone();
            this.retryOwner = retryOwner;
        }

        private static Result outcome(Outcome outcome) {
            return new Result(outcome, null, null, List.of(), null, null);
        }

        private static Result rejected(RejectionReason reason) {
            return new Result(Outcome.REJECTED, reason, null, List.of(), null, null);
        }

        private static Result rejected(BoundedFragmentAssembler.RejectionReason reason) {
            return new Result(
                Outcome.REJECTED,
                RejectionReason.FRAGMENT_REJECTED,
                Objects.requireNonNull(reason, "reason"),
                List.of(),
                null,
                null);
        }

        private static Result fragmentsCreated(List<QuestingFragment> fragments) {
            return new Result(Outcome.FRAGMENTS_CREATED, null, null, fragments, null, null);
        }

        private static Result completed(byte[] payload) {
            return new Result(Outcome.PUBLISHED, null, null, List.of(), payload, null);
        }

        private static Result applicationFailed(
            byte[] payload,
            LoginSyncBulkSyncOrchestrator retryOwner
        ) {
            return new Result(
                Outcome.APPLICATION_FAILED, null, null, List.of(), payload, retryOwner);
        }

        public Outcome outcome() {
            return outcome;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public Optional<RejectionReason> rejectionReason() {
            return Optional.ofNullable(rejectionReason);
        }

        public Optional<RejectionReason> reason() {
            return rejectionReason();
        }

        public Optional<BoundedFragmentAssembler.RejectionReason> fragmentRejectionReason() {
            return Optional.ofNullable(fragmentRejectionReason);
        }

        public List<QuestingFragment> fragments() {
            return fragments;
        }

        /** Returns canonical wire bytes for outbound fragments without exposing mutable internals. */
        public List<byte[]> encodedFragments(QuestingFragmentCodec codec) {
            Objects.requireNonNull(codec, "codec");
            List<byte[]> encoded = new ArrayList<>(fragments.size());
            for (QuestingFragment fragment : fragments) {
                encoded.add(codec.encode(fragment));
            }
            List<byte[]> owned = new ArrayList<>(encoded.size());
            for (byte[] bytes : encoded) {
                owned.add(bytes.clone());
            }
            return List.copyOf(owned);
        }

        /** Returns completed bytes for successful or failed inbound application results. */
        public Optional<byte[]> payload() {
            return payload == null ? Optional.empty() : Optional.of(payload.clone());
        }

        public Optional<byte[]> completedPayload() {
            return payload();
        }

        public boolean isRejected() {
            return outcome == Outcome.REJECTED;
        }

        private byte[] claimRetry(LoginSyncBulkSyncOrchestrator candidateOwner) {
            if (outcome != Outcome.APPLICATION_FAILED
                || retryOwner != candidateOwner
                || retryClaimed) {
                throw new IllegalArgumentException(
                    "result is not a retryable application failure from this orchestrator");
            }
            retryClaimed = true;
            return payload.clone();
        }

        @Override
        public String toString() {
            return "LoginSyncBulkSyncOrchestrator.Result[outcome=" + outcome
                + ", rejectionReason=" + rejectionReason
                + ", fragmentRejectionReason=" + fragmentRejectionReason
                + ", fragmentCount=" + fragments.size()
                + ", payloadLength=" + (payload == null ? 0 : payload.length) + ']';
        }
    }

    private final Thread owner;
    private final Object lifecycleLock = new Object();
    private final LoginSyncSession session;
    private final BoundedFragmenter fragmenter;
    private final QuestingFragmentCodec fragmentCodec;
    private final BoundedFragmentAssembler assembler;
    private final PayloadPublication publication;
    private long nextTransferSequence = ThreadLocalRandom.current().nextLong();
    private boolean applying;
    private boolean closed;

    public LoginSyncBulkSyncOrchestrator(
        LoginSyncSession session,
        FragmentAssemblyLimits limits,
        PayloadPublication publication
    ) {
        this.owner = Thread.currentThread();
        this.session = Objects.requireNonNull(session, "session");
        Objects.requireNonNull(limits, "limits");
        this.fragmenter = new BoundedFragmenter(limits);
        this.fragmentCodec = new QuestingFragmentCodec(limits);
        this.assembler = new BoundedFragmentAssembler(limits);
        this.publication = Objects.requireNonNull(publication, "publication");
        // Session teardown is the connection boundary; no fragment assembly may outlive it.
        session.addCloseHook(this::closeForSession);
    }

    public LoginSyncBulkSyncOrchestrator(
        LoginSyncSession session,
        FragmentAssemblyLimits limits
    ) {
        this(session, limits, ignored -> { });
    }

    public LoginSyncSession session() {
        return session;
    }

    public FragmentAssemblyLimits limits() {
        return fragmenter.limits();
    }

    public QuestingFragmentCodec fragmentCodec() {
        return fragmentCodec;
    }

    /** Starts the client hello without exposing handshake internals to a transport adapter. */
    public LoginSyncFrame startClientHello() {
        checkOwner();
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("login sync bulk orchestrator is closed");
            }
        }
        return session.start();
    }

    public LoginSyncFrame startClientHello(UUID connectionToken) {
        checkOwner();
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("login sync bulk orchestrator is closed");
            }
        }
        return session.start(connectionToken);
    }

    /** Creates the existing direction-bound login packet; no registration or runtime wiring occurs here. */
    public Packet startClientHelloPacket(UUID connectionToken) {
        return transportStartedHello(startClientHello(connectionToken));
    }

    public Packet startClientHelloPacket() {
        return transportStartedHello(startClientHello());
    }

    /** Returns the strict frame wire bytes consumed by LoginSyncTransportPackets. */
    public byte[] encodeFrame(LoginSyncFrame frame) {
        checkOwner();
        return LoginSyncFrameCodec.encode(Objects.requireNonNull(frame, "frame"));
    }

    public LoginSyncSession.ReceiveResult receiveFrame(LoginSyncFrame frame) {
        checkOwner();
        if (session.isClosed()) {
            closeForSession();
        }
        LoginSyncSession.ReceiveResult result = session.receive(frame);
        if (session.isClosed()) {
            closeForSession();
        }
        return result;
    }

    public LoginSyncSession.ReceiveResult receiveFrameEncoded(byte[] encoded) {
        checkOwner();
        if (session.isClosed()) {
            closeForSession();
        }
        LoginSyncSession.ReceiveResult result = session.receiveEncoded(encoded);
        if (session.isClosed()) {
            closeForSession();
        }
        return result;
    }

    public LoginSyncSession.ReceiveResult receive(LoginSyncFrame frame) {
        return receiveFrame(frame);
    }

    public LoginSyncSession.ReceiveResult receiveEncoded(byte[] encoded) {
        return receiveFrameEncoded(encoded);
    }

    public LoginSyncFrame sendSettings(LoginSettingsSnapshot snapshot) {
        checkOwner();
        if (isClosed()) {
            throw new IllegalStateException("login sync bulk orchestrator is closed");
        }
        return session.sendSettings(snapshot);
    }

    public Packet sendSettingsPacket(LoginSettingsSnapshot snapshot) {
        return transportPacket(sendSettings(snapshot));
    }

    /** Routes a packet only through the existing strict frame extractor. */
    public LoginSyncSession.ReceiveResult receivePacket(Packet packet) {
        checkOwner();
        Optional<LoginSyncFrame> frame = LoginSyncTransportPackets.extract(packet);
        return frame.isPresent()
            ? receiveFrame(frame.orElseThrow())
            : receiveFrameEncoded(new byte[0]);
    }

    /** Splits a server-authored payload into bounded fragments after handshake readiness. */
    public Result publish(byte[] payload) {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        LoginSyncSession.Role sessionRole = session.role();
        synchronized (lifecycleLock) {
            if (closed || sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
                return Result.outcome(Outcome.CLOSED);
            }
            if (sessionRole != LoginSyncSession.Role.SERVER) {
                return Result.rejected(RejectionReason.WRONG_ROLE);
            }
            if (!isHandshakeReady(sessionState)) {
                return Result.outcome(Outcome.NOT_READY);
            }
            Result invalid = validateOutboundPayload(payload);
            if (invalid != null) {
                return invalid;
            }
            return publishReady(nextTransferId(), payload);
        }
    }

    /** Deterministic transfer-id overload useful to callers that persist an outbound queue. */
    public Result publish(long transferId, byte[] payload) {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        LoginSyncSession.Role sessionRole = session.role();
        synchronized (lifecycleLock) {
            if (closed || sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
                return Result.outcome(Outcome.CLOSED);
            }
            if (sessionRole != LoginSyncSession.Role.SERVER) {
                return Result.rejected(RejectionReason.WRONG_ROLE);
            }
            if (!isHandshakeReady(sessionState)) {
                return Result.outcome(Outcome.NOT_READY);
            }
            Result invalid = validateOutboundPayload(payload);
            if (invalid != null) {
                return invalid;
            }
            return publishReady(transferId, payload);
        }
    }

    private Result publishReady(long transferId, byte[] payload) {
        try {
            List<QuestingFragment> fragments = fragmenter.split(transferId, payload);
            // Validate every produced fragment through the canonical wire codec before exposing it.
            for (QuestingFragment fragment : fragments) {
                fragmentCodec.decode(fragmentCodec.encode(fragment)).orElseThrow();
            }
            return Result.fragmentsCreated(fragments);
        } catch (IllegalArgumentException invalidPayload) {
            return Result.rejected(payload.length > limits().maxTransferBytes()
                ? RejectionReason.OVERSIZED : RejectionReason.INVALID_PAYLOAD);
        }
    }

    /** Accepts a decoded fragment, preserving the assembler's duplicate and rejection semantics. */
    public Result accept(QuestingFragment fragment, long nowNanos) {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        LoginSyncSession.Role sessionRole = session.role();
        ApplicationStep step;
        synchronized (lifecycleLock) {
            if (closed || sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
                return Result.outcome(Outcome.CLOSED);
            }
            if (sessionRole != LoginSyncSession.Role.CLIENT) {
                return Result.rejected(RejectionReason.WRONG_ROLE);
            }
            if (!isHandshakeReady(sessionState)) {
                return Result.outcome(Outcome.NOT_READY);
            }
            if (fragment == null) {
                return Result.rejected(RejectionReason.MALFORMED);
            }
            QuestingFragment canonical;
            try {
                canonical = fragmentCodec.decode(fragmentCodec.encode(fragment)).orElseThrow();
            } catch (RuntimeException malformed) {
                return Result.rejected(isOversized(fragment)
                    ? RejectionReason.OVERSIZED : RejectionReason.MALFORMED);
            }
            step = acceptCanonicalLocked(canonical, nowNanos);
        }
        return finishApplicationStep(step);
    }

    /** Decodes one bounded fragment wire payload before handing it to the assembler. */
    public Result acceptEncoded(byte[] encoded, long nowNanos) {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        LoginSyncSession.Role sessionRole = session.role();
        ApplicationStep step;
        synchronized (lifecycleLock) {
            if (closed || sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
                return Result.outcome(Outcome.CLOSED);
            }
            if (sessionRole != LoginSyncSession.Role.CLIENT) {
                return Result.rejected(RejectionReason.WRONG_ROLE);
            }
            if (!isHandshakeReady(sessionState)) {
                return Result.outcome(Outcome.NOT_READY);
            }
            if (encoded == null) {
                return Result.rejected(RejectionReason.MALFORMED);
            }
            if (encoded.length > fragmentCodec.maxEncodedBytes()) {
                return Result.rejected(RejectionReason.OVERSIZED);
            }
            Optional<QuestingFragment> decoded = fragmentCodec.decode(encoded);
            if (decoded.isEmpty()) {
                return Result.rejected(RejectionReason.MALFORMED);
            }
            step = acceptCanonicalLocked(decoded.orElseThrow(), nowNanos);
        }
        return finishApplicationStep(step);
    }

    /**
     * Retries only the application callback for a failure result produced by this instance.
     * The assembler has already retired the transfer id, so each failure result grants one retry;
     * another transient failure returns a fresh retryable result.
     */
    public Result retryApplication(Result failedApplication) {
        checkOwner();
        Objects.requireNonNull(failedApplication, "failedApplication");
        LoginSyncSession.State sessionState = session.state();
        LoginSyncSession.Role sessionRole = session.role();
        ApplicationStep step;
        synchronized (lifecycleLock) {
            if (closed || sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
                return Result.outcome(Outcome.CLOSED);
            }
            if (sessionRole != LoginSyncSession.Role.CLIENT) {
                return Result.rejected(RejectionReason.WRONG_ROLE);
            }
            if (!isHandshakeReady(sessionState)) {
                return Result.outcome(Outcome.NOT_READY);
            }
            if (applying) {
                closeStateLocked();
                step = ApplicationStep.closing();
            } else {
                byte[] payload = failedApplication.claimRetry(this);
                applying = true;
                step = ApplicationStep.apply(payload);
            }
        }
        return finishApplicationStep(step);
    }

    /** Returns zero without changing assembly state when monotonic time is negative or regresses. */
    public int expireIdle(long nowNanos) {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        synchronized (lifecycleLock) {
            if (closed || sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
                return 0;
            }
            if (nowNanos < 0L) {
                return 0;
            }
            try {
                return assembler.expireIdle(nowNanos);
            } catch (IllegalArgumentException regressedTime) {
                return 0;
            }
        }
    }

    public int activeTransferCount() {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        synchronized (lifecycleLock) {
            if (!closed && sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
            }
            return assembler.activeTransferCount();
        }
    }

    public boolean hasActiveAssemblies() {
        return activeTransferCount() != 0;
    }

    public long reservedBytes() {
        checkOwner();
        LoginSyncSession.State sessionState = session.state();
        synchronized (lifecycleLock) {
            if (!closed && sessionState == LoginSyncSession.State.CLOSED) {
                closeStateLocked();
            }
            return assembler.reservedBytes();
        }
    }

    public boolean isClosed() {
        checkOwner();
        boolean sessionClosed = session.isClosed();
        synchronized (lifecycleLock) {
            return closed || sessionClosed;
        }
    }

    @Override
    public void close() {
        session.close();
        closeForSession();
    }

    private ApplicationStep acceptCanonicalLocked(QuestingFragment fragment, long nowNanos) {
        if (applying) {
            closeStateLocked();
            return ApplicationStep.closing();
        }
        if (nowNanos < 0L) {
            return ApplicationStep.immediate(Result.rejected(RejectionReason.MALFORMED));
        }
        BoundedFragmentAssembler.Result assembled;
        try {
            assembled = assembler.accept(fragment, nowNanos);
        } catch (IllegalArgumentException malformedTime) {
            return ApplicationStep.immediate(Result.rejected(RejectionReason.MALFORMED));
        }
        return switch (assembled.outcome()) {
            case ACCEPTED -> ApplicationStep.immediate(Result.outcome(Outcome.ACCEPTED));
            case DUPLICATE -> ApplicationStep.immediate(Result.outcome(Outcome.DUPLICATE));
            case REJECTED -> ApplicationStep.immediate(
                Result.rejected(assembled.rejectionReason()));
            case COMPLETED -> prepareApplication(assembled.payload().orElseThrow());
        };
    }

    private ApplicationStep prepareApplication(byte[] payload) {
        applying = true;
        return ApplicationStep.apply(payload);
    }

    private Result finishApplicationStep(ApplicationStep step) {
        if (step.closeSession()) {
            session.close();
            return Result.outcome(Outcome.CLOSED);
        }
        if (step.payload() == null) {
            return step.result();
        }
        return publishCompleted(step.payload());
    }

    private Result publishCompleted(byte[] payload) {
        boolean succeeded;
        try {
            publication.publish(payload.clone());
            succeeded = true;
        } catch (RuntimeException failure) {
            succeeded = false;
        } catch (Error failure) {
            finishExceptionalApplication();
            throw failure;
        }
        boolean sessionClosed = session.isClosed();
        synchronized (lifecycleLock) {
            applying = false;
            if (sessionClosed) {
                closeStateLocked();
            }
            if (closed) {
                return Result.outcome(Outcome.CLOSED);
            }
            return succeeded
                ? Result.completed(payload)
                : Result.applicationFailed(payload, this);
        }
    }

    private void finishExceptionalApplication() {
        boolean sessionClosed = session.isClosed();
        synchronized (lifecycleLock) {
            applying = false;
            if (sessionClosed) {
                closeStateLocked();
            }
        }
    }

    private boolean isHandshakeReady(LoginSyncSession.State state) {
        return state == LoginSyncSession.State.READY || state == LoginSyncSession.State.PUBLISHED;
    }

    private boolean isOversized(QuestingFragment fragment) {
        return fragment.bytes().length > limits().maxFragmentBytes()
            || fragment.totalLength() > limits().maxTransferBytes()
            || fragment.fragmentCount() > limits().maxFragmentsPerTransfer();
    }

    private Result validateOutboundPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Result.rejected(RejectionReason.INVALID_PAYLOAD);
        }
        if (payload.length > limits().maxTransferBytes()) {
            return Result.rejected(RejectionReason.OVERSIZED);
        }
        return null;
    }

    private void closeForSession() {
        synchronized (lifecycleLock) {
            closeStateLocked();
        }
    }

    private void closeStateLocked() {
        if (!closed) {
            closed = true;
            applying = false;
            assembler.close();
        }
    }

    private long nextTransferId() {
        return nextTransferSequence++;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                "login sync bulk orchestrator accessed from a non-owner thread");
        }
    }

    private Packet transportPacket(LoginSyncFrame frame) {
        Packet packet = frame.direction() == LoginSyncFrame.Direction.CLIENT_TO_SERVER
            ? LoginSyncTransportPackets.c2s(frame)
            : LoginSyncTransportPackets.s2c(frame);
        if (LoginSyncTransportPackets.isRejected(packet)) {
            throw new IllegalStateException("login sync frame was rejected by transport codec");
        }
        return packet;
    }

    private Packet transportStartedHello(LoginSyncFrame frame) {
        try {
            return transportPacket(frame);
        } catch (RuntimeException | Error constructionFailure) {
            try {
                session.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != constructionFailure) {
                    constructionFailure.addSuppressed(cleanupFailure);
                }
            } finally {
                closeForSession();
            }
            throw constructionFailure;
        }
    }

    private record ApplicationStep(Result result, byte[] payload, boolean closeSession) {
        private ApplicationStep {
            payload = payload == null ? null : payload.clone();
        }

        private static ApplicationStep immediate(Result result) {
            return new ApplicationStep(Objects.requireNonNull(result, "result"), null, false);
        }

        private static ApplicationStep apply(byte[] payload) {
            return new ApplicationStep(null, Objects.requireNonNull(payload, "payload"), false);
        }

        private static ApplicationStep closing() {
            return new ApplicationStep(null, null, true);
        }
    }
}
