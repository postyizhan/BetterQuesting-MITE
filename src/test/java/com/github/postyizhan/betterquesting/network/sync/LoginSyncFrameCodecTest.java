package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.PacketLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragment;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHelloCodec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginSyncFrameCodecTest {
    @Test
    void helloAndSettingsFramesRoundTripWithDirectionAndToken() {
        UUID token = UUID.randomUUID();
        HandshakeHello hello = new HandshakeHello(token, new HandshakeCapabilities(1, 1, 1L, 0L));
        LoginSettingsSnapshot snapshot = snapshot();

        LoginSyncFrame clientHello = LoginSyncFrame.clientHello(hello);
        LoginSyncFrame serverHello = LoginSyncFrame.serverHello(hello);
        LoginSyncFrame settings = LoginSyncFrame.settings(token, snapshot);

        assertEquals(clientHello, LoginSyncFrameCodec.decode(LoginSyncFrameCodec.encode(clientHello)).orElseThrow());
        assertEquals(serverHello, LoginSyncFrameCodec.decode(LoginSyncFrameCodec.encode(serverHello)).orElseThrow());
        assertEquals(settings, LoginSyncFrameCodec.decode(LoginSyncFrameCodec.encode(settings)).orElseThrow());
        assertEquals(token, settings.connectionToken());
        assertArrayEquals(LoginSyncFrameCodec.encode(settings),
            LoginSyncFrameCodec.encode(LoginSyncFrameCodec.decode(
                LoginSyncFrameCodec.encode(settings)).orElseThrow()));
    }

    @Test
    void bulkFragmentIsTokenBoundServerToClientAndFillsTheExactEnvelope() {
        UUID token = UUID.randomUUID();
        byte[] fragmentBytes = new byte[LoginSyncProtocol.FRAGMENT_LIMITS.maxFragmentBytes()];
        QuestingFragment fragment = new QuestingFragment(
            41L, fragmentBytes.length, 0, 1, fragmentBytes);
        byte[] encodedFragment = LoginSyncProtocol.FRAGMENT_CODEC.encode(fragment);
        LoginSyncFrame frame = LoginSyncFrame.bulkFragment(token, encodedFragment);

        byte[] encoded = LoginSyncFrameCodec.encode(frame);

        assertEquals(LoginSyncFrame.Direction.SERVER_TO_CLIENT, frame.direction());
        assertEquals(LoginSyncFrame.Type.BULK_FRAGMENT, frame.type());
        assertEquals(token, frame.connectionToken());
        assertArrayEquals(encodedFragment, frame.bulkFragment().orElseThrow());
        assertEquals(PacketLimits.MAX_ENVELOPE_BYTES, encoded.length);
        assertEquals(frame, LoginSyncFrameCodec.decode(encoded).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> LoginSyncFrameCodec.encode(
            new LoginSyncFrame(
                LoginSyncFrame.Direction.CLIENT_TO_SERVER,
                LoginSyncFrame.Type.BULK_FRAGMENT,
                token,
                encodedFragment)));
        assertThrows(IllegalArgumentException.class, () -> LoginSyncFrameCodec.encode(
            new LoginSyncFrame(
                LoginSyncFrame.Direction.SERVER_TO_CLIENT,
                LoginSyncFrame.Type.BULK_FRAGMENT,
                token,
                new byte[encodedFragment.length])));
    }

    @Test
    void malformedOversizedWrongPayloadAndMismatchedHelloTokenAreRejected() {
        UUID token = UUID.randomUUID();
        LoginSyncFrame frame = LoginSyncFrame.clientHello(new HandshakeHello(
            token, new HandshakeCapabilities(1, 1, 0L, 0L)));
        byte[] encoded = LoginSyncFrameCodec.encode(frame);
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(LoginSyncFrameCodec.decode(Arrays.copyOf(encoded, length)).isEmpty());
        }
        assertTrue(LoginSyncFrameCodec.decode(new byte[LoginSyncFrameCodec.MAX_ENCODED_BYTES + 1]).isEmpty());

        byte[] wrongToken = encoded.clone();
        wrongToken[8] ^= 1;
        assertTrue(LoginSyncFrameCodec.decode(wrongToken).isEmpty());

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertTrue(LoginSyncFrameCodec.decode(trailing).isEmpty());

        byte[] unknownVersion = encoded.clone();
        unknownVersion[4] = 2;
        assertTrue(LoginSyncFrameCodec.decode(unknownVersion).isEmpty());

        byte[] unknownType = encoded.clone();
        unknownType[5] = (byte) LoginSyncFrame.Type.values().length;
        assertTrue(LoginSyncFrameCodec.decode(unknownType).isEmpty());

        byte[] unknownDirection = encoded.clone();
        unknownDirection[6] = 2;
        assertTrue(LoginSyncFrameCodec.decode(unknownDirection).isEmpty());

        byte[] negativePayloadLength = encoded.clone();
        ByteBuffer.wrap(negativePayloadLength).putInt(
            LoginSyncProtocol.LOGIN_FRAME_HEADER_BYTES - Integer.BYTES, -1);
        assertTrue(LoginSyncFrameCodec.decode(negativePayloadLength).isEmpty());

        byte[] oversizedPayloadLength = encoded.clone();
        ByteBuffer.wrap(oversizedPayloadLength).putInt(
            LoginSyncProtocol.LOGIN_FRAME_HEADER_BYTES - Integer.BYTES,
            LoginSyncFrame.MAX_PAYLOAD_BYTES + 1);
        assertTrue(LoginSyncFrameCodec.decode(oversizedPayloadLength).isEmpty());
    }

    @Test
    void codecAcceptsOnlyCanonicalTypeDirectionPairs() {
        UUID token = UUID.randomUUID();
        HandshakeHello hello = new HandshakeHello(
            token, new HandshakeCapabilities(1, 1, 0L, 0L));
        LoginSettingsSnapshot snapshot = snapshot();

        LoginSyncFrame wrongClientHello = new LoginSyncFrame(
            LoginSyncFrame.Direction.SERVER_TO_CLIENT,
            LoginSyncFrame.Type.CLIENT_HELLO,
            token,
            HandshakeHelloCodec.encode(hello));
        LoginSyncFrame wrongServerHello = new LoginSyncFrame(
            LoginSyncFrame.Direction.CLIENT_TO_SERVER,
            LoginSyncFrame.Type.SERVER_HELLO,
            token,
            HandshakeHelloCodec.encode(hello));
        LoginSyncFrame wrongSettings = new LoginSyncFrame(
            LoginSyncFrame.Direction.CLIENT_TO_SERVER,
            LoginSyncFrame.Type.SETTINGS,
            token,
            LoginSettingsSnapshotCodec.encode(snapshot));

        assertThrows(IllegalArgumentException.class,
            () -> LoginSyncFrameCodec.encode(wrongClientHello));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSyncFrameCodec.encode(wrongServerHello));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSyncFrameCodec.encode(wrongSettings));

        byte[] encoded = LoginSyncFrameCodec.encode(LoginSyncFrame.clientHello(hello));
        encoded[6] = (byte) LoginSyncFrame.Direction.SERVER_TO_CLIENT.ordinal();
        assertTrue(LoginSyncFrameCodec.decode(encoded).isEmpty());
    }

    @Test
    void frameOwnsItsBoundedPayload() {
        UUID token = UUID.randomUUID();
        byte[] payload = HandshakeHelloCodec.encode(
            new HandshakeHello(token, new HandshakeCapabilities(1, 1, 0L, 0L)));
        LoginSyncFrame frame = new LoginSyncFrame(
            LoginSyncFrame.Direction.CLIENT_TO_SERVER,
            LoginSyncFrame.Type.CLIENT_HELLO,
            token,
            payload);

        payload[0] = 0;
        byte[] returned = frame.payload();
        returned[0] = 0;
        assertTrue(frame.hello().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new LoginSyncFrame(
            LoginSyncFrame.Direction.CLIENT_TO_SERVER,
            LoginSyncFrame.Type.CLIENT_HELLO,
            token,
            new byte[LoginSyncFrame.MAX_PAYLOAD_BYTES + 1]));
    }

    private static LoginSettingsSnapshot snapshot() {
        return new LoginSettingsSnapshot(
            "Pack", 1, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }
}
