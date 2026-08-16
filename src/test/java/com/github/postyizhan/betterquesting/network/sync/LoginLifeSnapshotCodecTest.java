package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LoginLifeSnapshotCodecTest {
    @Test
    void roundTripsEveryAuthoritativeIntWithoutClampingOrReinterpretation() {
        for (int lives : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            LoginLifeSnapshot snapshot = new LoginLifeSnapshot(lives);

            assertEquals(snapshot, LoginLifeSnapshotCodec.decode(
                LoginLifeSnapshotCodec.encode(snapshot)).orElseThrow());
        }
    }

    @Test
    void rejectsMalformedTruncatedTrailingAndOversizedBodies() {
        byte[] encoded = LoginLifeSnapshotCodec.encode(new LoginLifeSnapshot(-17));

        assertTrue(LoginLifeSnapshotCodec.decode(null).isEmpty());
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(LoginLifeSnapshotCodec.decode(Arrays.copyOf(encoded, length)).isEmpty());
        }
        assertTrue(LoginLifeSnapshotCodec.decode(
            Arrays.copyOf(encoded, encoded.length + 1)).isEmpty());

        byte[] wrongMagic = encoded.clone();
        wrongMagic[0] ^= 1;
        assertTrue(LoginLifeSnapshotCodec.decode(wrongMagic).isEmpty());
        byte[] wrongVersion = encoded.clone();
        wrongVersion[Integer.BYTES]++;
        assertTrue(LoginLifeSnapshotCodec.decode(wrongVersion).isEmpty());
        assertThrows(NullPointerException.class, () -> LoginLifeSnapshotCodec.encode(null));
    }
}
