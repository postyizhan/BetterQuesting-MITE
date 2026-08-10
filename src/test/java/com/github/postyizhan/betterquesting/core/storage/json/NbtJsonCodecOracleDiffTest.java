package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;
import net.minecraft.NBTTagByteArray;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagDouble;
import net.minecraft.NBTTagFloat;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagIntArray;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagLong;
import net.minecraft.NBTTagShort;
import net.minecraft.NBTTagString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential test: {@link NbtJsonCodec} against {@link UpstreamNbtConverterOracle}, the upstream
 * converter transplanted into the test source set.
 *
 * <p>{@link NbtJsonCodecTest} asserts the codec against textual forms written by hand from a reading
 * of upstream. This file removes the reading: the same NBT goes into both implementations and the
 * output is diffed, then the same JSON goes into both and the restored NBT is diffed.
 *
 * <p>What each comparison proves, and why the comparison differs by path:
 * <ul>
 *   <li><b>{@code JsonObject} path, both dialects: byte-for-byte.</b> Upstream sorts keys through a
 *       {@code TreeSet} (NBTConverter.java:267) and so does the codec, so the serialized text must
 *       match exactly.</li>
 *   <li><b>Streaming path, {@code format=true}: order-insensitive canonical text</b>
 *       ({@link #canonicalJson}), which keeps every primitive's literal text and only forgives
 *       member order. Upstream iterates
 *       {@code tagMap.keySet()} unsorted (NBTConverter.java:185) while the codec sorts, a registered
 *       deviation. Tree equality is the right relation because the reader keys off
 *       {@code entrySet()} (NBTConverter.java:289) and is order-independent for compounds. Only
 *       {@code format=true} is diffed on this path: upstream's {@code format=false} list branch is
 *       unclosed (NBTConverter.java:166-171) and cannot produce a document. {@code format=true} is
 *       also the only mode real saves use (SaveLoadHandler.java:314/341/349/357/369).</li>
 *   <li><b>{@code format=false} streaming: not diffable.</b> Pinned instead by
 *       {@link #oracleStreamingPlainListCannotBeWrittenWhileTheCodecClosesIt}.</li>
 *   <li><b>Read direction: canonical NBT dump equality.</b> {@link #canon} renders ids, structure
 *       and values, so a type or precision difference fails the assertion.</li>
 * </ul>
 *
 * <p>Not a pure JVM test: the domain layer exposes {@code net.minecraft.NBT*}, so the Gradle
 * Minecraft classpath is required.
 */
class NbtJsonCodecOracleDiffTest {
    private final NbtJsonCodec codec = new NbtJsonCodec();

    // ------------------------------------------------------------- write direction: NBT to JSON

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#nbtCases")
    void elementPathIsByteIdenticalToTheOracleInBothDialects(String name, Supplier<NBTTagCompound> source) {
        assertEquals(UpstreamNbtConverterOracle.NBTtoJSON_Compound(source.get(), new JsonObject(), true).toString(),
            codec.toJson(source.get(), true).toString(),
            name + " diverged in format=true");
        assertEquals(UpstreamNbtConverterOracle.NBTtoJSON_Compound(source.get(), new JsonObject(), false).toString(),
            codec.toJson(source.get(), false).toString(),
            name + " diverged in format=false");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#nbtCases")
    void streamingPathMatchesTheOracleTreeAndItsOwnElementPathBytes(String name, Supplier<NBTTagCompound> source)
        throws IOException {
        String oracle = oracleStream(source.get(), true);
        String ours = codecStream(source.get(), true);

        assertEquals(canonicalJson(parse(oracle)), canonicalJson(parse(ours)),
            name + " streaming documents diverged");
        // Key order is the registered deviation and the only one: once sorted, the bytes must match
        // the codec's own element path, which is byte-identical to the oracle above.
        assertEquals(codec.toJson(source.get(), true).toString(), ours, name + " streaming bytes drifted");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#nbtCases")
    void theOraclesOwnTwoWritePathsAgreeAsTrees(String name, Supplier<NBTTagCompound> source) throws IOException {
        // Guards the oracle itself: upstream's streaming and JsonObject paths differ only in key
        // order (NBTConverter.java:185 unsorted vs :267 sorted). If they disagreed on anything else
        // the transplant would be wrong and every diff above would be measuring the wrong thing.
        assertEquals(
            canonicalJson(UpstreamNbtConverterOracle.NBTtoJSON_Compound(source.get(), new JsonObject(), true)),
            canonicalJson(parse(oracleStream(source.get(), true))), name + " oracle paths diverged");
    }

    // ------------------------------------------------------------- read direction: JSON to NBT

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#formatJsonCases")
    void formatModeReadMatchesTheOracle(String name, String document) throws IOException {
        JsonObject parsed = parse(document);

        assertEquals(canon(UpstreamNbtConverterOracle.JSONtoNBT_Object(parsed, new NBTTagCompound(), true)),
            canon(codec.toNbt(parsed, new NBTTagCompound(), true)), name + " diverged");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#plainJsonCases")
    void plainModeReadMatchesTheOracle(String name, String document) throws IOException {
        JsonObject parsed = parse(document);

        assertEquals(canon(UpstreamNbtConverterOracle.JSONtoNBT_Object(parsed, new NBTTagCompound(), false)),
            canon(codec.toNbt(parsed, new NBTTagCompound(), false)), name + " diverged");
    }

    // ------------------------------------------------------ round trips and cross-compatibility

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#nbtCases")
    void formatModeRoundTripMatchesTheOracle(String name, Supplier<NBTTagCompound> source) {
        assertEquals(canon(oracleRoundTrip(source.get(), true)), canon(codecRoundTrip(source.get(), true)),
            name + " round trip diverged");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#nbtCases")
    void plainModeRoundTripMatchesTheOracle(String name, Supplier<NBTTagCompound> source) {
        assertEquals(canon(oracleRoundTrip(source.get(), false)), canon(codecRoundTrip(source.get(), false)),
            name + " round trip diverged");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.postyizhan.betterquesting.core.storage.json.NbtDiffFixtures#nbtCases")
    void eachSideCanReadTheOthersDocument(String name, Supplier<NBTTagCompound> source) {
        // The property that matters for save compatibility: a file this port writes must load in
        // upstream to the same tags, and a file upstream wrote must load here to the same tags.
        JsonObject ours = codec.toJson(source.get(), true);
        JsonObject theirs = UpstreamNbtConverterOracle.NBTtoJSON_Compound(source.get(), new JsonObject(), true);

        assertEquals(canon(UpstreamNbtConverterOracle.JSONtoNBT_Object(ours, new NBTTagCompound(), true)),
            canon(codec.toNbt(theirs, new NBTTagCompound(), true)), name + " cross read diverged");
    }

    @Test
    void suffixConflictWithAPrePopulatedRawKeyBehavesLikeTheOracle() throws IOException {
        // NBTConverter.java:305-312: when the suffix will not parse, the raw key is skipped only if
        // the target already holds it, otherwise it is written with id 0. Reaching the skip requires
        // a pre-populated target, which no fixture can express.
        JsonObject document = parse("{\"a:b\":\"replacement\",\"c:d\":\"fresh\"}");

        assertEquals(canon(UpstreamNbtConverterOracle.JSONtoNBT_Object(document, prePopulated(), true)),
            canon(codec.toNbt(document, prePopulated(), true)));
        // Pin the observable outcome too, so a shared regression in both cannot pass silently.
        NBTTagCompound ours = codec.toNbt(document, prePopulated(), true);
        assertEquals("original", ours.getString("a:b"));
        assertEquals("fresh", ours.getString("c:d"));
    }

    // ------------------------------------------------------------------ registered deviations

    @Test
    void oracleStreamingPlainListCannotBeWrittenWhileTheCodecClosesIt() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagInt("", 1));
        root.setTag("entries", list);
        root.setInteger("after", 2);

        // NBTConverter.java:167-171 calls beginArray() and returns without endArray(), leaving the
        // writer inside the array. JsonWriter then rejects the next name and the closing brace.
        assertThrows(IllegalStateException.class, () -> oracleStream(root, false));
        assertEquals("{\"after\":2,\"entries\":[1]}", codecStream(root, false));
        // The element path is unaffected upstream, so it stays byte-comparable and is diffed above.
        assertEquals("{\"after\":2,\"entries\":[1]}",
            UpstreamNbtConverterOracle.NBTtoJSON_Compound(root, new JsonObject(), false).toString());
    }

    @Test
    void nullStringPayloadThrowsInTheOracleAndDegradesHere() throws IOException {
        // MITE's one-argument NBTTagString leaves data null, a state 1.7.10 could not reach, so
        // upstream has no defined behaviour here. Gson 2.2.2 rejects a null JsonPrimitive value.
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("s", new NBTTagString("s"));

        assertThrows(RuntimeException.class,
            () -> UpstreamNbtConverterOracle.NBTtoJSON_Compound(root, new JsonObject(), true));
        assertEquals("{\"s:8\":\"\"}", codec.toJson(root, true).toString());
    }

    @Test
    void nullArrayPayloadThrowsInTheOracleAndDegradesHere() {
        NBTTagCompound bytes = new NBTTagCompound();
        bytes.setTag("b", new NBTTagByteArray("b"));
        NBTTagCompound ints = new NBTTagCompound();
        ints.setTag("i", new NBTTagIntArray("i"));

        assertThrows(NullPointerException.class,
            () -> UpstreamNbtConverterOracle.NBTtoJSON_Compound(bytes, new JsonObject(), true));
        assertThrows(NullPointerException.class,
            () -> UpstreamNbtConverterOracle.NBTtoJSON_Compound(ints, new JsonObject(), true));
        assertEquals("{\"b:7\":[]}", codec.toJson(bytes, true).toString());
        assertEquals("{\"i:11\":[]}", codec.toJson(ints, true).toString());
    }

    @Test
    void nonFiniteDoublesSplitTheTwoWritePathsIdenticallyOnBothSides() {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("nan", new NBTTagDouble("", Double.NaN));
        root.setTag("inf", new NBTTagFloat("", Float.POSITIVE_INFINITY));

        // JsonElement.toString() writes leniently, so the element path emits bare NaN/Infinity, which
        // is not JSON and will not parse back. Reproduced, not corrected: correcting it would change
        // bytes upstream still writes.
        assertEquals("{\"inf:5\":Infinity,\"nan:6\":NaN}",
            UpstreamNbtConverterOracle.NBTtoJSON_Compound(root, new JsonObject(), true).toString());
        assertEquals("{\"inf:5\":Infinity,\"nan:6\":NaN}", codec.toJson(root, true).toString());
        // The streaming path is strict on both sides and refuses the value outright.
        assertThrows(IllegalArgumentException.class,
            () -> UpstreamNbtConverterOracle.NBTtoJSON_Compound(root, jsonWriter(new ByteArrayOutputStream()), true));
        assertThrows(IllegalArgumentException.class,
            () -> codec.write(root, jsonWriter(new ByteArrayOutputStream()), true));
    }

    @Test
    void theOracleIsWiredToRealNbtInternals() {
        // If the reflective hooks silently failed, every list would read as empty and the diffs would
        // pass while proving nothing. This is the canary for that.
        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagInt("", 41));
        list.appendTag(new NBTTagInt("", 42));

        assertEquals(2, UpstreamNbtConverterOracle.getTagList(list).size());
        assertEquals(42, ((NBTTagInt) UpstreamNbtConverterOracle.getTagList(list).get(1)).data);
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("k", 1);
        assertNotEquals("{}", UpstreamNbtConverterOracle.NBTtoJSON_Compound(root, new JsonObject(), true).toString());
    }

    // -------------------------------------------------------------------------------- helpers

    private NBTTagCompound oracleRoundTrip(NBTTagCompound source, boolean format) {
        return UpstreamNbtConverterOracle.JSONtoNBT_Object(
            UpstreamNbtConverterOracle.NBTtoJSON_Compound(source, new JsonObject(), format),
            new NBTTagCompound(), format);
    }

    private NBTTagCompound codecRoundTrip(NBTTagCompound source, boolean format) {
        return codec.toNbt(codec.toJson(source, format), new NBTTagCompound(), format);
    }

    private static NBTTagCompound prePopulated() {
        NBTTagCompound target = new NBTTagCompound();
        target.setString("a:b", "original");
        return target;
    }

    private static String oracleStream(NBTTagCompound source, boolean format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonWriter json = jsonWriter(bytes);
        UpstreamNbtConverterOracle.NBTtoJSON_Compound(source, json, format);
        json.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String codecStream(NBTTagCompound source, boolean format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonWriter json = jsonWriter(bytes);
        codec.write(source, json, format);
        json.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Compact writer, so streaming output is directly comparable to {@code JsonElement.toString()}. */
    private static JsonWriter jsonWriter(ByteArrayOutputStream bytes) {
        return new JsonWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8));
    }

    private static JsonObject parse(String document) throws IOException {
        return JsonDocuments.parseObject(document);
    }

    /**
     * Renders JSON with object members sorted by key and every primitive kept as its literal text,
     * so two documents that differ only in member order compare equal and nothing else does.
     *
     * <p>Comparing parsed trees with {@code JsonObject.equals} would be wrong here:
     * {@code JsonPrimitive.equals} compares numbers through {@code doubleValue()}, and a float tag's
     * text re-parsed as a double is a different double than the widened float
     * ({@code 0.1} parses to {@code 0.1} but {@code (double) 0.1f} is {@code 0.10000000149011612}).
     * That would report a divergence where both sides in fact wrote identical bytes. Primitives are
     * therefore rendered with {@code toString()}, which for a parsed number returns the original
     * literal.
     */
    private static String canonicalJson(com.google.gson.JsonElement element) {
        if (element.isJsonObject()) {
            java.util.TreeMap<String, com.google.gson.JsonElement> sorted = new java.util.TreeMap<>();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> member
                : element.getAsJsonObject().entrySet()) {
                sorted.put(member.getKey(), member.getValue());
            }
            StringBuilder rendered = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> member : sorted.entrySet()) {
                if (!first) {
                    rendered.append(',');
                }
                first = false;
                rendered.append('[').append(member.getKey()).append("]=").append(canonicalJson(member.getValue()));
            }
            return rendered.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder rendered = new StringBuilder("[");
            boolean first = true;
            for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
                if (!first) {
                    rendered.append(',');
                }
                first = false;
                rendered.append(canonicalJson(child));
            }
            return rendered.append(']').toString();
        }
        return element.toString();
    }

    /**
     * Renders NBT as text that exposes every property the JSON contract carries: tag ids, structure,
     * element order inside lists, and the exact value of each primitive.
     *
     * <p>Compound keys are sorted because a compound is a {@code HashMap} whose iteration order is
     * not part of the contract; list elements keep their order because that order <em>is</em> part of
     * it. Tag names are excluded: {@code setTag} derives a child's name from its key
     * (verified against MITE's bytecode), and list element names are never encoded in JSON at all, so
     * a name difference cannot reach a file.
     */
    private static String canon(NBTBase tag) {
        if (tag == null) {
            return "null";
        }
        switch (tag.getId()) {
            case 0:
                return "end";
            case 1:
                return "byte(" + ((NBTTagByte) tag).data + ")";
            case 2:
                return "short(" + ((NBTTagShort) tag).data + ")";
            case 3:
                return "int(" + ((NBTTagInt) tag).data + ")";
            case 4:
                return "long(" + ((NBTTagLong) tag).data + ")";
            case 5:
                // Float.toString keeps -0.0 distinct from 0.0 and never widens through double.
                return "float(" + Float.toString(((NBTTagFloat) tag).data) + ")";
            case 6:
                return "double(" + Double.toString(((NBTTagDouble) tag).data) + ")";
            case 7:
                return "bytes" + Arrays.toString(((NBTTagByteArray) tag).byteArray);
            case 8:
                return "string(" + ((NBTTagString) tag).data + ")";
            case 9:
                return canonList((NBTTagList) tag);
            case 10:
                return canonCompound((NBTTagCompound) tag);
            case 11:
                return "ints" + Arrays.toString(((NBTTagIntArray) tag).intArray);
            default:
                return "unknown(" + tag.getId() + ")";
        }
    }

    private static String canonList(NBTTagList list) {
        StringBuilder rendered = new StringBuilder("list[");
        List<NBTBase> elements = NbtCompat.elements(list);
        for (int index = 0; index < elements.size(); index++) {
            if (index > 0) {
                rendered.append(',');
            }
            rendered.append(index).append('=').append(canon(elements.get(index)));
        }
        return rendered.append(']').toString();
    }

    private static String canonCompound(NBTTagCompound compound) {
        StringBuilder rendered = new StringBuilder("compound{");
        boolean first = true;
        for (String key : NbtCompat.sortedKeys(compound)) {
            if (!first) {
                rendered.append(',');
            }
            first = false;
            rendered.append('[').append(key).append("]=").append(canon(compound.getTag(key)));
        }
        return rendered.append('}').toString();
    }
}
