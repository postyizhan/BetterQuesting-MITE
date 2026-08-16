package com.github.postyizhan.betterquesting.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public final class BoundedNbtWireCodec {
    // The native reader recurses and only guards at 512.
    static final int MAX_MATERIALIZATION_DEPTH = 256;

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;

    private BoundedNbtWireCodec() {
    }

    public static byte[] encode(NBTTagCompound root, NbtLimits limits) {
        validateForEncoding(root, limits);

        try {
            NBTTagCompound detachedRoot = (NBTTagCompound) root.copy();
            NbtBounds.Result detachedValidation = validateForEncoding(detachedRoot, limits);
            if (!root.equals(detachedRoot)) {
                throw new IllegalArgumentException("NBT copy differs from the validated input");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            NBTBase.writeNamedTag(detachedRoot, output);
            output.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length != detachedValidation.serializedBytes()
                || !new Preflight(encoded, limits).scan()) {
                throw new IllegalArgumentException("NBT did not produce a valid bounded wire value");
            }
            return encoded;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) failure;
            }
            throw new IllegalArgumentException("NBT serialization failed", failure);
        }
    }

    private static NbtBounds.Result validateForEncoding(NBTTagCompound root, NbtLimits limits) {
        NbtBounds.Result validation = NbtBounds.validate(root, limits);
        if (!validation.isAccepted()) {
            throw new IllegalArgumentException("NBT rejected: " + validation.rejectionReason());
        }
        if (validation.maximumDepth() > MAX_MATERIALIZATION_DEPTH) {
            throw new IllegalArgumentException("NBT exceeds the safe materialization depth");
        }
        if (!hasHomogeneousLists(root)) {
            throw new IllegalArgumentException("NBT contains an invalid list");
        }
        if (validation.serializedBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("NBT is too large for a byte array");
        }
        return validation;
    }

    public static Optional<NBTTagCompound> decode(byte[] encoded, NbtLimits limits) {
        if (encoded == null || limits == null || !limits.isValid()
            || (long) encoded.length > limits.maxSerializedBytes()) {
            return Optional.empty();
        }

        byte[] detached = Arrays.copyOf(encoded, encoded.length);
        if (!new Preflight(detached, limits).scan()) {
            return Optional.empty();
        }

        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(detached);
            DataInputStream input = new DataInputStream(bytes);
            NBTBase decoded = NBTBase.readNamedTag(input);
            if (!(decoded instanceof NBTTagCompound) || bytes.available() != 0) {
                return Optional.empty();
            }

            NBTTagCompound root = (NBTTagCompound) decoded;
            NbtBounds.Result validation = NbtBounds.validate(root, limits);
            if (!validation.isAccepted() || validation.maximumDepth() > MAX_MATERIALIZATION_DEPTH
                || validation.serializedBytes() != detached.length) {
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static boolean hasHomogeneousLists(NBTTagCompound root) {
        try {
            Deque<NBTBase> pending = new ArrayDeque<NBTBase>();
            pending.push(root);
            while (!pending.isEmpty()) {
                NBTBase tag = pending.pop();
                int tagId = tag.getId() & 0xff;
                if (tagId == TAG_COMPOUND) {
                    Collection<?> children = ((NBTTagCompound) tag).getTags();
                    for (Object child : children) {
                        pending.push((NBTBase) child);
                    }
                } else if (tagId == TAG_LIST) {
                    NBTTagList list = (NBTTagList) tag;
                    int itemCount = list.tagCount();
                    if (itemCount == 0) {
                        continue;
                    }
                    int elementType = list.tagAt(0).getId() & 0xff;
                    if (!isPayloadTag(elementType)) {
                        return false;
                    }
                    for (int index = 0; index < itemCount; index++) {
                        NBTBase child = list.tagAt(index);
                        if ((child.getId() & 0xff) != elementType) {
                            return false;
                        }
                        pending.push(child);
                    }
                }
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean isPayloadTag(int tagId) {
        return tagId >= TAG_BYTE && tagId <= TAG_INT_ARRAY;
    }

    private static final class Preflight {
        private final byte[] bytes;
        private final NbtLimits limits;
        private final Deque<Frame> frames = new ArrayDeque<Frame>();
        private int offset;
        private long totalNodes;

        private Preflight(byte[] bytes, NbtLimits limits) {
            this.bytes = bytes;
            this.limits = limits;
        }

        private boolean scan() {
            if ((long) bytes.length > limits.maxSerializedBytes()) {
                return false;
            }
            int rootType = readTagId();
            if (rootType != TAG_COMPOUND || !readModifiedUtf() || !enter(rootType, 1)) {
                return false;
            }

            while (!frames.isEmpty()) {
                Frame frame = frames.peek();
                if (frame.list) {
                    if (frame.remaining == 0) {
                        frames.pop();
                        continue;
                    }
                    frame.remaining--;
                    if (!enter(frame.elementType, frame.childDepth)) {
                        return false;
                    }
                    continue;
                }

                int childType = readTagId();
                if (childType == TAG_END) {
                    frames.pop();
                    continue;
                }
                if (!isPayloadTag(childType) || frame.entries >= limits.maxCompoundEntries()) {
                    return false;
                }
                frame.entries++;
                if (!readModifiedUtf() || !enter(childType, frame.childDepth)) {
                    return false;
                }
            }
            return offset == bytes.length;
        }

        private boolean enter(int tagId, int depth) {
            if (!isPayloadTag(tagId) || depth > limits.maxDepth()
                || depth > MAX_MATERIALIZATION_DEPTH || totalNodes >= limits.maxTotalNodes()) {
                return false;
            }
            totalNodes++;

            switch (tagId) {
                case TAG_BYTE:
                    return skip(1L);
                case TAG_SHORT:
                    return skip(2L);
                case TAG_INT:
                case TAG_FLOAT:
                    return skip(4L);
                case TAG_LONG:
                case TAG_DOUBLE:
                    return skip(8L);
                case TAG_BYTE_ARRAY:
                    return scanByteArray();
                case TAG_STRING:
                    return readModifiedUtf();
                case TAG_LIST:
                    return enterList(depth);
                case TAG_COMPOUND:
                    frames.push(Frame.compound(depth + 1));
                    return true;
                case TAG_INT_ARRAY:
                    return scanIntArray();
                default:
                    return false;
            }
        }

        private boolean scanByteArray() {
            if (!hasRemaining(4)) {
                return false;
            }
            int length = readInt();
            return length >= 0 && length <= limits.maxByteArrayLength() && skip(length);
        }

        private boolean scanIntArray() {
            if (!hasRemaining(4)) {
                return false;
            }
            int length = readInt();
            return length >= 0 && length <= limits.maxIntArrayLength() && skip(4L * length);
        }

        private boolean enterList(int depth) {
            int elementType = readTagId();
            if (!isPayloadTag(elementType) || !hasRemaining(4)) {
                return false;
            }
            int itemCount = readInt();
            if (itemCount < 0 || itemCount > limits.maxListItems()
                || (long) itemCount > limits.maxTotalNodes() - totalNodes) {
                return false;
            }
            if (itemCount > 0) {
                frames.push(Frame.list(elementType, itemCount, depth + 1));
            }
            return true;
        }

        private boolean readModifiedUtf() {
            if (!hasRemaining(2)) {
                return false;
            }
            int byteLength = readUnsignedShort();
            if (!hasRemaining(byteLength)) {
                return false;
            }

            int end = offset + byteLength;
            int characters = 0;
            while (offset < end) {
                int first = bytes[offset] & 0xff;
                int width;
                if (first >= 0x01 && first <= 0x7f) {
                    width = 1;
                } else if (first == 0xc0) {
                    width = 2;
                } else if (first >= 0xc2 && first <= 0xdf) {
                    width = 2;
                } else if (first >= 0xe0 && first <= 0xef) {
                    width = 3;
                } else {
                    return false;
                }
                if (offset + width > end) {
                    return false;
                }
                for (int index = 1; index < width; index++) {
                    if ((bytes[offset + index] & 0xc0) != 0x80) {
                        return false;
                    }
                }
                int second = width > 1 ? bytes[offset + 1] & 0xff : 0;
                if ((first == 0xc0 && second != 0x80)
                    || (first == 0xe0 && second < 0xa0)) {
                    return false;
                }
                offset += width;
                characters++;
                if (characters > limits.maxStringLength()) {
                    return false;
                }
            }
            return true;
        }

        private int readTagId() {
            if (!hasRemaining(1)) {
                return -1;
            }
            // MITE stores positive tag IDs as 128 - id at every type boundary.
            int diskId = bytes[offset++];
            return diskId > 0 ? 128 - diskId : diskId;
        }

        private int readUnsignedShort() {
            int value = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
            offset += 2;
            return value;
        }

        private int readInt() {
            int value = ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
            offset += 4;
            return value;
        }

        private boolean skip(long count) {
            if (count < 0L || count > bytes.length - (long) offset) {
                return false;
            }
            offset += (int) count;
            return true;
        }

        private boolean hasRemaining(int count) {
            return count >= 0 && count <= bytes.length - offset;
        }
    }

    private static final class Frame {
        private final boolean list;
        private final int childDepth;
        private final int elementType;
        private int remaining;
        private int entries;

        private Frame(boolean list, int childDepth, int elementType, int remaining) {
            this.list = list;
            this.childDepth = childDepth;
            this.elementType = elementType;
            this.remaining = remaining;
        }

        private static Frame compound(int childDepth) {
            return new Frame(false, childDepth, TAG_END, 0);
        }

        private static Frame list(int elementType, int remaining, int childDepth) {
            return new Frame(true, childDepth, elementType, remaining);
        }
    }
}
