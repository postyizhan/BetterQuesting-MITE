package com.github.postyizhan.betterquesting.network.probe;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import moddedmite.rustedironcore.network.Network;
import moddedmite.rustedironcore.network.Packet;
import moddedmite.rustedironcore.network.PacketByteBuf;
import moddedmite.rustedironcore.network.PacketReader;
import net.minecraft.EntityPlayer;
import net.minecraft.ResourceLocation;
import net.minecraft.ServerPlayer;

public final class ProbePackets {
    private ProbePackets() {
    }

    public static void register() {
        PacketReader.registerServerPacketReader(BetterQuestingConstants.PROBE_C2S_CHANNEL,
                buffer -> new C2SProbePacket(buffer.readInt()));
        PacketReader.registerClientPacketReader(BetterQuestingConstants.PROBE_S2C_CHANNEL,
                buffer -> new S2CProbePacket(buffer.readInt()));
    }

    private record C2SProbePacket(int nonce) implements Packet {
        @Override
        public void write(PacketByteBuf buffer) {
            buffer.writeInt(nonce);
        }

        @Override
        public void apply(EntityPlayer player) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                throw new IllegalStateException("C2S probe received without a server player");
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
            buffer.writeInt(nonce);
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
}
