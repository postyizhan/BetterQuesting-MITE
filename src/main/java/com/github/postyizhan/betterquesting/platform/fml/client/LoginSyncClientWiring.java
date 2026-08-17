package com.github.postyizhan.betterquesting.platform.fml.client;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.client.state.ClientChapterState;
import com.github.postyizhan.betterquesting.client.state.ClientLifeState;
import com.github.postyizhan.betterquesting.client.state.ClientPlayerNameState;
import com.github.postyizhan.betterquesting.client.state.ClientQuestSettingsState;
import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragment;
import com.github.postyizhan.betterquesting.network.sync.LoginBulkPayload;
import com.github.postyizhan.betterquesting.network.sync.LoginBulkPayloadCodec;
import com.github.postyizhan.betterquesting.network.sync.LoginSettingsSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncBulkSyncOrchestrator;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncConnectionOwner;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncFrame;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncProtocol;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncSession;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncTransportPackets;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import moddedmite.rustedironcore.network.Network;
import moddedmite.rustedironcore.network.Packet;
import net.minecraft.EntityClientPlayerMP;
import net.minecraft.EntityPlayer;
import net.minecraft.Minecraft;
import net.minecraft.NetClientHandler;
import net.minecraft.WorldClient;

public final class LoginSyncClientWiring {
    @FunctionalInterface
    interface Sender {
        void send(Packet packet);
    }

    private static final LoginSyncClientWiring PRODUCTION = new LoginSyncClientWiring(
        ClientQuestSettingsState.INSTANCE,
        ClientChapterState.INSTANCE,
        ClientLifeState.INSTANCE,
        ClientPlayerNameState.INSTANCE,
        () -> LoginSyncTransportPackets.registerClient(LoginSyncClientWiring::receiveFromRic),
        Network::sendToServer,
        LoginSyncProtocol.FRAGMENT_LIMITS);

    static {
        TickHandler.getInstance().registerClientTickHandler(
            minecraft -> PRODUCTION.tick(System.nanoTime()));
        WorldLoadHandler.getInstance().registerWorldLoadPostHandler(new IWorldLoadListener() {
            @Override
            public void onWorldLoadPost(
                WorldClient worldBefore,
                WorldClient worldAfter,
                Minecraft minecraft
            ) {
                PRODUCTION.worldChanged(worldBefore, worldAfter);
            }
        });
    }

    private final Object lifecycleLock = new Object();
    private final LoginSyncConnectionOwner owner;
    private final Runnable readerRegistration;
    private final Sender sender;
    private final FragmentAssemblyLimits fragmentLimits;
    private final LoginSyncBulkSyncOrchestrator.PayloadPublication bulkPublication;
    private final ClientChapterState chapterState;
    private final ClientLifeState lifeState;
    private final ClientPlayerNameState nameState;
    private final IdentityHashMap<Object, Binding> bindings = new IdentityHashMap<>();
    private Object currentWorld;
    private boolean readerRegistered;
    private boolean readerRegistrationInProgress;

    LoginSyncClientWiring(
        ClientQuestSettingsState settingsState,
        Runnable readerRegistration,
        Sender sender
    ) {
        this(settingsState, new ClientLifeState(), readerRegistration, sender);
    }

    LoginSyncClientWiring(
        ClientQuestSettingsState settingsState,
        ClientLifeState lifeState,
        Runnable readerRegistration,
        Sender sender
    ) {
        this(settingsState, lifeState, new ClientPlayerNameState(), readerRegistration, sender,
            LoginSyncProtocol.FRAGMENT_LIMITS);
    }

    LoginSyncClientWiring(
        ClientQuestSettingsState settingsState,
        ClientLifeState lifeState,
        Runnable readerRegistration,
        Sender sender,
        FragmentAssemblyLimits fragmentLimits
    ) {
        this(settingsState, lifeState, new ClientPlayerNameState(), readerRegistration, sender,
            fragmentLimits);
    }

    LoginSyncClientWiring(
        ClientQuestSettingsState settingsState,
        ClientChapterState chapterState,
        ClientLifeState lifeState,
        ClientPlayerNameState nameState,
        Runnable readerRegistration,
        Sender sender,
        FragmentAssemblyLimits fragmentLimits
    ) {
        this(
            settingsOwner(settingsState),
            readerRegistration,
            sender,
            fragmentLimits,
            null,
            Objects.requireNonNull(chapterState, "chapterState"),
            Objects.requireNonNull(lifeState, "lifeState"),
            Objects.requireNonNull(nameState, "nameState"));
    }

    LoginSyncClientWiring(
        ClientQuestSettingsState settingsState,
        ClientLifeState lifeState,
        ClientPlayerNameState nameState,
        Runnable readerRegistration,
        Sender sender,
        FragmentAssemblyLimits fragmentLimits
    ) {
        this(
            settingsOwner(settingsState),
            readerRegistration,
            sender,
            fragmentLimits,
            null,
            new ClientChapterState(),
            Objects.requireNonNull(lifeState, "lifeState"),
            Objects.requireNonNull(nameState, "nameState"));
    }

    LoginSyncClientWiring(
        LoginSyncConnectionOwner owner,
        Runnable readerRegistration,
        Sender sender
    ) {
        this(
            owner,
            readerRegistration,
            sender,
            LoginSyncProtocol.FRAGMENT_LIMITS,
            ignored -> { },
            null,
            null,
            null);
    }

    LoginSyncClientWiring(
        LoginSyncConnectionOwner owner,
        Runnable readerRegistration,
        Sender sender,
        FragmentAssemblyLimits fragmentLimits,
        LoginSyncBulkSyncOrchestrator.PayloadPublication bulkPublication
    ) {
        this(owner, readerRegistration, sender, fragmentLimits, bulkPublication, null, null, null);
    }

    private LoginSyncClientWiring(
        LoginSyncConnectionOwner owner,
        Runnable readerRegistration,
        Sender sender,
        FragmentAssemblyLimits fragmentLimits,
        LoginSyncBulkSyncOrchestrator.PayloadPublication bulkPublication,
        ClientChapterState chapterState,
        ClientLifeState lifeState,
        ClientPlayerNameState nameState
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.readerRegistration = Objects.requireNonNull(
            readerRegistration, "readerRegistration");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.fragmentLimits = Objects.requireNonNull(fragmentLimits, "fragmentLimits");
        if (bulkPublication == null && lifeState == null) {
            throw new NullPointerException("bulkPublication");
        }
        this.bulkPublication = bulkPublication;
        this.chapterState = chapterState;
        this.lifeState = lifeState;
        this.nameState = nameState;
        if (owner.role() != LoginSyncSession.Role.CLIENT) {
            throw new IllegalArgumentException("client wiring requires a client connection owner");
        }
    }

    private static LoginSyncConnectionOwner settingsOwner(
        ClientQuestSettingsState settingsState
    ) {
        Objects.requireNonNull(settingsState, "settingsState");
        return new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> createSettingsSession(role, settingsState));
    }

    private static LoginSyncSession createSettingsSession(
        LoginSyncSession.Role role,
        ClientQuestSettingsState settingsState
    ) {
        ClientQuestSettingsState.ConnectionLease lease = settingsState.openConnectionLease();
        try {
            return new LoginSyncSession(
                role,
                LoginSyncProtocol.CAPABILITIES,
                LoginSyncProtocol.LIMITS,
                lease::publish,
                lease::close);
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    public static void onLogin(NetClientHandler handler) {
        PRODUCTION.login(handler);
    }

    public static void onTerminal(NetClientHandler handler) {
        PRODUCTION.terminal(handler);
    }

    private static void receiveFromRic(EntityPlayer player, LoginSyncFrame frame) {
        if (player instanceof EntityClientPlayerMP clientPlayer) {
            PRODUCTION.receive(clientPlayer.sendQueue, frame);
        }
    }

    void login(Object handler) {
        if (handler == null) {
            return;
        }
        if (!ensureReaderRegistered()) {
            terminal(handler);
            return;
        }

        Binding existing = currentBinding(handler);
        if (existing != null) {
            return;
        }
        List<Binding> displaced;
        synchronized (lifecycleLock) {
            displaced = new ArrayList<>(bindings.values());
            bindings.clear();
        }
        for (Binding binding : displaced) {
            binding.releaseReplay();
        }

        LoginSyncSession session = null;
        try {
            session = owner.bind(handler);
            LoginSyncBulkSyncOrchestrator.PayloadPublication publication = bulkPublication;
            if (lifeState != null) {
                ClientChapterState.ConnectionLease chapterLease = chapterState.openConnectionLease();
                ClientLifeState.ConnectionLease lifeLease = lifeState.openConnectionLease();
                ClientPlayerNameState.ConnectionLease nameLease = nameState.openConnectionLease();
                session.addCloseHook(chapterLease::close);
                session.addCloseHook(lifeLease::close);
                session.addCloseHook(nameLease::close);
                publication = payload -> publishTyped(chapterLease, lifeLease, nameLease, payload);
            }
            LoginSyncBulkSyncOrchestrator orchestrator = new LoginSyncBulkSyncOrchestrator(
                session, fragmentLimits, publication);
            LoginSyncFrame helloFrame = orchestrator.startClientHello();
            Packet helloPacket = LoginSyncTransportPackets.c2s(helloFrame);
            if (LoginSyncTransportPackets.isRejected(helloPacket)) {
                throw new IllegalStateException("client login-sync hello was rejected by transport");
            }
            Binding candidate = new Binding(
                session,
                orchestrator,
                helloFrame,
                fragmentLimits.maxTrackedTransferIds(),
                fragmentLimits.maxReservedBytes());
            synchronized (lifecycleLock) {
                if (owner.current(handler).orElse(null) != session || !bindings.isEmpty()) {
                    throw new IllegalStateException("client login-sync binding changed during composition");
                }
                bindings.put(handler, candidate);
            }
            sender.send(helloPacket);
        } catch (RuntimeException | Error failure) {
            unbindExpected(handler, session);
            logFailure("start a client login-sync connection", failure);
        }
    }

    private static void publishTyped(
        ClientChapterState.ConnectionLease chapterLease,
        ClientLifeState.ConnectionLease lifeLease,
        ClientPlayerNameState.ConnectionLease nameLease,
        byte[] encoded
    ) {
        LoginBulkPayload payload = LoginBulkPayloadCodec.decode(encoded).orElseThrow(
            () -> new IllegalArgumentException("invalid typed login bulk payload"));
        if (payload.chapter() != null) {
            chapterLease.publish(payload.chapter());
        } else if (payload.life() != null) {
            lifeLease.publish(payload.life());
        } else {
            nameLease.publish(payload.name());
        }
    }

    void receive(Object handler, LoginSyncFrame frame) {
        receive(handler, frame, System.nanoTime());
    }

    void receive(Object handler, LoginSyncFrame frame, long nowNanos) {
        if (handler == null || frame == null
            || frame.direction() != LoginSyncFrame.Direction.SERVER_TO_CLIENT) {
            return;
        }
        Binding expected = currentBinding(handler);
        if (expected == null) {
            return;
        }
        try {
            if (!expected.session.connectionToken().orElseThrow()
                .equals(frame.connectionToken())) {
                unbindExpected(handler, expected.session);
                return;
            }
            if (frame.type() == LoginSyncFrame.Type.BULK_FRAGMENT) {
                receiveBulk(handler, expected, frame, nowNanos);
                return;
            }
            if (frame.type() != LoginSyncFrame.Type.SERVER_HELLO
                && frame.type() != LoginSyncFrame.Type.SETTINGS) {
                return;
            }

            Optional<LoginSyncSession.ReceiveResult> optional = owner.receive(handler, frame);
            if (optional.isEmpty()) {
                return;
            }
            LoginSyncSession.ReceiveResult result = optional.orElseThrow();
            if (result.outcome() == LoginSyncSession.Outcome.APPLICATION_FAILED
                && isCurrent(handler, expected)) {
                result = owner.receive(handler, frame).orElseThrow();
            }
            if (result.outcome() == LoginSyncSession.Outcome.APPLICATION_FAILED
                || result.outcome() == LoginSyncSession.Outcome.CONFLICT
                || expected.session.isClosed()) {
                unbindExpected(handler, expected.session);
            }
        } catch (RuntimeException | Error failure) {
            unbindExpected(handler, expected.session);
            logFailure("receive a client login-sync frame", failure);
        }
    }

    private void receiveBulk(
        Object handler,
        Binding expected,
        LoginSyncFrame frame,
        long nowNanos
    ) {
        if (expected.session.state() != LoginSyncSession.State.PUBLISHED) {
            unbindExpected(handler, expected.session);
            return;
        }
        byte[] encodedFragment = frame.bulkFragment().orElseThrow();
        Optional<QuestingFragment> decoded = expected.orchestrator.fragmentCodec().decode(
            encodedFragment);
        if (decoded.isEmpty()) {
            unbindExpected(handler, expected.session);
            return;
        }
        ReplayDecision replay = expected.replayLedger.observe(
            decoded.orElseThrow(), encodedFragment);
        if (replay == ReplayDecision.REPLAY) {
            return;
        }
        if (replay == ReplayDecision.REJECTED) {
            unbindExpected(handler, expected.session);
            return;
        }

        LoginSyncBulkSyncOrchestrator.Result result = expected.orchestrator.acceptEncoded(
            encodedFragment, nowNanos);
        if (result.outcome() == LoginSyncBulkSyncOrchestrator.Outcome.APPLICATION_FAILED
            && isCurrent(handler, expected)) {
            result = expected.orchestrator.retryApplication(result);
        }
        switch (result.outcome()) {
            case ACCEPTED, DUPLICATE, PUBLISHED -> {
            }
            default -> unbindExpected(handler, expected.session);
        }
    }

    void tick(long nowNanos) {
        List<HandlerBinding> current = new ArrayList<>();
        synchronized (lifecycleLock) {
            for (var entry : bindings.entrySet()) {
                current.add(new HandlerBinding(entry.getKey(), entry.getValue()));
            }
        }
        for (HandlerBinding candidate : current) {
            try {
                if (isCurrent(candidate.handler, candidate.binding)) {
                    candidate.binding.orchestrator.expireIdle(nowNanos);
                }
            } catch (RuntimeException | Error failure) {
                unbindExpected(candidate.handler, candidate.binding.session);
                logFailure("expire a client login-sync fragment assembly", failure);
            }
        }
    }

    void worldUnload() {
        unloadWorld(null, false);
    }

    void worldChanged(Object worldBefore, Object worldAfter) {
        if (worldAfter != null) {
            synchronized (lifecycleLock) {
                currentWorld = worldAfter;
            }
        } else if (worldBefore != null) {
            unloadWorld(worldBefore, true);
        }
    }

    private void unloadWorld(Object expectedWorld, boolean requireExpectedWorld) {
        List<HandlerBinding> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (requireExpectedWorld && currentWorld != null && currentWorld != expectedWorld) {
                return;
            }
            currentWorld = null;
            for (var entry : bindings.entrySet()) {
                removed.add(new HandlerBinding(entry.getKey(), entry.getValue()));
            }
            bindings.clear();
        }
        for (HandlerBinding candidate : removed) {
            candidate.binding.releaseReplay();
            unbindOwnerExpected(candidate.handler, candidate.binding.session);
        }
    }

    void terminal(Object handler) {
        if (handler == null) {
            return;
        }
        Binding removed;
        synchronized (lifecycleLock) {
            removed = bindings.remove(handler);
        }
        if (removed != null) {
            removed.releaseReplay();
        }
        try {
            owner.unbind(handler);
        } catch (RuntimeException | Error failure) {
            logFailure("tear down a client login-sync connection", failure);
        }
    }

    Optional<LoginSyncBulkSyncOrchestrator> currentOrchestrator(Object handler) {
        Binding binding = currentBinding(handler);
        return binding == null ? Optional.empty() : Optional.of(binding.orchestrator);
    }

    long retainedReplayBytes(Object handler) {
        Binding binding = currentBinding(handler);
        return binding == null ? 0L : binding.replayLedger.retainedBytes();
    }

    Optional<LoginSettingsSnapshot> currentSettings(Object handler) {
        Binding binding = currentBinding(handler);
        return binding == null ? Optional.empty() : binding.session.publishedSnapshot();
    }

    Optional<LoginSyncFrame> outboundHello(Object handler) {
        Binding binding = currentBinding(handler);
        return binding == null ? Optional.empty() : Optional.of(binding.outboundHello);
    }

    boolean isLifecycleLockHeldByCurrentThread() {
        return Thread.holdsLock(lifecycleLock);
    }

    private boolean ensureReaderRegistered() {
        synchronized (lifecycleLock) {
            if (readerRegistered) {
                return true;
            }
            if (readerRegistrationInProgress) {
                return false;
            }
            readerRegistrationInProgress = true;
        }
        boolean succeeded = false;
        try {
            readerRegistration.run();
            succeeded = true;
            return true;
        } catch (RuntimeException | Error failure) {
            logFailure("register the client login-sync reader", failure);
            return false;
        } finally {
            synchronized (lifecycleLock) {
                readerRegistered = succeeded;
                readerRegistrationInProgress = false;
            }
        }
    }

    private Binding currentBinding(Object handler) {
        if (handler == null) {
            return null;
        }
        Binding binding;
        synchronized (lifecycleLock) {
            binding = bindings.get(handler);
        }
        return binding != null && owner.current(handler).orElse(null) == binding.session
            ? binding
            : null;
    }

    private boolean isCurrent(Object handler, Binding expected) {
        synchronized (lifecycleLock) {
            if (bindings.get(handler) != expected) {
                return false;
            }
        }
        return owner.current(handler).orElse(null) == expected.session;
    }

    private void unbindExpected(Object handler, LoginSyncSession expected) {
        if (handler == null) {
            return;
        }
        Binding removed = null;
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            if (binding != null && (expected == null || binding.session == expected)) {
                removed = bindings.remove(handler);
            }
        }
        if (removed != null) {
            removed.releaseReplay();
        }
        unbindOwnerExpected(handler, expected);
    }

    private void unbindOwnerExpected(Object handler, LoginSyncSession expected) {
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

    private static final class Binding {
        private final LoginSyncSession session;
        private final LoginSyncBulkSyncOrchestrator orchestrator;
        private final LoginSyncFrame outboundHello;
        private final ReplayLedger replayLedger;

        private Binding(
            LoginSyncSession session,
            LoginSyncBulkSyncOrchestrator orchestrator,
            LoginSyncFrame outboundHello,
            int maxTrackedTransfers,
            long maxRetainedBytes
        ) {
            this.session = session;
            this.orchestrator = orchestrator;
            this.outboundHello = outboundHello;
            this.replayLedger = new ReplayLedger(maxTrackedTransfers, maxRetainedBytes);
        }

        private void releaseReplay() {
            replayLedger.clear();
        }
    }

    private enum ReplayDecision {
        INITIAL,
        REPLAY,
        REJECTED
    }

    // Assembler retirement expires; exact replay evidence must last for the live binding.
    private static final class ReplayLedger {
        private final int maxTrackedTransfers;
        private final long maxRetainedBytes;
        private final Map<Long, TransferReplay> transfers = new HashMap<>();
        private long retainedBytes;

        private ReplayLedger(int maxTrackedTransfers, long maxRetainedBytes) {
            this.maxTrackedTransfers = maxTrackedTransfers;
            this.maxRetainedBytes = maxRetainedBytes;
        }

        private synchronized ReplayDecision observe(QuestingFragment fragment, byte[] encoded) {
            TransferReplay transfer = transfers.get(fragment.transferId());
            boolean newTransfer = transfer == null;
            if (transfer == null) {
                if (transfers.size() >= maxTrackedTransfers) {
                    return ReplayDecision.REJECTED;
                }
                transfer = new TransferReplay(fragment.totalLength(), fragment.fragmentCount());
            } else if (!transfer.hasLayout(fragment)) {
                return ReplayDecision.REJECTED;
            }

            byte[] previous = transfer.encodedFragments[fragment.fragmentIndex()];
            if (previous != null) {
                if (!Arrays.equals(previous, encoded)) {
                    return ReplayDecision.REJECTED;
                }
                return transfer.completed ? ReplayDecision.REPLAY : ReplayDecision.INITIAL;
            }
            if (transfer.completed) {
                return ReplayDecision.REJECTED;
            }
            if (encoded.length > maxRetainedBytes - retainedBytes) {
                return ReplayDecision.REJECTED;
            }

            if (newTransfer) {
                transfers.put(fragment.transferId(), transfer);
            }
            transfer.encodedFragments[fragment.fragmentIndex()] = encoded.clone();
            transfer.receivedCount++;
            transfer.completed = transfer.receivedCount == transfer.encodedFragments.length;
            retainedBytes += encoded.length;
            return ReplayDecision.INITIAL;
        }

        private synchronized long retainedBytes() {
            return retainedBytes;
        }

        private synchronized void clear() {
            transfers.clear();
            retainedBytes = 0L;
        }
    }

    private static final class TransferReplay {
        private final int totalLength;
        private final byte[][] encodedFragments;
        private int receivedCount;
        private boolean completed;

        private TransferReplay(int totalLength, int fragmentCount) {
            this.totalLength = totalLength;
            this.encodedFragments = new byte[fragmentCount][];
        }

        private boolean hasLayout(QuestingFragment fragment) {
            return totalLength == fragment.totalLength()
                && encodedFragments.length == fragment.fragmentCount();
        }
    }

    private static final class HandlerBinding {
        private final Object handler;
        private final Binding binding;

        private HandlerBinding(Object handler, Binding binding) {
            this.handler = handler;
            this.binding = binding;
        }
    }
}
