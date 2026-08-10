package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;
import net.minecraft.NBTTagByteArray;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagDouble;
import net.minecraft.NBTTagEnd;
import net.minecraft.NBTTagFloat;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagIntArray;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagLong;
import net.minecraft.NBTTagShort;
import net.minecraft.NBTTagString;
import org.junit.jupiter.api.Test;

/**
 * Exercises the codec against upstream {@code NBTConverter}'s exact textual contract.
 *
 * <p>These are not pure JVM unit tests: the domain layer exposes {@code net.minecraft.NBT*}
 * directly (handoff.md 8), so every assertion here needs the Gradle Minecraft classpath.
 */
class NbtJsonCodecTest {
    private final NbtJsonCodec codec = new NbtJsonCodec();

    // ------------------------------------------------------------ exact textual form

    @Test
    void formatModeSuffixesEveryCompoundKeyWithItsTagId() {
        NBTTagCompound root = new NBTTagCompound();
        root.setByte("b", (byte) 1);
        root.setShort("s", (short) 2);
        root.setInteger("i", 3);
        root.setLong("l", 4L);
        root.setFloat("f", 5.5f);
        root.setDouble("d", 6.5d);
        root.setString("str", "x");

        assertEquals("{\"b:1\":1,\"d:6\":6.5,\"f:5\":5.5,\"i:3\":3,\"l:4\":4,\"s:2\":2,\"str:8\":\"x\"}",
            codec.toJson(root, true).toString());
    }

    @Test
    void plainModeUsesBareKeysAndLosesTypeInformation() {
        NBTTagCompound root = new NBTTagCompound();
        root.setShort("s", (short) 2);
        root.setString("str", "x");

        assertEquals("{\"s\":2,\"str\":\"x\"}", codec.toJson(root, false).toString());
    }

    @Test
    void formatModeEncodesListAsObjectKeyedByIndexAndElementId() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList("");
        NBTTagCompound element = new NBTTagCompound();
        element.setInteger("n", 7);
        list.appendTag(element);
        list.appendTag(new NBTTagString("", "tail"));
        root.setTag("entries", list);

        assertEquals("{\"entries:9\":{\"0:10\":{\"n:3\":7},\"1:8\":\"tail\"}}",
            codec.toJson(root, true).toString());
    }

    @Test
    void plainModeEncodesListAsArray() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagInt("", 7));
        list.appendTag(new NBTTagInt("", 8));
        root.setTag("entries", list);

        assertEquals("{\"entries\":[7,8]}", codec.toJson(root, false).toString());
    }

    @Test
    void keysAreSortedSoOutputBytesAreStable() {
        NBTTagCompound inOneOrder = new NBTTagCompound();
        inOneOrder.setInteger("zebra", 1);
        inOneOrder.setInteger("alpha", 2);
        inOneOrder.setInteger("mid", 3);
        NBTTagCompound inAnother = new NBTTagCompound();
        inAnother.setInteger("mid", 3);
        inAnother.setInteger("zebra", 1);
        inAnother.setInteger("alpha", 2);

        assertEquals("{\"alpha:3\":2,\"mid:3\":3,\"zebra:3\":1}", codec.toJson(inOneOrder, true).toString());
        assertEquals(codec.toJson(inOneOrder, true).toString(), codec.toJson(inAnother, true).toString());
    }

    @Test
    void streamingWriterMatchesTheElementPathByteForByte() throws IOException {
        NBTTagCompound root = populatedRoot();

        assertEquals(codec.toJson(root, true).toString(), compact(root, true));
        assertEquals(codec.toJson(root, false).toString(), compact(root, false));
    }

    @Test
    void streamingWriterIndentsWithTabsLikeUpstreamJsonHelper() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("n", 1);

        assertEquals("{\n\t\"n:3\": 1\n}", indented(root, true));
    }

    @Test
    void streamingWriterClosesPlainModeListArrays() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagInt("", 1));
        root.setTag("entries", list);
        root.setInteger("after", 2);

        // Upstream NBTConverter.java:166-171 omits endArray(), which would swallow "after".
        assertEquals("{\"after\":2,\"entries\":[1]}", compact(root, false));
    }

    // ------------------------------------------------------------------- byte and int arrays

    @Test
    void arraysAreUntaggedArraysInBothDialects() {
        NBTTagCompound root = new NBTTagCompound();
        root.setByteArray("bytes", new byte[] {1, -2, 127, -128});
        root.setIntArray("ints", new int[] {5, -6, Integer.MAX_VALUE, Integer.MIN_VALUE});

        assertEquals("{\"bytes:7\":[1,-2,127,-128],\"ints:11\":[5,-6,2147483647,-2147483648]}",
            codec.toJson(root, true).toString());
        assertEquals("{\"bytes\":[1,-2,127,-128],\"ints\":[5,-6,2147483647,-2147483648]}",
            codec.toJson(root, false).toString());
    }

    @Test
    void formatModeRestoresArrayTypesFromTheKeySuffix() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setByteArray("bytes", new byte[] {1, -2});
        root.setIntArray("ints", new int[] {5, -6});

        NBTTagCompound restored = roundTrip(root, true);

        assertEquals(7, NbtCompat.getTagId(restored, "bytes"));
        assertEquals(11, NbtCompat.getTagId(restored, "ints"));
        assertArrayEquals(new byte[] {1, -2}, restored.getByteArray("bytes"));
        assertArrayEquals(new int[] {5, -6}, restored.getIntArray("ints"));
    }

    @Test
    void plainModeCannotDistinguishArraysFromListsAndResolvesToTagList() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setByteArray("bytes", new byte[] {1, -2});
        root.setIntArray("ints", new int[] {5, -6});

        NBTTagCompound restored = roundTrip(root, false);

        // fallbackTagId returns 9 for every array (NBTConverter.java:474-510), so the element type
        // degrades to long: the only remaining fidelity is the numeric value.
        assertEquals(9, NbtCompat.getTagId(restored, "bytes"));
        assertEquals(9, NbtCompat.getTagId(restored, "ints"));
        assertEquals(List.of(1L, -2L), longsOf(restored.getTagList("bytes")));
        assertEquals(List.of(5L, -6L), longsOf(restored.getTagList("ints")));
    }

    @Test
    void emptyArraysSurviveFormatModeRoundTrip() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setByteArray("bytes", new byte[0]);
        root.setIntArray("ints", new int[0]);

        assertEquals("{\"bytes:7\":[],\"ints:11\":[]}", codec.toJson(root, true).toString());

        NBTTagCompound restored = roundTrip(root, true);
        assertEquals(0, restored.getByteArray("bytes").length);
        assertEquals(0, restored.getIntArray("ints").length);
    }

    // ------------------------------------------------------------------------ round trips

    @Test
    void formatModeRoundTripPreservesEveryTagId() throws IOException {
        NBTTagCompound root = populatedRoot();

        NBTTagCompound restored = roundTrip(root, true);

        assertEquals(codec.toJson(root, true).toString(), codec.toJson(restored, true).toString());
        assertEquals(1, NbtCompat.getTagId(restored, "b"));
        assertEquals(2, NbtCompat.getTagId(restored, "s"));
        assertEquals(3, NbtCompat.getTagId(restored, "i"));
        assertEquals(4, NbtCompat.getTagId(restored, "l"));
        assertEquals(5, NbtCompat.getTagId(restored, "f"));
        assertEquals(6, NbtCompat.getTagId(restored, "d"));
        assertEquals(8, NbtCompat.getTagId(restored, "str"));
        assertEquals(9, NbtCompat.getTagId(restored, "entries"));
        assertEquals(10, NbtCompat.getTagId(restored, "child"));
    }

    @Test
    void plainModeRoundTripIsStableFromTheSecondPassOnward() throws IOException {
        NBTTagCompound root = populatedRoot();

        NBTTagCompound first = roundTrip(root, false);
        NBTTagCompound second = roundTrip(first, false);

        // The first pass necessarily widens: without key suffixes byte/short/int all become long
        // and float becomes double, so only the second pass onwards is a fixed point.
        assertEquals(codec.toJson(first, false).toString(), codec.toJson(second, false).toString());
        assertEquals(4, NbtCompat.getTagId(first, "b"));
        assertEquals(4, NbtCompat.getTagId(first, "i"));
        assertEquals(6, NbtCompat.getTagId(first, "f"));
        assertEquals(6, NbtCompat.getTagId(first, "d"));
    }

    @Test
    void nestedListsAndCompoundsRoundTripInFormatMode() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList outer = new NBTTagList("");
        NBTTagList inner = new NBTTagList("");
        inner.appendTag(new NBTTagString("", "deep"));
        NBTTagCompound holder = new NBTTagCompound();
        holder.setTag("inner", inner);
        outer.appendTag(holder);
        root.setTag("outer", outer);

        assertEquals("{\"outer:9\":{\"0:10\":{\"inner:9\":{\"0:8\":\"deep\"}}}}",
            codec.toJson(root, true).toString());
        assertEquals(codec.toJson(root, true).toString(),
            codec.toJson(roundTrip(root, true), true).toString());
    }

    // --------------------------------------------------------------------- numeric fidelity

    @Test
    void floatsSerializeThroughTheirOwnTypeNotThroughDouble() {
        NBTTagCompound root = new NBTTagCompound();
        root.setFloat("f", 0.1f);
        root.setDouble("d", 0.1d);

        // Widening 0.1f to double would emit 0.10000000149011612.
        assertEquals("{\"d:6\":0.1,\"f:5\":0.1}", codec.toJson(root, true).toString());
    }

    @Test
    void largeLongsKeepEveryBitInsteadOfPassingThroughDouble() throws IOException {
        long beyondDoublePrecision = 9007199254740993L;
        NBTTagCompound root = new NBTTagCompound();
        root.setLong("l", beyondDoublePrecision);

        assertEquals("{\"l:4\":9007199254740993}", codec.toJson(root, true).toString());
        assertEquals(beyondDoublePrecision, roundTrip(root, true).getLong("l"));
    }

    @Test
    void inMemoryNumberNarrowsTowardZeroWhileNbtAccessorsFloor() {
        // A Number that is already a Double narrows with Java semantics: -3.7 truncates to -3.
        NBTBase truncated = codec.toNbt(
            new com.google.gson.JsonPrimitive(Double.valueOf(-3.7d)), (byte) 3, true);
        assertEquals(3, truncated.getId());
        assertEquals(-3, ((NBTTagInt) truncated).data);

        // Reading an existing floating tag as an int floors instead: -3.7 becomes -4. The two rules
        // are deliberately different; see handoff.md 5.3.
        assertEquals(-4, com.github.postyizhan.betterquesting.api.util.NbtNumbers
            .readAsInt(new NBTTagDouble("", -3.7d)));
        assertEquals(-4, com.github.postyizhan.betterquesting.api.util.NbtNumbers
            .readAsInt(new NBTTagFloat("", -3.7f)));
    }

    @Test
    void parsedFractionalLiteralUnderAnIntegralKeyIsLostNotTruncated() throws IOException {
        List<String> warnings = new ArrayList<>();

        NBTTagCompound restored = new NbtJsonCodec(warnings::add)
            .toNbt(JsonDocuments.parseObject("{\"n:3\":-3.7,\"m:4\":2.9}"), true);

        // Gson 2.2.2 backs a parsed number with LazilyParsedNumber, whose intValue/longValue fall
        // back to BigInteger and therefore throw on a fractional literal. Upstream's
        // instanceNumber has the same failure and degrades to an empty string tag.
        assertEquals(8, NbtCompat.getTagId(restored, "n"));
        assertEquals("", restored.getString("n"));
        assertEquals(8, NbtCompat.getTagId(restored, "m"));
        // Two reports per failed member: the conversion error, then the fall-through "unknown
        // representation" notice. Upstream logs the same pair (NBTConverter.java:396-402).
        assertEquals(4, warnings.size());
    }

    @Test
    void declaredTagIdWinsOverTheLiteralShape() throws IOException {
        JsonObject document = JsonDocuments.parseObject(
            "{\"asByte:1\":300,\"asShort:2\":70000,\"asFloat:5\":1.5,\"asLong:4\":2}");

        NBTTagCompound restored = codec.toNbt(document, true);

        assertEquals(1, NbtCompat.getTagId(restored, "asByte"));
        assertEquals((byte) 44, restored.getByte("asByte"));
        assertEquals(2, NbtCompat.getTagId(restored, "asShort"));
        assertEquals((short) 4464, restored.getShort("asShort"));
        assertEquals(5, NbtCompat.getTagId(restored, "asFloat"));
        assertEquals(1.5f, restored.getFloat("asFloat"));
        assertEquals(4, NbtCompat.getTagId(restored, "asLong"));
        assertEquals(2L, restored.getLong("asLong"));
    }

    @Test
    void undeclaredNumbersFallBackToLongOrDoubleByTextualForm() throws IOException {
        NBTTagCompound restored = codec.toNbt(
            JsonDocuments.parseObject("{\"whole\":7,\"fraction\":7.0,\"exponentNoDot\":1E3}"), false);

        assertEquals(4, NbtCompat.getTagId(restored, "whole"));
        assertEquals(7L, restored.getLong("whole"));
        assertEquals(6, NbtCompat.getTagId(restored, "fraction"));
        assertEquals(7.0d, restored.getDouble("fraction"));
        // fallbackTagId inspects only the literal text (NBTConverter.java:461-468), so exponent
        // notation without a '.' picks long, and Long.parseLong("1E3") then fails: the value is
        // lost rather than rounded. Reproduced from upstream, not introduced here.
        assertEquals(8, NbtCompat.getTagId(restored, "exponentNoDot"));
        assertEquals("", restored.getString("exponentNoDot"));
    }

    // ------------------------------------------------------------------------- empty and null

    @Test
    void nullSourcesAreToleratedInBothDirections() {
        assertEquals("{}", codec.toJson((NBTTagCompound) null, new JsonObject(), true).toString());
        assertEquals("{}", codec.toJson((NBTBase) null, true).toString());
        assertTrue(codec.toNbt(null, new NBTTagCompound(), true).hasNoTags());
    }

    @Test
    void emptyCompoundAndEmptyListAreDistinguishableOnlyInFormatMode() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("emptyList", new NBTTagList(""));
        root.setTag("emptyCompound", new NBTTagCompound());

        assertEquals("{\"emptyCompound:10\":{},\"emptyList:9\":{}}", codec.toJson(root, true).toString());
        assertEquals("{\"emptyCompound\":{},\"emptyList\":[]}", codec.toJson(root, false).toString());

        NBTTagCompound formatted = roundTrip(root, true);
        assertEquals(9, NbtCompat.getTagId(formatted, "emptyList"));
        assertEquals(10, NbtCompat.getTagId(formatted, "emptyCompound"));

        NBTTagCompound plain = roundTrip(root, false);
        assertEquals(9, NbtCompat.getTagId(plain, "emptyList"));
        assertEquals(10, NbtCompat.getTagId(plain, "emptyCompound"));
    }

    @Test
    void jsonNullDegradesToAnEmptyStringTag() throws IOException {
        List<String> warnings = new ArrayList<>();
        NBTTagCompound restored = new NbtJsonCodec(warnings::add)
            .toNbt(JsonDocuments.parseObject("{\"missing:10\":null}"), true);

        assertEquals(8, NbtCompat.getTagId(restored, "missing"));
        assertEquals("", restored.getString("missing"));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void unrecognizedTagIdDegradesToAnEmptyStringTagAndReports() throws IOException {
        List<String> warnings = new ArrayList<>();

        NBTTagCompound restored = new NbtJsonCodec(warnings::add)
            .toNbt(JsonDocuments.parseObject("{\"weird:99\":1}"), true);

        assertEquals(8, NbtCompat.getTagId(restored, "weird"));
        assertEquals("", restored.getString("weird"));
        assertEquals(List.of("Unknown NBT representation for 1 (ID: 99)"), warnings);
    }

    @Test
    void unrecognizedTagTypeSerializesAsAnEmptyObject() {
        assertEquals("{}", codec.toJson(new NBTTagEnd(), true).toString());

        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagEnd());
        root.setTag("entries", list);
        assertEquals("{\"entries:9\":{\"0:0\":{}}}", codec.toJson(root, true).toString());
    }

    // ------------------------------------------------------------------ key suffix parsing

    @Test
    void keysContainingColonsSplitOnTheLastOne() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("betterquesting:name", "x");

        assertEquals("{\"betterquesting:name:8\":\"x\"}", codec.toJson(root, true).toString());
        assertEquals("x", roundTrip(root, true).getString("betterquesting:name"));
    }

    @Test
    void unparsableSuffixKeepsTheWholeKeyAndInfersTheType() throws IOException {
        NBTTagCompound restored = codec.toNbt(
            JsonDocuments.parseObject("{\"betterquesting:name\":\"x\"}"), true);

        assertEquals("x", restored.getString("betterquesting:name"));
        assertEquals(8, NbtCompat.getTagId(restored, "betterquesting:name"));
    }

    @Test
    void suffixConflictWithAnExistingRawKeyIsSkippedNotOverwritten() throws IOException {
        List<String> warnings = new ArrayList<>();
        NBTTagCompound target = new NBTTagCompound();
        target.setString("a:b", "original");

        new NbtJsonCodec(warnings::add)
            .toNbt(JsonDocuments.parseObject("{\"a:b\":\"replacement\"}"), target, true);

        assertEquals("original", target.getString("a:b"));
        assertEquals(List.of("JSON/NBT formatting conflict on key 'a:b'. Skipping..."), warnings);
    }

    @Test
    void legacyBooleanUnderAByteTagBecomesZeroOrOne() throws IOException {
        NBTTagCompound restored = codec.toNbt(
            JsonDocuments.parseObject("{\"on:1\":true,\"off:1\":false,\"bare\":true}"), true);

        assertEquals(1, NbtCompat.getTagId(restored, "on"));
        assertEquals((byte) 1, restored.getByte("on"));
        assertEquals((byte) 0, restored.getByte("off"));
        assertEquals(1, NbtCompat.getTagId(restored, "bare"));
        assertEquals((byte) 1, restored.getByte("bare"));
    }

    @Test
    void listElementIdsAreReadFromTheIndexSuffix() throws IOException {
        NBTTagCompound restored = codec.toNbt(
            JsonDocuments.parseObject("{\"entries:9\":{\"0:5\":1.5,\"1:2\":3}}"), true);

        NBTTagList entries = restored.getTagList("entries");
        assertEquals(2, entries.tagCount());
        assertEquals(5, entries.tagAt(0).getId());
        assertEquals(2, entries.tagAt(1).getId());
    }

    @Test
    void listElementWithAnUnparsableSuffixFallsBackToInference() throws IOException {
        NBTTagCompound restored = codec.toNbt(
            JsonDocuments.parseObject("{\"entries:9\":{\"zero\":1.5}}"), true);

        NBTTagList entries = restored.getTagList("entries");
        assertEquals(1, entries.tagCount());
        assertEquals(6, entries.tagAt(0).getId());
    }

    // ------------------------------------------------------------------------- parse rejection

    @Test
    void emptyAndNonObjectDocumentsAreRejectedRatherThanTreatedAsEmpty() {
        assertThrows(MalformedJsonDocumentException.class, () -> JsonDocuments.parseObject(""));
        assertThrows(MalformedJsonDocumentException.class, () -> JsonDocuments.parseObject("[1,2]"));
        assertThrows(MalformedJsonDocumentException.class, () -> JsonDocuments.parseObject("{\"a\":"));
        assertThrows(MalformedJsonDocumentException.class, () -> JsonDocuments.parseObject("null"));
    }

    // -------------------------------------------------------------------------------- helpers

    private static NBTTagCompound populatedRoot() {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("b", new NBTTagByte("", (byte) 1));
        root.setTag("s", new NBTTagShort("", (short) 2));
        root.setTag("i", new NBTTagInt("", 3));
        root.setTag("l", new NBTTagLong("", 4L));
        root.setTag("f", new NBTTagFloat("", 5.5f));
        root.setTag("d", new NBTTagDouble("", -6.5d));
        root.setTag("str", new NBTTagString("", "value"));
        root.setTag("bytes", new NBTTagByteArray("", new byte[] {1, -2}));
        root.setTag("ints", new NBTTagIntArray("", new int[] {3, -4}));
        NBTTagList entries = new NBTTagList("");
        NBTTagCompound element = new NBTTagCompound();
        element.setInteger("n", 9);
        entries.appendTag(element);
        root.setTag("entries", entries);
        NBTTagCompound child = new NBTTagCompound();
        child.setString("nested", "deep");
        root.setTag("child", child);
        return root;
    }

    private NBTTagCompound roundTrip(NBTTagCompound source, boolean format) throws IOException {
        return codec.toNbt(JsonDocuments.parseObject(compact(source, format)), format);
    }

    private String compact(NBTTagCompound source, boolean format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonWriter json = new JsonWriter(new java.io.OutputStreamWriter(bytes, StandardCharsets.UTF_8));
        codec.write(source, json, format);
        json.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void listIndicesPastNineKeepNaturalOrderNotLexicographicOrder() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList("");
        for (int i = 0; i < 12; i++) {
            list.appendTag(new NBTTagLong("", i));
        }
        root.setTag("entries", list);

        String encoded = codec.toJson(root, true).toString();
        // Upstream keys list entries by loop counter (NBTConverter.java:220-225) and Gson keeps
        // insertion order, so "10:4" follows "9:4" rather than sorting between "1:4" and "2:4".
        assertTrue(encoded.contains("\"9:4\":9,\"10:4\":10,\"11:4\":11}"), encoded);

        NBTTagCompound restored = codec.toNbt(codec.toJson(root, true), true);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L),
            longsOf((NBTTagList) restored.getTag("entries")));
    }

    @Test
    void lexicographicallyReorderedListKeysSilentlyPermuteElements() throws IOException {
        // Characterization of an inherited upstream defect, NOT endorsed behaviour: the reader
        // (NbtJsonCodec.java:352-365, upstream NBTConverter.java:377-389) parses only the element-id
        // suffix and appends in entry order, discarding the index. Any external tool that sorts the
        // object's keys therefore permutes the list with no error surfaced. Honouring the index
        // instead would diverge from upstream on files upstream itself accepts, so the format stays
        // as-is and the hazard is pinned here.
        JsonObject sortedByText = JsonDocuments.parseObject(
            "{\"entries:9\":{\"0:4\":0,\"1:4\":1,\"10:4\":10,\"11:4\":11,\"2:4\":2,\"3:4\":3,"
                + "\"4:4\":4,\"5:4\":5,\"6:4\":6,\"7:4\":7,\"8:4\":8,\"9:4\":9}}");

        NBTTagCompound restored = codec.toNbt(sortedByText, true);

        assertEquals(List.of(0L, 1L, 10L, 11L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
            longsOf((NBTTagList) restored.getTag("entries")));
    }

    private String indented(NBTTagCompound source, boolean format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonWriter json = JsonDocuments.writer(bytes);
        codec.write(source, json, format);
        json.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static List<Long> longsOf(NBTTagList list) {
        List<Long> values = new ArrayList<>();
        for (NBTBase element : NbtCompat.elements(list)) {
            values.add(((NBTTagLong) element).data);
        }
        return values;
    }
}
