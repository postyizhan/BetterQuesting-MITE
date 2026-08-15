package com.github.postyizhan.betterquesting.network.probe;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.network.PacketLimits;
import com.github.postyizhan.betterquesting.network.QuestingPacket;
import com.github.postyizhan.betterquesting.network.QuestingPacketCodec;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import moddedmite.rustedironcore.network.Network;
import moddedmite.rustedironcore.network.Packet;
import moddedmite.rustedironcore.network.PacketByteBuf;
import moddedmite.rustedironcore.network.PacketReader;
import net.minecraft.EntityPlayer;
import net.minecraft.ResourceLocation;
import net.minecraft.ServerPlayer;

public final class ProbePackets {
    static final String PROBE_PACKET_ID = BetterQuestingConstants.MOD_ID + ":nonce_probe";
    private static final int NONCE_BYTES = Integer.BYTES;

    private ProbePackets() {
    }

    public static void register() {
        PacketReader.registerServerPacketReader(BetterQuestingConstants.PROBE_C2S_CHANNEL,
                buffer -> readProbe(buffer, true));
        PacketReader.registerClientPacketReader(BetterQuestingConstants.PROBE_S2C_CHANNEL,
                buffer -> readProbe(buffer, false));
    }

    static Packet c2s(int nonce) {
        return new C2SProbePacket(nonce);
    }

    static Packet s2c(int nonce) {
        return new S2CProbePacket(nonce);
    }

    static boolean isRejected(Packet packet) {
        return packet instanceof RejectedProbePacket;
    }

    private static Packet readProbe(PacketByteBuf buffer, boolean serverbound) {
        ResourceLocation channel = serverbound
            ? BetterQuestingConstants.PROBE_C2S_CHANNEL
            : BetterQuestingConstants.PROBE_S2C_CHANNEL;
        try {
            DataInputStream input = buffer == null ? null : buffer.getInputStream();
            if (input == null) {
                return new RejectedProbePacket(channel);
            }
            byte[] encoded = input.readNBytes(PacketLimits.MAX_ENVELOPE_BYTES + 1);
            Optional<QuestingPacket> decoded = QuestingPacketCodec.decode(encoded);
            if (decoded.isEmpty()) {
                return new RejectedProbePacket(channel);
            }

            QuestingPacket packet = decoded.get();
            byte[] payload = packet.payload();
            if (!PROBE_PACKET_ID.equals(packet.id()) || payload.length != NONCE_BYTES) {
                return new RejectedProbePacket(channel);
            }
            int nonce = (payload[0] & 0xff) << 24
                | (payload[1] & 0xff) << 16
                | (payload[2] & 0xff) << 8
                | payload[3] & 0xff;
            return serverbound ? new C2SProbePacket(nonce) : new S2CProbePacket(nonce);
        } catch (IOException | RuntimeException expectedMalformedInput) {
            return new RejectedProbePacket(channel);
        }
    }

    private static void writeProbe(PacketByteBuf buffer, int nonce) {
        byte[] payload = {
            (byte) (nonce >>> 24),
            (byte) (nonce >>> 16),
            (byte) (nonce >>> 8),
            (byte) nonce
        };
        byte[] encoded = QuestingPacketCodec.encode(new QuestingPacket(PROBE_PACKET_ID, payload));
        buffer.write(encoded, 0, encoded.length);
    }

    private record C2SProbePacket(int nonce) implements Packet {
        @Override
        public void write(PacketByteBuf buffer) {
            writeProbe(buffer, nonce);
        }

        @Override
        public void apply(EntityPlayer player) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            BetterQuestingMod.LOGGER.info("C2S custom payload probe received nonce={}", nonce);
            Network.sendToClient(serverPlayer, new S2CProbePacket(nonce));
        }

        @Override
        public ResourceLocation getChannel() {
            return BetterQuestingConstants.PROBE_C2S_CHANNEL;
        }
    }

    private record S2CProbePacket(int nonce) implements Packet {
        @Override
        public void write(PacketByteBuf buffer) {
            writeProbe(buffer, nonce);
        }

        @Override
        public void apply(EntityPlayer player) {
            BetterQuestingMod.LOGGER.info("S2C custom payload probe received nonce={}", nonce);
        }

        @Override
        public ResourceLocation getChannel() {
            return BetterQuestingConstants.PROBE_S2C_CHANNEL;
        }
    }

    private record RejectedProbePacket(ResourceLocation channel) implements Packet {
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
