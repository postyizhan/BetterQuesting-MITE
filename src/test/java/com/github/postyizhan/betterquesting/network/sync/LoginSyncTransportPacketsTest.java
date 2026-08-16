package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.PacketLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragment;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import moddedmite.rustedironcore.network.Packet;
import moddedmite.rustedironcore.network.PacketByteBuf;
import moddedmite.rustedironcore.network.PacketReader;
import moddedmite.rustedironcore.network.PacketSupplier;
import net.minecraft.EntityPlayer;
import net.minecraft.Packet250CustomPayload;
import net.minecraft.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginSyncTransportPacketsTest {
    private static final UUID TOKEN = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @BeforeEach
    void registerReaders() {
        LoginSyncTransportPackets.registerServer((player, frame) -> { });
        LoginSyncTransportPackets.registerClient((player, frame) -> { });
    }

    @Test
    void registersDedicatedVanillaSafeChannelsOnTheirCorrectSides() {
        String c2s = LoginSyncTransportPackets.C2S_CHANNEL.toString();
        String s2c = LoginSyncTransportPackets.S2C_CHANNEL.toString();

        assertEquals("betterquesting:lc2s", c2s);
        assertEquals("betterquesting:ls2c", s2c);
        assertTrue(c2s.length() <= 20);
        assertTrue(s2c.length() <= 20);
        assertNotNull(PacketReader.serverReaders.get(c2s));
        assertNotNull(PacketReader.clientReaders.get(s2c));
        assertNull(PacketReader.clientReaders.get(c2s));
        assertNull(PacketReader.serverReaders.get(s2c));
    }

    @Test
    void ricClientRegistrationReplacesTheExistingChannelReader() {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger replacementCalls = new AtomicInteger();
        LoginSyncTransportPackets.registerClient(
            (player, frame) -> firstCalls.incrementAndGet());
        PacketSupplier first = PacketReader.clientReaders.get(
            LoginSyncTransportPackets.S2C_CHANNEL.toString());

        LoginSyncTransportPackets.registerClient(
            (player, frame) -> replacementCalls.incrementAndGet());
        PacketSupplier replacement = PacketReader.clientReaders.get(
            LoginSyncTransportPackets.S2C_CHANNEL.toString());

        assertNotSame(first, replacement);
        Packet packet = readClient(LoginSyncFrameCodec.encode(
            LoginSyncFrame.serverHello(hello())));
        assertDoesNotThrow(() -> packet.apply(null));
        assertEquals(0, firstCalls.get());
        assertEquals(1, replacementCalls.get());
    }

    @Test
    void validFramesRoundTripThroughRealCustomPayloadWire() throws Exception {
        LoginSyncFrame clientHello = LoginSyncFrame.clientHello(hello());
        LoginSyncFrame serverHello = LoginSyncFrame.serverHello(hello());
        LoginSyncFrame settings = LoginSyncFrame.settings(TOKEN, snapshot());
        LoginSyncFrame bulkFragment = bulkFragment(TOKEN, 7L, new byte[] {1, 2, 3});

        assertRoundTrip(
            clientHello,
            LoginSyncTransportPackets.c2s(clientHello),
            PacketReader.serverReaders);
        assertRoundTrip(
            serverHello,
            LoginSyncTransportPackets.s2c(serverHello),
            PacketReader.clientReaders);
        assertRoundTrip(
            settings,
            LoginSyncTransportPackets.s2c(settings),
            PacketReader.clientReaders);
        assertRoundTrip(
            bulkFragment,
            LoginSyncTransportPackets.s2c(bulkFragment),
            PacketReader.clientReaders);
    }

    @Test
    void extractedFramePayloadsRemainDefensivelyCopied() {
        LoginSyncFrame expected = LoginSyncFrame.settings(TOKEN, snapshot());
        Packet packet = LoginSyncTransportPackets.s2c(expected);

        LoginSyncFrame extracted = LoginSyncTransportPackets.extract(packet).orElseThrow();
        byte[] changedPayload = extracted.payload();
        changedPayload[0] ^= 1;

        assertArrayEquals(expected.payload(), extracted.payload());
        assertEquals(expected, LoginSyncTransportPackets.extract(packet).orElseThrow());
    }

    @Test
    void nullAndForeignPacketsCannotProduceFramesOrInvokePacketMethods() {
        assertTrue(assertDoesNotThrow(() -> LoginSyncTransportPackets.extract(null)).isEmpty());

        byte[] encoded = LoginSyncFrameCodec.encode(LoginSyncFrame.clientHello(hello()));
        ForeignPacket sameChannel = new ForeignPacket(
            LoginSyncTransportPackets.C2S_CHANNEL, encoded);
        ForeignPacket wrongChannel = new ForeignPacket(
            new ResourceLocation("betterquesting", "other", false), encoded);

        assertTrue(assertDoesNotThrow(
            () -> LoginSyncTransportPackets.extract(sameChannel)).isEmpty());
        assertTrue(assertDoesNotThrow(
            () -> LoginSyncTransportPackets.extract(wrongChannel)).isEmpty());
        assertEquals(0, sameChannel.invocations);
        assertEquals(0, wrongChannel.invocations);
    }

    @Test
    void malformedFramesAreRejectedWithoutThrowing() {
        byte[] clientHello = LoginSyncFrameCodec.encode(LoginSyncFrame.clientHello(hello()));
        byte[] serverHello = LoginSyncFrameCodec.encode(LoginSyncFrame.serverHello(hello()));

        assertRejected(assertDoesNotThrow(() -> readServer((byte[]) null)));
        assertRejected(assertDoesNotThrow(() -> readClient((byte[]) null)));
        assertRejected(assertDoesNotThrow(() -> readServer(new byte[0])));
        assertRejected(assertDoesNotThrow(() -> readClient(new byte[0])));
        assertRejected(assertDoesNotThrow(() -> readServer(
            new byte[LoginSyncFrameCodec.MAX_ENCODED_BYTES + 1])));
        assertRejected(assertDoesNotThrow(() -> readClient(
            new byte[LoginSyncFrameCodec.MAX_ENCODED_BYTES + 1])));

        assertRejected(readServer(changed(clientHello, 0, 0)));
        assertRejected(readServer(changed(clientHello, 4, 2)));
        assertRejected(readServer(changed(clientHello, 5, 0x7f)));
        assertRejected(readServer(changed(clientHello, 6, 0x7f)));
        assertRejected(readServer(changed(clientHello, 8, clientHello[8] ^ 1)));
        assertRejected(readServer(Arrays.copyOf(clientHello, clientHello.length + 1)));
        assertRejected(readClient(Arrays.copyOf(serverHello, serverHello.length + 1)));

        byte[] invalidSettings = LoginSyncFrameCodec.encode(
            LoginSyncFrame.settings(TOKEN, snapshot()));
        invalidSettings[27] = 0;
        assertRejected(readClient(invalidSettings));
    }

    @Test
    void packetsCannotCrossDirections() {
        byte[] c2s = LoginSyncFrameCodec.encode(LoginSyncFrame.clientHello(hello()));
        byte[] serverHello = LoginSyncFrameCodec.encode(LoginSyncFrame.serverHello(hello()));
        byte[] settings = LoginSyncFrameCodec.encode(LoginSyncFrame.settings(TOKEN, snapshot()));
        byte[] bulkFragment = LoginSyncFrameCodec.encode(
            bulkFragment(TOKEN, 8L, new byte[] {1}));

        assertRejected(readClient(c2s));
        assertRejected(readServer(serverHello));
        assertRejected(readServer(settings));
        assertRejected(readServer(bulkFragment));
    }

    @Test
    void nullStreamsAndDecodeFailuresBecomeInertRejections() {
        assertRejected(assertDoesNotThrow(() -> readServer((PacketByteBuf) null)));
        assertRejected(assertDoesNotThrow(() -> readClient((PacketByteBuf) null)));
        assertRejected(assertDoesNotThrow(() -> readServer(bufferReturning(null))));
        assertRejected(assertDoesNotThrow(() -> readClient(bufferReturning(null))));
        assertRejected(assertDoesNotThrow(() -> readServer(PacketByteBuf.in(
            new DataInputStream(failingInput())))));
        assertRejected(assertDoesNotThrow(() -> readClient(bufferThrowing(
            new IllegalStateException("decode failure")))));
    }

    @Test
    void exactMaximumFrameIsAcceptedAndOneTrailingByteIsRejected() {
        byte[] payload = new byte[LoginSyncProtocol.FRAGMENT_LIMITS.maxFragmentBytes()];
        byte[] encoded = LoginSyncFrameCodec.encode(bulkFragment(TOKEN, 9L, payload));

        assertEquals(LoginSyncFrameCodec.MAX_ENCODED_BYTES, encoded.length);
        assertFalse(LoginSyncTransportPackets.isRejected(readClient(encoded)));
        assertRejected(readClient(Arrays.copyOf(encoded, encoded.length + 1)));
    }

    @Test
    void onlyReaderCreatedPacketsDispatchToTheirDirectionOwnedReceiver() {
        AtomicInteger serverCalls = new AtomicInteger();
        AtomicInteger clientCalls = new AtomicInteger();
        AtomicReference<LoginSyncFrame> serverFrame = new AtomicReference<>();
        AtomicReference<LoginSyncFrame> clientFrame = new AtomicReference<>();
        LoginSyncTransportPackets.registerServer((player, frame) -> {
            serverCalls.incrementAndGet();
            serverFrame.set(frame);
        });
        LoginSyncTransportPackets.registerClient((player, frame) -> {
            clientCalls.incrementAndGet();
            clientFrame.set(frame);
        });
        LoginSyncFrame clientHello = LoginSyncFrame.clientHello(hello());
        LoginSyncFrame serverHello = LoginSyncFrame.serverHello(hello());

        Packet outboundClient = LoginSyncTransportPackets.c2s(clientHello);
        Packet outboundServer = LoginSyncTransportPackets.s2c(serverHello);
        assertDoesNotThrow(() -> outboundClient.apply(null));
        assertDoesNotThrow(() -> outboundServer.apply(null));
        assertEquals(0, serverCalls.get());
        assertEquals(0, clientCalls.get());

        Packet inboundClient = readServer(LoginSyncFrameCodec.encode(clientHello));
        Packet inboundServer = readClient(LoginSyncFrameCodec.encode(serverHello));
        assertDoesNotThrow(() -> inboundClient.apply(null));
        assertEquals(1, serverCalls.get());
        assertEquals(clientHello, serverFrame.get());
        assertEquals(0, clientCalls.get());
        assertDoesNotThrow(() -> inboundServer.apply(null));
        assertEquals(1, serverCalls.get());
        assertEquals(1, clientCalls.get());
        assertEquals(serverHello, clientFrame.get());

        assertDoesNotThrow(() -> readServer(new byte[0]).apply(null));
        assertDoesNotThrow(() -> readClient(new byte[0]).apply(null));
        assertEquals(1, serverCalls.get());
        assertEquals(1, clientCalls.get());
    }

    @Test
    void receiverFailuresCannotEscapeRicApply() {
        LoginSyncTransportPackets.registerServer((player, frame) -> {
            throw new AssertionError("server receiver failed");
        });
        LoginSyncTransportPackets.registerClient((player, frame) -> {
            throw new IllegalStateException("client receiver failed");
        });

        Packet serverbound = readServer(LoginSyncFrameCodec.encode(
            LoginSyncFrame.clientHello(hello())));
        Packet clientbound = readClient(LoginSyncFrameCodec.encode(
            LoginSyncFrame.serverHello(hello())));

        assertDoesNotThrow(() -> serverbound.apply(null));
        assertDoesNotThrow(() -> clientbound.apply(null));
    }

    @Test
    void outboundAndRejectedPacketsRemainInertAndFitTheEnvelope() {
        Packet c2s = LoginSyncTransportPackets.c2s(LoginSyncFrame.clientHello(hello()));
        Packet s2cHello = LoginSyncTransportPackets.s2c(LoginSyncFrame.serverHello(hello()));
        Packet s2cSettings = LoginSyncTransportPackets.s2c(LoginSyncFrame.settings(
            TOKEN,
            new LoginSettingsSnapshot(
                "p".repeat(LoginSettingsSnapshot.MAX_PACK_NAME_BYTES),
                1,
                true,
                false,
                false,
                3,
                10,
                "h".repeat(LoginSettingsSnapshot.MAX_HOME_IMAGE_BYTES),
                0.5F,
                0F,
                -128,
                0)));
        Packet s2cBulk = LoginSyncTransportPackets.s2c(bulkFragment(
            TOKEN,
            10L,
            new byte[LoginSyncProtocol.FRAGMENT_LIMITS.maxFragmentBytes()]));

        for (Packet packet : new Packet[]{c2s, s2cHello, s2cSettings, s2cBulk}) {
            byte[] before = packet.toVanilla().data.clone();
            assertFalse(LoginSyncTransportPackets.isRejected(packet));
            assertDoesNotThrow(() -> packet.apply(null));
            assertArrayEquals(before, packet.toVanilla().data);
            assertTrue(before.length <= PacketLimits.MAX_ENVELOPE_BYTES);
        }

        assertRejected(LoginSyncTransportPackets.c2s(LoginSyncFrame.serverHello(hello())));
        assertRejected(LoginSyncTransportPackets.s2c(LoginSyncFrame.clientHello(hello())));
        assertRejected(LoginSyncTransportPackets.c2s(null));
        assertRejected(LoginSyncTransportPackets.s2c(null));
    }

    private static LoginSyncFrame bulkFragment(UUID token, long transferId, byte[] payload) {
        QuestingFragment fragment = new QuestingFragment(
            transferId, payload.length, 0, 1, payload);
        return LoginSyncFrame.bulkFragment(
            token, LoginSyncProtocol.FRAGMENT_CODEC.encode(fragment));
    }

    private static void assertRoundTrip(
        LoginSyncFrame expected,
        Packet original,
        Map<String, PacketSupplier> readers
    ) throws IOException {
        assertEquals(expected, LoginSyncTransportPackets.extract(original).orElseThrow());
        Packet decoded = wireRoundTrip(original, readers);
        assertFalse(LoginSyncTransportPackets.isRejected(decoded));
        assertEquals(original.getChannel(), decoded.getChannel());
        assertArrayEquals(original.toVanilla().data, decoded.toVanilla().data);
        assertEquals(expected, LoginSyncTransportPackets.extract(decoded).orElseThrow());
        assertDoesNotThrow(() -> decoded.apply(null));
    }

    private static Packet wireRoundTrip(
        Packet source,
        Map<String, PacketSupplier> readers
    ) throws IOException {
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
        return readServer(encoded == null ? null : input(encoded));
    }

    private static Packet readClient(byte[] encoded) {
        return readClient(encoded == null ? null : input(encoded));
    }

    private static Packet readServer(PacketByteBuf buffer) {
        PacketSupplier supplier = PacketReader.serverReaders.get(
            LoginSyncTransportPackets.C2S_CHANNEL.toString());
        assertNotNull(supplier);
        return supplier.readPacket(buffer);
    }

    private static Packet readClient(PacketByteBuf buffer) {
        PacketSupplier supplier = PacketReader.clientReaders.get(
            LoginSyncTransportPackets.S2C_CHANNEL.toString());
        assertNotNull(supplier);
        return supplier.readPacket(buffer);
    }

    private static PacketByteBuf input(byte[] bytes) {
        return PacketByteBuf.in(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static PacketByteBuf bufferReturning(DataInputStream stream) {
        return (PacketByteBuf) Proxy.newProxyInstance(
            PacketByteBuf.class.getClassLoader(),
            new Class<?>[]{PacketByteBuf.class},
            (proxy, method, arguments) -> method.getName().equals("getInputStream")
                ? stream
                : null);
    }

    private static PacketByteBuf bufferThrowing(RuntimeException failure) {
        return (PacketByteBuf) Proxy.newProxyInstance(
            PacketByteBuf.class.getClassLoader(),
            new Class<?>[]{PacketByteBuf.class},
            (proxy, method, arguments) -> {
                throw failure;
            });
    }

    private static InputStream failingInput() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("decode failure");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("decode failure");
            }
        };
    }

    private static byte[] changed(byte[] source, int index, int value) {
        byte[] changed = source.clone();
        changed[index] = (byte) value;
        return changed;
    }

    private static HandshakeHello hello() {
        return new HandshakeHello(TOKEN, new HandshakeCapabilities(1, 1, 1L, 0L));
    }

    private static LoginSettingsSnapshot snapshot() {
        return new LoginSettingsSnapshot(
            "Pack", 1, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }

    private static void assertRejected(Packet packet) {
        assertTrue(LoginSyncTransportPackets.isRejected(packet));
        assertTrue(assertDoesNotThrow(
            () -> LoginSyncTransportPackets.extract(packet)).isEmpty());
        assertDoesNotThrow(() -> packet.apply(null));
        assertEquals(0, packet.toVanilla().data.length);
        assertTrue(packet.toVanilla().data.length <= PacketLimits.MAX_ENVELOPE_BYTES);
    }

    private static final class ForeignPacket implements Packet {
        private final ResourceLocation channel;
        private final byte[] encoded;
        private int invocations;

        private ForeignPacket(ResourceLocation channel, byte[] encoded) {
            this.channel = channel;
            this.encoded = encoded.clone();
        }

        @Override
        public void write(PacketByteBuf buffer) {
            invocations++;
            buffer.write(encoded, 0, encoded.length);
        }

        @Override
        public void apply(EntityPlayer player) {
            invocations++;
            throw new AssertionError("foreign packet apply must stay unreachable");
        }

        @Override
        public ResourceLocation getChannel() {
            invocations++;
            return channel;
        }
    }
}
