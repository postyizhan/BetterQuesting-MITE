package com.github.postyizhan.betterquesting.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.NBTTagByteArray;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagIntArray;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagString;
import org.junit.jupiter.api.Test;

class BoundedNbtWireCodecTest {
    private static final NbtLimits WIDE = limits(64, 10_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_048_576L);

    @Test
    void roundTripsCompoundRootsContainingEverySupportedTagId() {
        NBTTagCompound root = allTagsRoot();

        byte[] encoded = BoundedNbtWireCodec.encode(root, WIDE);
        Optional<NBTTagCompound> decoded = BoundedNbtWireCodec.decode(encoded, WIDE);

        assertEquals(Optional.of(root), decoded);
        assertArrayEquals(new byte[]{diskId(10), 0, 4}, Arrays.copyOf(encoded, 3));
    }

    @Test
    void encodesAnUncompressedNamedCompoundGoldenVector() {
        assertArrayEquals(new byte[]{diskId(10), 0, 0, 0},
            BoundedNbtWireCodec.encode(new NBTTagCompound(), WIDE));
    }

    @Test
    void validatesMaterializedInputBeforeNativeSerialization() {
        NBTTagCompound oversized = new NBTTagCompound();
        oversized.setTag("bytes", new NBTTagByteArray("", new byte[]{1, 2}));
        NbtLimits oneByte = limits(8, 8, 8, 8, 16, 1, 8, 1_024L);
        assertThrows(IllegalArgumentException.class, () -> BoundedNbtWireCodec.encode(oversized, oneByte));

        NBTTagCompound cycle = new NBTTagCompound();
        cycle.setTag("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> BoundedNbtWireCodec.encode(cycle, WIDE));

        NBTTagList mixed = new NBTTagList("");
        mixed.appendTag(new NBTTagInt("", 1));
        mixed.appendTag(new NBTTagString("", "two"));
        NBTTagCompound mixedRoot = new NBTTagCompound();
        mixedRoot.setTag("mixed", mixed);
        assertThrows(IllegalArgumentException.class, () -> BoundedNbtWireCodec.encode(mixedRoot, WIDE));

        assertThrows(IllegalArgumentException.class, () -> BoundedNbtWireCodec.encode(null, WIDE));
        assertThrows(IllegalArgumentException.class,
            () -> BoundedNbtWireCodec.encode(new NBTTagCompound(), null));
    }

    @Test
    void rejectsDetachedCopiesThatRenameHiddenCompoundEntries() {
        NBTTagInt child = new NBTTagInt("", 1);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("a", child);
        child.setName("b");

        assertThrows(IllegalArgumentException.class,
            () -> BoundedNbtWireCodec.encode(root, WIDE));
    }

    @Test
    void boundsExpandedDetachedCopiesBeforeSerialization() {
        NBTTagInt child = new NBTTagInt("", 1);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("hidden-compound-map-key", child);
        child.setName("x");
        long originalBytes = NbtBounds.validate(root, WIDE).serializedBytes();
        NbtLimits originalBudget = limits(8, 8, 8, 8, 64, 8, 8, originalBytes);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> BoundedNbtWireCodec.encode(root, originalBudget));

        assertEquals("NBT rejected: MAX_SERIALIZED_BYTES", failure.getMessage());
    }

    @Test
    void acceptsExactDepthAndNodeBoundariesAndRejectsOneOver() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound child = new NBTTagCompound();
        root.setTag("c", child);
        child.setInteger("i", 1);
        byte[] encoded = BoundedNbtWireCodec.encode(root, WIDE);

        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(3, 3, 1, 0, 1, 0, 0, encoded.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(2, 3, 1, 0, 1, 0, 0, encoded.length)).isEmpty());
        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(3, 2, 1, 0, 1, 0, 0, encoded.length)).isEmpty());
    }

    @Test
    void acceptsExactCompoundAndListBoundariesAndRejectsOneOver() {
        NBTTagCompound compound = new NBTTagCompound();
        compound.setInteger("a", 1);
        compound.setInteger("b", 2);
        byte[] compoundBytes = BoundedNbtWireCodec.encode(compound, WIDE);
        assertTrue(BoundedNbtWireCodec.decode(compoundBytes,
            limits(2, 3, 2, 0, 1, 0, 0, compoundBytes.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(compoundBytes,
            limits(2, 3, 1, 0, 1, 0, 0, compoundBytes.length)).isEmpty());

        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagInt("", 1));
        list.appendTag(new NBTTagInt("", 2));
        NBTTagCompound listRoot = new NBTTagCompound();
        listRoot.setTag("l", list);
        byte[] listBytes = BoundedNbtWireCodec.encode(listRoot, WIDE);
        assertTrue(BoundedNbtWireCodec.decode(listBytes,
            limits(3, 4, 1, 2, 1, 0, 0, listBytes.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(listBytes,
            limits(3, 4, 1, 1, 1, 0, 0, listBytes.length)).isEmpty());
    }

    @Test
    void acceptsExactStringAndArrayBoundariesAndRejectsOneOver() {
        NBTTagCompound stringRoot = new NBTTagCompound();
        stringRoot.setString("s", "abcd");
        byte[] stringBytes = BoundedNbtWireCodec.encode(stringRoot, WIDE);
        assertTrue(BoundedNbtWireCodec.decode(stringBytes,
            limits(2, 2, 1, 0, 4, 0, 0, stringBytes.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(stringBytes,
            limits(2, 2, 1, 0, 3, 0, 0, stringBytes.length)).isEmpty());

        NBTTagCompound byteArrayRoot = new NBTTagCompound();
        byteArrayRoot.setTag("b", new NBTTagByteArray("", new byte[]{1, 2}));
        byte[] byteArrayBytes = BoundedNbtWireCodec.encode(byteArrayRoot, WIDE);
        assertTrue(BoundedNbtWireCodec.decode(byteArrayBytes,
            limits(2, 2, 1, 0, 1, 2, 0, byteArrayBytes.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(byteArrayBytes,
            limits(2, 2, 1, 0, 1, 1, 0, byteArrayBytes.length)).isEmpty());

        NBTTagCompound intArrayRoot = new NBTTagCompound();
        intArrayRoot.setTag("i", new NBTTagIntArray("", new int[]{1, 2}));
        byte[] intArrayBytes = BoundedNbtWireCodec.encode(intArrayRoot, WIDE);
        assertTrue(BoundedNbtWireCodec.decode(intArrayBytes,
            limits(2, 2, 1, 0, 1, 0, 2, intArrayBytes.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(intArrayBytes,
            limits(2, 2, 1, 0, 1, 0, 1, intArrayBytes.length)).isEmpty());
    }

    @Test
    void acceptsTheExactSerializedByteBoundaryAndRejectsOneOver() {
        NBTTagCompound root = new NBTTagCompound();
        NbtLimits exact = limits(1, 1, 0, 0, 0, 0, 0, 4L);
        byte[] encoded = BoundedNbtWireCodec.encode(root, exact);

        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(1, 1, 0, 0, 0, 0, 0, encoded.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(1, 1, 0, 0, 0, 0, 0, encoded.length - 1L)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BoundedNbtWireCodec.encode(root,
            limits(1, 1, 0, 0, 0, 0, 0, 3L)));
    }

    @Test
    void countsModifiedUtfCharactersInsteadOfWireBytes() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("\u0800", 1);
        byte[] encoded = BoundedNbtWireCodec.encode(root, WIDE);

        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(2, 2, 1, 0, 1, 0, 0, encoded.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(encoded,
            limits(2, 2, 1, 0, 0, 0, 0, encoded.length)).isEmpty());
    }

    @Test
    void rejectsHostileArrayAndListLengthsWithoutMaterializingThem() {
        NbtLimits maximalDeclarations = limits(8, Long.MAX_VALUE, 8, Integer.MAX_VALUE,
            16, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);

        List<byte[]> hostile = List.of(
            compoundWithTag(7, "x", intBytes(-1)),
            compoundWithTag(7, "x", intBytes(Integer.MAX_VALUE)),
            compoundWithTag(11, "x", intBytes(-1)),
            compoundWithTag(11, "x", intBytes(0x4000_0000)),
            compoundWithTag(11, "x", intBytes(Integer.MAX_VALUE)),
            compoundWithTag(9, "x", concat(new byte[]{diskId(1)}, intBytes(-1))),
            compoundWithTag(9, "x", concat(new byte[]{diskId(1)}, intBytes(Integer.MAX_VALUE)))
        );

        for (byte[] encoded : hostile) {
            Optional<NBTTagCompound> decoded = assertDoesNotThrow(
                () -> BoundedNbtWireCodec.decode(encoded, maximalDeclarations));
            assertTrue(decoded.isEmpty());
        }
    }

    @Test
    void rejectsUnknownIdsNonCompoundRootsAndInvalidListElementTypes() {
        byte[] unknownRoot = namedRoot(12, new byte[0]);
        byte[] scalarRoot = namedRoot(3, intBytes(1));
        byte[] unknownChild = compoundWithTag(12, "x", new byte[0]);
        byte[] unknownListType = compoundWithTag(9, "x",
            concat(new byte[]{diskId(12)}, intBytes(0)));
        byte[] endListType = compoundWithTag(9, "x",
            concat(new byte[]{0}, intBytes(1)));

        for (byte[] encoded : List.of(unknownRoot, scalarRoot, unknownChild, unknownListType, endListType)) {
            assertTrue(BoundedNbtWireCodec.decode(encoded, WIDE).isEmpty());
        }
    }

    @Test
    void rejectsMalformedModifiedUtfTruncationAndTrailingBytes() {
        byte[] malformedRootName = new byte[]{diskId(10), 0, 1, (byte) 0xc2, 0};
        byte[] malformedString = compoundWithTag(8, "x", new byte[]{0, 1, (byte) 0xc2});
        List<byte[]> malformedUtf = List.of(
            malformedRootName,
            malformedString,
            rootWithRawName(new byte[]{0}),
            rootWithRawName(new byte[]{(byte) 0xc0, (byte) 0xaf}),
            rootWithRawName(new byte[]{(byte) 0xe0, (byte) 0x80, (byte) 0x80}),
            rootWithRawName(new byte[]{(byte) 0xc2, 0x20}),
            rootWithRawName(new byte[]{(byte) 0xf0, (byte) 0x90, (byte) 0x80, (byte) 0x80})
        );
        for (byte[] malformed : malformedUtf) {
            assertTrue(BoundedNbtWireCodec.decode(malformed, WIDE).isEmpty());
        }

        byte[] valid = BoundedNbtWireCodec.encode(allTagsRoot(), WIDE);
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertTrue(assertDoesNotThrow(() -> BoundedNbtWireCodec.decode(truncated, WIDE)).isEmpty(),
                "accepted truncation at byte " + length);
        }
        assertTrue(BoundedNbtWireCodec.decode(concat(valid, new byte[]{0}), WIDE).isEmpty());
    }

    @Test
    void publicDecodeFailsClosedForMalformedInputsAndRetainsNoPartialState() {
        List<byte[]> malformed = Arrays.asList(
            null,
            new byte[0],
            new byte[]{diskId(10)},
            new byte[]{diskId(10), 0, 0},
            new byte[]{(byte) 0xff, 0, 0},
            compoundWithTag(7, "x", intBytes(Integer.MAX_VALUE)),
            compoundWithTag(9, "x", concat(new byte[]{diskId(1)}, intBytes(Integer.MAX_VALUE)))
        );
        for (byte[] encoded : malformed) {
            Optional<NBTTagCompound> result = assertDoesNotThrow(
                () -> BoundedNbtWireCodec.decode(encoded, WIDE));
            assertFalse(result.isPresent());
        }

        byte[] valid = BoundedNbtWireCodec.encode(new NBTTagCompound(), WIDE);
        assertTrue(BoundedNbtWireCodec.decode(valid, WIDE).isPresent());
        assertTrue(assertDoesNotThrow(() -> BoundedNbtWireCodec.decode(valid, null)).isEmpty());
    }

    @Test
    void encodedBytesAndDecodedTreesAreDetachedFromCallerMutation() {
        byte[] source = {1, 2, 3};
        NBTTagByteArray sourceTag = new NBTTagByteArray("", source);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("bytes", sourceTag);
        NBTTagList empty = new NBTTagList("");
        root.setTag("empty", empty);
        NBTTagCompound beforeEncoding = (NBTTagCompound) root.copy();

        byte[] encoded = BoundedNbtWireCodec.encode(root, WIDE);
        assertEquals(beforeEncoding, root);
        source[0] = 9;
        sourceTag.byteArray[1] = 9;
        NBTTagCompound first = BoundedNbtWireCodec.decode(encoded, WIDE).orElseThrow();
        NBTTagCompound second = BoundedNbtWireCodec.decode(encoded, WIDE).orElseThrow();
        encoded[encoded.length - 1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, ((NBTTagByteArray) first.getTag("bytes")).byteArray);
        ((NBTTagByteArray) first.getTag("bytes")).byteArray[0] = 7;
        assertArrayEquals(new byte[]{1, 2, 3}, ((NBTTagByteArray) second.getTag("bytes")).byteArray);
    }

    @Test
    void scannerCapsDepthBeforeInvokingTheRecursiveNativeParser() {
        byte[] atLimit = nestedCompounds(BoundedNbtWireCodec.MAX_MATERIALIZATION_DEPTH);
        byte[] tooDeep = nestedCompounds(BoundedNbtWireCodec.MAX_MATERIALIZATION_DEPTH + 1);

        assertTrue(BoundedNbtWireCodec.decode(atLimit,
            limits(512, 1_000, 1, 0, 0, 0, 0, atLimit.length)).isPresent());
        assertTrue(BoundedNbtWireCodec.decode(tooDeep,
            limits(512, 1_000, 1, 0, 0, 0, 0, tooDeep.length)).isEmpty());
    }

    private static NBTTagCompound allTagsRoot() {
        NBTTagCompound root = new NBTTagCompound("root");
        root.setByte("byte", (byte) -4);
        root.setShort("short", (short) 5);
        root.setInteger("int", 6);
        root.setLong("long", 7L);
        root.setFloat("float", 8.25F);
        root.setDouble("double", 9.5D);
        root.setTag("bytes", new NBTTagByteArray("", new byte[]{10, 11}));
        root.setString("string", "value\u0000\u0800");

        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagInt("", 12));
        list.appendTag(new NBTTagInt("", 13));
        root.setTag("list", list);

        NBTTagCompound nested = new NBTTagCompound();
        nested.setInteger("child", 14);
        root.setTag("compound", nested);
        root.setTag("ints", new NBTTagIntArray("", new int[]{15, 16}));
        return root;
    }

    private static byte[] namedRoot(int tagId, byte[] payload) {
        return concat(new byte[]{diskId(tagId), 0, 0}, payload);
    }

    private static byte[] rootWithRawName(byte[] name) {
        return concat(new byte[]{diskId(10), 0, (byte) name.length}, name, new byte[]{0});
    }

    private static byte[] compoundWithTag(int tagId, String name, byte[] payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(diskId(10));
            output.writeUTF("");
            output.writeByte(diskId(tagId));
            output.writeUTF(name);
            output.write(payload);
            output.writeByte(0);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] nestedCompounds(int depth) {
        byte[] encoded = new byte[3 + (depth - 1) * 3 + depth];
        int offset = 0;
        encoded[offset++] = diskId(10);
        encoded[offset++] = 0;
        encoded[offset++] = 0;
        for (int index = 1; index < depth; index++) {
            encoded[offset++] = diskId(10);
            encoded[offset++] = 0;
            encoded[offset++] = 0;
        }
        while (offset < encoded.length) {
            encoded[offset++] = 0;
        }
        return encoded;
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value
        };
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }

    private static byte diskId(int tagId) {
        return (byte) (tagId == 0 ? 0 : 128 - tagId);
    }

    private static NbtLimits limits(
        int depth,
        long nodes,
        int compoundEntries,
        int listItems,
        int stringLength,
        int byteArrayLength,
        int intArrayLength,
        long serializedBytes
    ) {
        return new NbtLimits(depth, nodes, compoundEntries, listItems, stringLength,
            byteArrayLength, intArrayLength, serializedBytes);
    }
}
