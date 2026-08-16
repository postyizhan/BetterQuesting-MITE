package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.network.PacketLimits;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import moddedmite.rustedironcore.network.Packet;
import moddedmite.rustedironcore.network.PacketByteBuf;
import moddedmite.rustedironcore.network.PacketReader;
import net.minecraft.EntityPlayer;
import net.minecraft.ResourceLocation;

/** Direction-bound RIC transport for login sync frames. */
public final class LoginSyncTransportPackets {
    public static final ResourceLocation C2S_CHANNEL = new ResourceLocation(
        BetterQuestingConstants.MOD_ID, "lc2s", false);
    public static final ResourceLocation S2C_CHANNEL = new ResourceLocation(
        BetterQuestingConstants.MOD_ID, "ls2c", false);

    private LoginSyncTransportPackets() {
    }

    @FunctionalInterface
    public interface FrameReceiver {
        void receive(EntityPlayer player, LoginSyncFrame frame);
    }

    public static void register() {
        registerServer((player, frame) -> { });
        registerClient((player, frame) -> { });
    }

    public static void registerServer(FrameReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver");
        PacketReader.registerServerPacketReader(C2S_CHANNEL,
            buffer -> read(buffer, true, receiver));
    }

    public static void registerClient(FrameReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver");
        PacketReader.registerClientPacketReader(S2C_CHANNEL,
            buffer -> read(buffer, false, receiver));
    }

    public static Packet c2s(LoginSyncFrame frame) {
        return outbound(frame, true);
    }

    public static Packet s2c(LoginSyncFrame frame) {
        return outbound(frame, false);
    }

    public static boolean isRejected(Packet packet) {
        return packet instanceof RejectedPacket;
    }

    public static Optional<LoginSyncFrame> extract(Packet packet) {
        if (!(packet instanceof FramePacket framePacket)) {
            return Optional.empty();
        }
        Optional<LoginSyncFrame> decoded = LoginSyncFrameCodec.decode(framePacket.encoded);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        LoginSyncFrame frame = decoded.orElseThrow();
        ResourceLocation expectedChannel = channel(
            frame.direction() == LoginSyncFrame.Direction.CLIENT_TO_SERVER);
        return framePacket.channel == expectedChannel ? Optional.of(frame) : Optional.empty();
    }

    private static Packet outbound(LoginSyncFrame frame, boolean serverbound) {
        ResourceLocation channel = channel(serverbound);
        if (frame == null || frame.direction() != direction(serverbound)) {
            return new RejectedPacket(channel);
        }
        try {
            byte[] encoded = LoginSyncFrameCodec.encode(frame);
            if (encoded.length > PacketLimits.MAX_ENVELOPE_BYTES) {
                return new RejectedPacket(channel);
            }
            return new FramePacket(channel, encoded, null, null);
        } catch (RuntimeException invalidFrame) {
            return new RejectedPacket(channel);
        }
    }

    private static Packet read(
        PacketByteBuf buffer,
        boolean serverbound,
        FrameReceiver receiver
    ) {
        ResourceLocation channel = channel(serverbound);
        try {
            DataInputStream input = buffer == null ? null : buffer.getInputStream();
            if (input == null) {
                return new RejectedPacket(channel);
            }
            byte[] encoded = input.readNBytes(LoginSyncFrameCodec.MAX_ENCODED_BYTES + 1);
            Optional<LoginSyncFrame> decoded = LoginSyncFrameCodec.decode(encoded);
            if (decoded.isEmpty() || decoded.orElseThrow().direction() != direction(serverbound)) {
                return new RejectedPacket(channel);
            }
            return new FramePacket(channel, encoded, decoded.orElseThrow(), receiver);
        } catch (IOException | RuntimeException malformedInput) {
            return new RejectedPacket(channel);
        }
    }

    private static ResourceLocation channel(boolean serverbound) {
        return serverbound ? C2S_CHANNEL : S2C_CHANNEL;
    }

    private static LoginSyncFrame.Direction direction(boolean serverbound) {
        return serverbound
            ? LoginSyncFrame.Direction.CLIENT_TO_SERVER
            : LoginSyncFrame.Direction.SERVER_TO_CLIENT;
    }

    private static final class FramePacket implements Packet {
        private final ResourceLocation channel;
        private final byte[] encoded;
        private final LoginSyncFrame inboundFrame;
        private final FrameReceiver receiver;

        private FramePacket(
            ResourceLocation channel,
            byte[] encoded,
            LoginSyncFrame inboundFrame,
            FrameReceiver receiver
        ) {
            this.channel = channel;
            this.encoded = encoded.clone();
            this.inboundFrame = inboundFrame;
            this.receiver = receiver;
        }

        @Override
        public void write(PacketByteBuf buffer) {
            if (buffer != null) {
                buffer.write(encoded, 0, encoded.length);
            }
        }

        @Override
        public void apply(EntityPlayer player) {
            if (receiver == null || inboundFrame == null) {
                return;
            }
            try {
                receiver.receive(player, inboundFrame);
            } catch (RuntimeException | Error ignored) {
                // RIC does not isolate Packet.apply failures from the vanilla packet lifecycle.
            }
        }

        @Override
        public ResourceLocation getChannel() {
            return channel;
        }
    }

    private static final class RejectedPacket implements Packet {
        private final ResourceLocation channel;

        private RejectedPacket(ResourceLocation channel) {
            this.channel = channel;
        }

        @Override
        public void write(PacketByteBuf buffer) {
        }

        @Override
        public void apply(EntityPlayer player) {
        }

        @Override
        public ResourceLocation getChannel() {
            return channel;
        }
    }
}
