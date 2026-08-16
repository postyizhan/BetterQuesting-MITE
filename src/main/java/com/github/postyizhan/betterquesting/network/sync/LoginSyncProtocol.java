package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.PacketLimits;
import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragmentCodec;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;

public final class LoginSyncProtocol {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DATA_FORMAT_VERSION = 1;
    public static final int LOGIN_FRAME_HEADER_BYTES = Integer.BYTES + 3
        + Long.BYTES * 2 + Integer.BYTES;
    public static final int FRAGMENT_WIRE_HEADER_BYTES = QuestingFragmentCodec.HEADER_BYTES;
    public static final int MAX_BULK_FRAME_BYTES = PacketLimits.MAX_ENVELOPE_BYTES;
    public static final int MAX_FRAGMENT_BYTES = MAX_BULK_FRAME_BYTES
        - LOGIN_FRAME_HEADER_BYTES - FRAGMENT_WIRE_HEADER_BYTES;
    public static final int MAX_TRANSFER_BYTES = 8 * 1024 * 1024;
    public static final int MAX_FRAGMENTS_PER_TRANSFER =
        (MAX_TRANSFER_BYTES + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES;
    public static final int MAX_CONCURRENT_TRANSFERS = 2;
    public static final long MAX_RESERVED_BYTES = 40L * 1024L * 1024L;
    public static final int MAX_TRACKED_TRANSFER_IDS = 128;
    public static final long FRAGMENT_IDLE_TIMEOUT_NANOS = 30_000_000_000L;
    public static final HandshakeCapabilities CAPABILITIES = new HandshakeCapabilities(
        PROTOCOL_VERSION,
        DATA_FORMAT_VERSION,
        0L,
        0L);
    public static final HandshakeLimits LIMITS = new HandshakeLimits(63, 0L, 0L);
    public static final FragmentAssemblyLimits FRAGMENT_LIMITS = new FragmentAssemblyLimits(
        MAX_FRAGMENT_BYTES,
        MAX_TRANSFER_BYTES,
        MAX_FRAGMENTS_PER_TRANSFER,
        MAX_CONCURRENT_TRANSFERS,
        MAX_RESERVED_BYTES,
        MAX_TRACKED_TRANSFER_IDS,
        FRAGMENT_IDLE_TIMEOUT_NANOS);
    public static final QuestingFragmentCodec FRAGMENT_CODEC =
        new QuestingFragmentCodec(FRAGMENT_LIMITS);

    private LoginSyncProtocol() {
    }
}
