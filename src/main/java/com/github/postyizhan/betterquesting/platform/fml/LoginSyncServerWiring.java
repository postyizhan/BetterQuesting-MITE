package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.api.storage.ILifeDatabase;
import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
import com.github.postyizhan.betterquesting.network.sync.LoginBulkPayload;
import com.github.postyizhan.betterquesting.network.sync.LoginBulkPayloadCodec;
import com.github.postyizhan.betterquesting.network.sync.LoginLifeSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginSettingsSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncBulkSyncOrchestrator;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncConnectionOwner;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncFrame;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncProtocol;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncSession;
import com.github.postyizhan.betterquesting.network.sync.LoginSyncTransportPackets;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.storage.LifeDatabase;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
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

    @FunctionalInterface
    interface SettingsCapture {
        LoginSettingsSnapshot capture(Object serverOwner, Object handler, Object recipient);
    }

    @FunctionalInterface
    interface BulkPayloadSource {
        List<byte[]> capture(Object serverOwner, Object handler, Object recipient);
    }

    @FunctionalInterface
    interface TypedBulkPayloadSource {
        Optional<LoginBulkPayload> capture(Object serverOwner, Object handler, Object recipient);
    }

    private static final LoginSyncServerWiring PRODUCTION = new LoginSyncServerWiring(
        new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            LoginSyncProtocol.CAPABILITIES,
            LoginSyncProtocol.LIMITS),
        (recipient, packet) -> Network.sendToClient((ServerPlayer) recipient, packet),
        (serverOwner, handler, recipient) -> LoginSettingsSnapshot.capture(QuestSettings.INSTANCE),
        (serverOwner, handler, recipient) -> List.of(),
        LoginSyncServerWiring::captureProductionLifePayload,
        LoginSyncProtocol.FRAGMENT_LIMITS);

    private final Object lifecycleLock = new Object();
    private final LoginSyncConnectionOwner owner;
    private final Sender sender;
    private final SettingsCapture settingsCapture;
    private final BulkPayloadSource bulkPayloadSource;
    private final TypedBulkPayloadSource typedBulkPayloadSource;
    private final FragmentAssemblyLimits fragmentLimits;
    private final IdentityHashMap<Object, Binding> bindings = new IdentityHashMap<>();

    LoginSyncServerWiring(LoginSyncConnectionOwner owner, Sender sender) {
        this(
            owner,
            sender,
            (serverOwner, handler, recipient) ->
                LoginSettingsSnapshot.capture(QuestSettings.INSTANCE),
            (serverOwner, handler, recipient) -> List.of(),
            (serverOwner, handler, recipient) -> Optional.empty(),
            LoginSyncProtocol.FRAGMENT_LIMITS);
    }

    LoginSyncServerWiring(
        LoginSyncConnectionOwner owner,
        Sender sender,
        SettingsCapture settingsCapture,
        BulkPayloadSource bulkPayloadSource,
        FragmentAssemblyLimits fragmentLimits
    ) {
        this(
            owner,
            sender,
            settingsCapture,
            bulkPayloadSource,
            (serverOwner, handler, recipient) -> Optional.empty(),
            fragmentLimits);
    }

    LoginSyncServerWiring(
        LoginSyncConnectionOwner owner,
        Sender sender,
        SettingsCapture settingsCapture,
        BulkPayloadSource bulkPayloadSource,
        TypedBulkPayloadSource typedBulkPayloadSource,
        FragmentAssemblyLimits fragmentLimits
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.settingsCapture = Objects.requireNonNull(settingsCapture, "settingsCapture");
        this.bulkPayloadSource = Objects.requireNonNull(bulkPayloadSource, "bulkPayloadSource");
        this.typedBulkPayloadSource = Objects.requireNonNull(
            typedBulkPayloadSource, "typedBulkPayloadSource");
        this.fragmentLimits = Objects.requireNonNull(fragmentLimits, "fragmentLimits");
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
        if (server != null && handler != null) {
            PRODUCTION.bind(server, handler);
        }
    }

    public static void onPlayerLoggedOut(ServerPlayer player) {
        if (player != null) {
            PRODUCTION.unbind(player.mcServer, player.playerNetServerHandler);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        PRODUCTION.closeAll(server);
    }

    private static void receiveFromRic(EntityPlayer player, LoginSyncFrame frame) {
        if (player instanceof ServerPlayer serverPlayer) {
            PRODUCTION.receive(
                serverPlayer.mcServer,
                serverPlayer.playerNetServerHandler,
                serverPlayer,
                frame);
        }
    }

    void bind(Object serverOwner, Object handler) {
        bind(serverOwner, handler, false);
    }

    void rebind(Object serverOwner, Object handler) {
        bind(serverOwner, handler, true);
    }

    private void bind(Object serverOwner, Object handler, boolean force) {
        if (serverOwner == null || handler == null) {
            return;
        }
        Binding previous;
        synchronized (lifecycleLock) {
            previous = bindings.get(handler);
            if (!force && previous != null && previous.serverOwner == serverOwner
                && owner.current(serverOwner, handler).orElse(null) == previous.session) {
                return;
            }
            if (previous != null) {
                bindings.remove(handler);
            }
        }
        if (previous != null) {
            previous.releaseReplay();
        }

        LoginSyncSession session = null;
        try {
            session = force
                ? owner.rebind(serverOwner, handler)
                : owner.bind(serverOwner, handler);
            LoginSyncBulkSyncOrchestrator orchestrator = new LoginSyncBulkSyncOrchestrator(
                session, fragmentLimits);
            Binding candidate = new Binding(serverOwner, session, orchestrator);
            synchronized (lifecycleLock) {
                if (owner.current(serverOwner, handler).orElse(null) != session
                    || bindings.containsKey(handler)) {
                    throw new IllegalStateException("server login-sync binding changed during composition");
                }
                bindings.put(handler, candidate);
            }
        } catch (RuntimeException | Error failure) {
            unbindExpected(serverOwner, handler, session);
            logFailure("bind a server login-sync connection", failure);
        }
    }

    void receive(Object serverOwner, Object handler, Object recipient, LoginSyncFrame frame) {
        if (serverOwner == null || handler == null || recipient == null || frame == null
            || frame.type() != LoginSyncFrame.Type.CLIENT_HELLO
            || frame.direction() != LoginSyncFrame.Direction.CLIENT_TO_SERVER) {
            return;
        }

        Binding expected = currentBinding(serverOwner, handler);
        if (expected == null) {
            return;
        }
        try {
            Optional<LoginSyncSession.ReceiveResult> optional = owner.receive(handler, frame);
            if (optional.isEmpty()) {
                return;
            }
            LoginSyncSession.ReceiveResult received = optional.orElseThrow();
            if (!isCurrent(handler, expected)) {
                removeExpected(handler, expected);
                return;
            }
            if (received.response().isEmpty()) {
                if (expected.session.isClosed()) {
                    unbindExpected(serverOwner, handler, expected.session);
                }
                return;
            }

            List<LoginSyncFrame> outbound = received.outcome() == LoginSyncSession.Outcome.DUPLICATE
                ? expected.cachedOutbound()
                : prepareInitialOutbound(serverOwner, handler, recipient, expected,
                    received.response().orElseThrow());
            if (outbound == null || outbound.isEmpty()) {
                throw new IllegalStateException("server login-sync response cache is unavailable");
            }
            sendAll(recipient, handler, expected, outbound);
        } catch (RuntimeException | Error failure) {
            unbindExpected(serverOwner, handler, expected.session);
            logFailure("receive or respond to a server login-sync frame", failure);
        }
    }

    private List<LoginSyncFrame> prepareInitialOutbound(
        Object serverOwner,
        Object handler,
        Object recipient,
        Binding expected,
        LoginSyncFrame serverHello
    ) {
        LoginSettingsSnapshot settings = Objects.requireNonNull(
            settingsCapture.capture(serverOwner, handler, recipient),
            "settingsCapture returned null");
        if (!isCurrent(handler, expected)) {
            return List.of();
        }
        LoginSyncFrame settingsFrame = expected.orchestrator.sendSettings(settings);

        List<byte[]> payloads = Objects.requireNonNull(
            bulkPayloadSource.capture(serverOwner, handler, recipient),
            "bulkPayloadSource returned null");
        Optional<LoginBulkPayload> typedPayload = Objects.requireNonNull(
            typedBulkPayloadSource.capture(serverOwner, handler, recipient),
            "typedBulkPayloadSource returned null");
        int typedPayloadCount = typedPayload.isPresent() ? 1 : 0;
        if (payloads.size() > fragmentLimits.maxTrackedTransferIds() - typedPayloadCount) {
            throw new IllegalArgumentException("bulk payload source exceeds tracked transfer bound");
        }
        int payloadCount = payloads.size() + typedPayloadCount;

        List<byte[]> encodedPayloads = new ArrayList<>(payloadCount);
        encodedPayloads.addAll(payloads);
        typedPayload.map(LoginBulkPayloadCodec::encode).ifPresent(encodedPayloads::add);

        List<LoginSyncFrame> outbound = new ArrayList<>();
        long retainedReplayBytes = 0L;
        outbound.add(serverHello);
        outbound.add(settingsFrame);
        for (byte[] payload : encodedPayloads) {
            if (!isCurrent(handler, expected)) {
                return List.of();
            }
            LoginSyncBulkSyncOrchestrator.Result fragmented = expected.orchestrator.publish(payload);
            if (fragmented.outcome()
                != LoginSyncBulkSyncOrchestrator.Outcome.FRAGMENTS_CREATED) {
                throw new IllegalArgumentException("bulk payload source returned an invalid payload");
            }
            for (byte[] encoded : fragmented.encodedFragments(
                expected.orchestrator.fragmentCodec())) {
                if (encoded.length > fragmentLimits.maxReservedBytes() - retainedReplayBytes) {
                    throw new IllegalArgumentException(
                        "bulk payload source exceeds retained replay byte bound");
                }
                retainedReplayBytes += encoded.length;
                outbound.add(LoginSyncFrame.bulkFragment(
                    serverHello.connectionToken(), encoded));
            }
        }

        List<LoginSyncFrame> cached = List.copyOf(outbound);
        synchronized (lifecycleLock) {
            if (bindings.get(handler) != expected) {
                return List.of();
            }
            expected.captureReplay(cached, retainedReplayBytes);
        }
        return cached;
    }

    static Optional<LoginBulkPayload> captureLifePayload(
        PlayerIdentityResolution resolution,
        ILifeDatabase lives
    ) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(lives, "lives");
        return resolution.identity().map(identity -> LoginBulkPayload.life(
            LoginLifeSnapshot.capture(lives, identity.id())));
    }

    private static Optional<LoginBulkPayload> captureProductionLifePayload(
        Object serverOwner,
        Object handler,
        Object recipient
    ) {
        if (!(serverOwner instanceof MinecraftServer server)
            || !(recipient instanceof EntityPlayer player)) {
            return Optional.empty();
        }
        return ServerIdentityContext.current(server)
            .map(MitePlayerIdentityAdapter::new)
            .map(adapter -> adapter.resolve(player))
            .flatMap(resolution -> captureLifePayload(resolution, LifeDatabase.INSTANCE));
    }

    private void sendAll(
        Object recipient,
        Object handler,
        Binding expected,
        List<LoginSyncFrame> outbound
    ) {
        for (LoginSyncFrame frame : outbound) {
            if (!isCurrent(handler, expected)) {
                removeExpected(handler, expected);
                return;
            }
            Packet packet = LoginSyncTransportPackets.s2c(frame);
            if (LoginSyncTransportPackets.isRejected(packet)) {
                throw new IllegalStateException("server login-sync response was rejected by transport");
            }
            sender.send(recipient, packet);
        }
    }

    void unbind(Object serverOwner, Object handler) {
        if (serverOwner == null || handler == null) {
            return;
        }
        Binding removed = null;
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            if (binding != null && binding.serverOwner == serverOwner) {
                removed = bindings.remove(handler);
            }
        }
        if (removed != null) {
            removed.releaseReplay();
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
        List<Binding> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            var iterator = bindings.entrySet().iterator();
            while (iterator.hasNext()) {
                Binding binding = iterator.next().getValue();
                if (binding.serverOwner == serverOwner) {
                    removed.add(binding);
                    iterator.remove();
                }
            }
        }
        for (Binding binding : removed) {
            binding.releaseReplay();
        }
        try {
            owner.closeAll(serverOwner);
        } catch (RuntimeException | Error failure) {
            logFailure("close server login-sync connections", failure);
        }
    }

    Optional<LoginSyncBulkSyncOrchestrator> currentOrchestrator(
        Object serverOwner,
        Object handler
    ) {
        Binding binding = currentBinding(serverOwner, handler);
        return binding == null ? Optional.empty() : Optional.of(binding.orchestrator);
    }

    long retainedReplayBytes(Object serverOwner, Object handler) {
        Binding binding = currentBinding(serverOwner, handler);
        return binding == null ? 0L : binding.retainedReplayBytes();
    }

    boolean isLifecycleLockHeldByCurrentThread() {
        return Thread.holdsLock(lifecycleLock);
    }

    private Binding currentBinding(Object serverOwner, Object handler) {
        if (serverOwner == null || handler == null) {
            return null;
        }
        Binding binding;
        synchronized (lifecycleLock) {
            binding = bindings.get(handler);
            if (binding == null || binding.serverOwner != serverOwner) {
                return null;
            }
        }
        return owner.current(serverOwner, handler).orElse(null) == binding.session
            ? binding
            : null;
    }

    private boolean isCurrent(Object handler, Binding expected) {
        synchronized (lifecycleLock) {
            if (bindings.get(handler) != expected) {
                return false;
            }
        }
        return owner.current(expected.serverOwner, handler).orElse(null) == expected.session;
    }

    private void removeExpected(Object handler, Binding expected) {
        boolean removed = false;
        synchronized (lifecycleLock) {
            if (bindings.get(handler) == expected) {
                bindings.remove(handler);
                removed = true;
            }
        }
        if (removed) {
            expected.releaseReplay();
        }
    }

    private void unbindExpected(
        Object serverOwner,
        Object handler,
        LoginSyncSession expected
    ) {
        if (serverOwner == null || handler == null) {
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

    private static final class Binding {
        private final Object serverOwner;
        private final LoginSyncSession session;
        private final LoginSyncBulkSyncOrchestrator orchestrator;
        private List<LoginSyncFrame> cachedOutbound;
        private long retainedReplayBytes;

        private Binding(
            Object serverOwner,
            LoginSyncSession session,
            LoginSyncBulkSyncOrchestrator orchestrator
        ) {
            this.serverOwner = serverOwner;
            this.session = session;
            this.orchestrator = orchestrator;
        }

        private synchronized void captureReplay(
            List<LoginSyncFrame> outbound,
            long retainedBytes
        ) {
            if (cachedOutbound != null) {
                throw new IllegalStateException("server login-sync response was captured twice");
            }
            cachedOutbound = outbound;
            retainedReplayBytes = retainedBytes;
        }

        private synchronized List<LoginSyncFrame> cachedOutbound() {
            return cachedOutbound;
        }

        private synchronized long retainedReplayBytes() {
            return retainedReplayBytes;
        }

        private synchronized void releaseReplay() {
            cachedOutbound = null;
            retainedReplayBytes = 0L;
        }
    }
}
