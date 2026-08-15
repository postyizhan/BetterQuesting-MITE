package com.github.postyizhan.betterquesting.network.probe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.BetterQuestingConstants;
import com.github.postyizhan.betterquesting.network.QuestingPacket;
import com.github.postyizhan.betterquesting.network.QuestingPacketCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import moddedmite.rustedironcore.network.Packet;
import moddedmite.rustedironcore.network.PacketByteBuf;
import moddedmite.rustedironcore.network.PacketReader;
import moddedmite.rustedironcore.network.PacketSupplier;
import net.minecraft.Packet250CustomPayload;
import net.minecraft.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProbePacketsTest {
    private static final int NONCE = 0x1234abcd;

    @BeforeEach
    void registerReaders() {
        ProbePackets.register();
    }

    @Test
    void channelNamesFitVanillaLimit() {
        assertEquals("betterquesting:pc2s", BetterQuestingConstants.PROBE_C2S_CHANNEL.toString());
        assertEquals("betterquesting:ps2c", BetterQuestingConstants.PROBE_S2C_CHANNEL.toString());
        assertTrue(BetterQuestingConstants.PROBE_C2S_CHANNEL.toString().length() <= 20);
        assertTrue(BetterQuestingConstants.PROBE_S2C_CHANNEL.toString().length() <= 20);
    }

    @Test
    void c2sProbeRoundTripsThroughRealCustomPayloadWire() throws Exception {
        Packet original = ProbePackets.c2s(NONCE);

        Packet decoded = wireRoundTrip(original, PacketReader.serverReaders);

        assertEquals(BetterQuestingConstants.PROBE_C2S_CHANNEL, decoded.getChannel());
        assertArrayEquals(original.toVanilla().data, decoded.toVanilla().data);
    }

    @Test
    void s2cProbeRoundTripsThroughRealCustomPayloadWire() throws Exception {
        Packet original = ProbePackets.s2c(NONCE);

        Packet decoded = wireRoundTrip(original, PacketReader.clientReaders);

        assertEquals(BetterQuestingConstants.PROBE_S2C_CHANNEL, decoded.getChannel());
        assertArrayEquals(original.toVanilla().data, decoded.toVanilla().data);
    }

    @Test
    void rejectsWrongIdBeforeApply() {
        byte[] encoded = QuestingPacketCodec.encode(new QuestingPacket("betterquesting:other", noncePayload()));

        assertRejected(readServer(encoded));
        assertRejected(readClient(encoded));
    }

    @Test
    void rejectsEmptyAndMalformedInputWithoutEscapingSupplier() {
        byte[] emptyNonce = QuestingPacketCodec.encode(
            new QuestingPacket(ProbePackets.PROBE_PACKET_ID, new byte[0]));

        assertRejected(assertDoesNotThrow(() -> readServer(new byte[0])));
        assertRejected(assertDoesNotThrow(() -> readClient(new byte[0])));
        assertRejected(assertDoesNotThrow(() -> readServer(emptyNonce)));
        assertRejected(assertDoesNotThrow(() -> readClient(emptyNonce)));
        assertRejected(assertDoesNotThrow(() -> readServer(new byte[]{1, 3, 'a'})));
        assertRejected(assertDoesNotThrow(() -> readClient(new byte[]{1, 3, 'a'})));
    }

    @Test
    void rejectsTrailingNonceData() {
        byte[] payload = {0x12, 0x34, (byte) 0xab, (byte) 0xcd, 0x00};
        byte[] encoded = QuestingPacketCodec.encode(new QuestingPacket(ProbePackets.PROBE_PACKET_ID, payload));

        assertRejected(readServer(encoded));
        assertRejected(readClient(encoded));
    }

    private static Packet wireRoundTrip(Packet source, Map<String, PacketSupplier> readers) throws IOException {
        Packet250CustomPayload outbound = source.toVanilla();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        outbound.writePacketData(new DataOutputStream(wire));

        Packet250CustomPayload inbound = new Packet250CustomPayload();
        inbound.readPacketData(new DataInputStream(new ByteArrayInputStream(wire.toByteArray())));
        assertEquals(outbound.channel, inbound.channel);
        assertEquals(outbound.length, inbound.length);
        assertArrayEquals(outbound.data, inbound.data);

        PacketSupplier supplier = readers.get(inbound.channel);
        assertNotNull(supplier);
        return supplier.readPacket(input(inbound.data));
    }

    private static Packet readServer(byte[] encoded) {
        PacketSupplier supplier = PacketReader.serverReaders.get(BetterQuestingConstants.PROBE_C2S_CHANNEL.toString());
        assertNotNull(supplier);
        return supplier.readPacket(input(encoded));
    }

    private static Packet readClient(byte[] encoded) {
        PacketSupplier supplier = PacketReader.clientReaders.get(BetterQuestingConstants.PROBE_S2C_CHANNEL.toString());
        assertNotNull(supplier);
        return supplier.readPacket(input(encoded));
    }

    private static PacketByteBuf input(byte[] bytes) {
        return PacketByteBuf.in(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static byte[] noncePayload() {
        return new byte[]{0x12, 0x34, (byte) 0xab, (byte) 0xcd};
    }

    private static void assertRejected(Packet packet) {
        assertTrue(ProbePackets.isRejected(packet));
        assertDoesNotThrow(() -> packet.apply(null));
        assertEquals(0, packet.toVanilla().data.length);
    }
}
