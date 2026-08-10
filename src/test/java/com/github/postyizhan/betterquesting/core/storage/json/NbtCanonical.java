package com.github.postyizhan.betterquesting.core.storage.json;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.google.gson.JsonElement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

/**
 * Structure-only renderers used to diff {@link NbtJsonCodec} against
 * {@link UpstreamNbtConverterOracle} without depending on member order or on
 * {@code JsonPrimitive.equals}'s lossy numeric comparison.
 *
 * <p>Extracted from {@code NbtJsonCodecOracleDiffTest} so the golden-fixture suite and the inline
 * differential suite share one definition of "equal" rather than maintaining two. The two
 * behaviours are the reason a naive comparison is wrong here:
 * <ul>
 *   <li>Upstream's real save path iterates {@code tagMap.keySet()} in {@code HashMap} order
 *       (NBTConverter.java:185) while the port sorts, so a compound has no byte-level upstream
 *       baseline; only its parsed structure can be compared. See handoff.md 4.2d.</li>
 *   <li>{@code JsonPrimitive.equals} compares numbers through {@code doubleValue()}, so a float
 *       tag's literal re-parsed as a double differs from the widened float. Primitives are rendered
 *       with {@code toString()}, which for a parsed number returns the original literal.</li>
 * </ul>
 *
 * <p>Not a pure-JVM helper: it touches {@code net.minecraft.NBT*}, so any test using it needs the
 * Gradle Minecraft classpath.
 */
final class NbtCanonical {
    private NbtCanonical() {
    }

    /**
     * Renders JSON with object members sorted by key and every primitive kept as its literal text,
     * so two documents that differ only in member order compare equal and nothing else does.
     */
    static String json(JsonElement element) {
        if (element.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                sorted.put(member.getKey(), member.getValue());
            }
            StringBuilder rendered = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonElement> member : sorted.entrySet()) {
                if (!first) {
                    rendered.append(',');
                }
                first = false;
                rendered.append('[').append(member.getKey()).append("]=").append(json(member.getValue()));
            }
            return rendered.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder rendered = new StringBuilder("[");
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) {
                    rendered.append(',');
                }
                first = false;
                rendered.append(json(child));
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
     * it. Tag names are excluded: {@code setTag} derives a child's name from its key (verified against
     * MITE's bytecode), and list element names are never encoded in JSON at all.
     */
    static String nbt(NBTBase tag) {
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
                return nbtList((NBTTagList) tag);
            case 10:
                return nbtCompound((NBTTagCompound) tag);
            case 11:
                return "ints" + Arrays.toString(((NBTTagIntArray) tag).intArray);
            default:
                return "unknown(" + tag.getId() + ")";
        }
    }

    private static String nbtList(NBTTagList list) {
        StringBuilder rendered = new StringBuilder("list[");
        List<NBTBase> elements = NbtCompat.elements(list);
        for (int index = 0; index < elements.size(); index++) {
            if (index > 0) {
                rendered.append(',');
            }
            rendered.append(index).append('=').append(nbt(elements.get(index)));
        }
        return rendered.append(']').toString();
    }

    private static String nbtCompound(NBTTagCompound compound) {
        StringBuilder rendered = new StringBuilder("compound{");
        boolean first = true;
        for (String key : NbtCompat.sortedKeys(compound)) {
            if (!first) {
                rendered.append(',');
            }
            first = false;
            rendered.append('[').append(key).append("]=").append(nbt(compound.getTag(key)));
        }
        return rendered.append('}').toString();
    }
}
