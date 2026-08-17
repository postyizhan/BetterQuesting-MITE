package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.network.BoundedNbtWireCodec;
import com.github.postyizhan.betterquesting.network.NbtLimits;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

/** Exact-schema bounded NBT codec for the current player's login name snapshot. */
public final class LoginNameSnapshotCodec {
    private static final String UUID_FIELD = "uuid";
    private static final String NAME_FIELD = "name";
    private static final int TAG_STRING = 8;
    private static final int MIN_ENCODED_BYTES = 59;

    public static final int MAX_ENCODED_BYTES = 74;
    private static final NbtLimits LIMITS = new NbtLimits(
        2,
        3,
        2,
        0,
        36,
        0,
        0,
        MAX_ENCODED_BYTES);

    private LoginNameSnapshotCodec() {
    }

    public static byte[] encode(LoginNameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        NBTTagCompound root = new NBTTagCompound();
        root.setString(UUID_FIELD, snapshot.playerId().toString());
        root.setString(NAME_FIELD, snapshot.displayName());
        byte[] encoded = BoundedNbtWireCodec.encode(root, LIMITS);
        if (encoded.length < MIN_ENCODED_BYTES || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("login name snapshot has an invalid wire size");
        }
        return encoded;
    }

    public static Optional<LoginNameSnapshot> decode(byte[] encoded) {
        if (encoded == null || encoded.length < MIN_ENCODED_BYTES
            || encoded.length > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }
        Optional<NBTTagCompound> decoded = BoundedNbtWireCodec.decode(encoded, LIMITS);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        NBTTagCompound root = decoded.orElseThrow();
        if (!root.getName().isEmpty()
            || root.getTags().size() != 2
            || NbtCompat.getTagId(root, UUID_FIELD) != TAG_STRING
            || NbtCompat.getTagId(root, NAME_FIELD) != TAG_STRING) {
            return Optional.empty();
        }

        String encodedUuid = root.getString(UUID_FIELD);
        try {
            UUID playerId = UUID.fromString(encodedUuid);
            if (!playerId.toString().equals(encodedUuid)) {
                return Optional.empty();
            }
            return Optional.of(new LoginNameSnapshot(playerId, root.getString(NAME_FIELD)));
        } catch (IllegalArgumentException invalidSnapshot) {
            return Optional.empty();
        }
    }
}
