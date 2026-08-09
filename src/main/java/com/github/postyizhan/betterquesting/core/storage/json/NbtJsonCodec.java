package com.github.postyizhan.betterquesting.core.storage.json;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtNumbers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Bidirectional NBT/JSON codec reproducing upstream {@code betterquesting.api.utils.NBTConverter}.
 *
 * <p>The {@code format} flag selects the on-disk dialect and must match what produced the file:
 * <ul>
 *   <li>{@code format=true} — every compound key becomes {@code "<key>:<tagId>"}
 *       (NBTConverter.java:272) and every list becomes a {@code JsonObject} whose keys are
 *       {@code "<index>:<tagId>"} (NBTConverter.java:221-225). Types survive the round trip.</li>
 *   <li>{@code format=false} — bare compound keys (NBTConverter.java:274) and plain
 *       {@code JsonArray} lists (NBTConverter.java:230-238). Type information is lost and the
 *       reverse direction must guess through {@link #fallbackTagId}.</li>
 * </ul>
 *
 * <p>{@code NBTTagByteArray} and {@code NBTTagIntArray} are always plain {@code JsonArray}s in both
 * dialects (NBTConverter.java:240-255), so the array element type is recoverable only from the
 * enclosing {@code "<key>:7"} or {@code "<key>:11"} suffix. Under {@code format=false} an integer
 * array and a list of integers are therefore indistinguishable, and {@link #fallbackTagId} resolves
 * every array to a tag list.
 *
 * <p>Only Gson 2.2.2 API is used: {@code JsonObject.entrySet}, {@code JsonArray.add(JsonElement)},
 * {@code JsonArray.size/get/iterator}. See docs/platform-probes.md.
 *
 * <p>Instances are stateless apart from the diagnostics sink and safe to share; the NBT objects
 * passed in are not, so callers keep the server main thread constraint from
 * {@code IPropertyContainer}.
 */
public final class NbtJsonCodec {
    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;

    private final NbtJsonDiagnostics diagnostics;

    public NbtJsonCodec() {
        this(NbtJsonDiagnostics.IGNORE);
    }

    public NbtJsonCodec(NbtJsonDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    // ---------------------------------------------------------------- NBT to JSON

    /**
     * Adds every tag of {@code source} to {@code target} in ascending key order and returns
     * {@code target}. Mirrors NBTConverter.java:262-279, including its null tolerance: a null
     * {@code source} leaves {@code target} untouched.
     */
    public JsonObject toJson(NBTTagCompound source, JsonObject target, boolean format) {
        Objects.requireNonNull(target, "target");
        if (source == null) {
            return target;
        }

        for (String key : NbtCompat.sortedKeys(source)) {
            NBTBase tag = source.getTag(key);
            target.add(format ? key + ":" + tag.getId() : key, toJson(tag, format));
        }
        return target;
    }

    /** Convenience overload returning a fresh object. */
    public JsonObject toJson(NBTTagCompound source, boolean format) {
        return toJson(source, new JsonObject(), format);
    }

    /**
     * Converts one tag. Mirrors NBTConverter.java:202-259: a null or unrecognized tag becomes an
     * empty object rather than JSON null, so no caller can trip over a null element.
     */
    public JsonElement toJson(NBTBase tag, boolean format) {
        if (tag == null) {
            return new JsonObject();
        }

        byte id = tag.getId();
        if (id >= TAG_BYTE && id <= TAG_DOUBLE) {
            // Dispatch on the source tag id so a float never widens through double and gains
            // precision digits; see NbtNumbers and handoff.md 5.3.
            return new JsonPrimitive(NbtNumbers.readAsNumber(tag));
        }
        if (tag instanceof NBTTagString) {
            return new JsonPrimitive(stringData((NBTTagString) tag));
        }
        if (tag instanceof NBTTagCompound) {
            return toJson((NBTTagCompound) tag, new JsonObject(), format);
        }
        if (tag instanceof NBTTagList) {
            return toJson((NBTTagList) tag, format);
        }
        if (tag instanceof NBTTagByteArray) {
            JsonArray array = new JsonArray();
            for (byte value : byteArrayData((NBTTagByteArray) tag)) {
                array.add(new JsonPrimitive(Byte.valueOf(value)));
            }
            return array;
        }
        if (tag instanceof NBTTagIntArray) {
            JsonArray array = new JsonArray();
            for (int value : intArrayData((NBTTagIntArray) tag)) {
                array.add(new JsonPrimitive(Integer.valueOf(value)));
            }
            return array;
        }
        return new JsonObject();
    }

    private JsonElement toJson(NBTTagList source, boolean format) {
        List<NBTBase> elements = NbtCompat.elements(source);
        if (!format) {
            JsonArray array = new JsonArray();
            for (NBTBase element : elements) {
                array.add(toJson(element, false));
            }
            return array;
        }

        JsonObject indexed = new JsonObject();
        for (int index = 0; index < elements.size(); index++) {
            NBTBase element = elements.get(index);
            indexed.add(index + ":" + element.getId(), toJson(element, true));
        }
        return indexed;
    }

    // ------------------------------------------------------- NBT to JSON (streaming)

    /**
     * Streams {@code source} to {@code out}, producing byte-for-byte the same document as
     * {@link #toJson(NBTTagCompound, JsonObject, boolean)} followed by serialization. Mirrors
     * NBTConverter.java:182-197 with two deliberate corrections noted on this class's write path:
     * keys are sorted, and list arrays are closed.
     */
    public void write(NBTTagCompound source, JsonWriter out, boolean format) throws IOException {
        Objects.requireNonNull(out, "out");
        out.beginObject();
        if (source != null) {
            for (String key : NbtCompat.sortedKeys(source)) {
                NBTBase tag = source.getTag(key);
                out.name(format ? key + ":" + tag.getId() : key);
                write(tag, out, format);
            }
        }
        out.endObject();
    }

    private void write(NBTBase tag, JsonWriter out, boolean format) throws IOException {
        if (tag == null || tag.getId() == TAG_END) {
            out.beginObject().endObject();
            return;
        }

        byte id = tag.getId();
        if (id >= TAG_BYTE && id <= TAG_DOUBLE) {
            // value(Number) emits Number.toString(), which is exactly what the JsonObject path
            // produces for the same JsonPrimitive, so both paths stay byte-identical.
            out.value(NbtNumbers.readAsNumber(tag));
        } else if (tag instanceof NBTTagString) {
            out.value(stringData((NBTTagString) tag));
        } else if (tag instanceof NBTTagCompound) {
            write((NBTTagCompound) tag, out, format);
        } else if (tag instanceof NBTTagList) {
            writeList((NBTTagList) tag, out, format);
        } else if (tag instanceof NBTTagByteArray) {
            out.beginArray();
            for (byte value : byteArrayData((NBTTagByteArray) tag)) {
                out.value(Byte.valueOf(value));
            }
            out.endArray();
        } else if (tag instanceof NBTTagIntArray) {
            out.beginArray();
            for (int value : intArrayData((NBTTagIntArray) tag)) {
                out.value(Integer.valueOf(value));
            }
            out.endArray();
        } else {
            out.beginObject().endObject();
        }
    }

    private void writeList(NBTTagList source, JsonWriter out, boolean format) throws IOException {
        List<NBTBase> elements = NbtCompat.elements(source);
        if (format) {
            out.beginObject();
            for (int index = 0; index < elements.size(); index++) {
                NBTBase element = elements.get(index);
                out.name(index + ":" + element.getId());
                write(element, out, true);
            }
            out.endObject();
            return;
        }

        // Deliberate deviation: upstream NBTConverter.java:166-171 opens the array and never calls
        // endArray(), which desynchronizes the writer for every following tag. The defect is latent
        // upstream because SaveLoadHandler.java:314 is the only streaming caller and passes
        // format=true. Emitting the closing bracket is required for this dialect to be parseable.
        out.beginArray();
        for (NBTBase element : elements) {
            write(element, out, false);
        }
        out.endArray();
    }

    // ---------------------------------------------------------------- JSON to NBT

    /**
     * Adds every member of {@code source} to {@code target} and returns {@code target}. Mirrors
     * NBTConverter.java:284-319, including the {@code format=true} key-suffix parse: a suffix that
     * is not a valid {@code byte} leaves the whole key intact with id 0, and a collision with an
     * already-present raw key is skipped rather than overwritten.
     */
    public NBTTagCompound toNbt(JsonObject source, NBTTagCompound target, boolean format) {
        Objects.requireNonNull(target, "target");
        if (source == null) {
            return target;
        }

        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            if (!format) {
                target.setTag(key, toNbt(entry.getValue(), TAG_END, false));
                continue;
            }

            byte id = TAG_END;
            String keyToUse = key;
            int lastColon = key.lastIndexOf(':');
            if (lastColon != -1) {
                try {
                    id = Byte.parseByte(key.substring(lastColon + 1));
                    keyToUse = key.substring(0, lastColon);
                } catch (NumberFormatException malformedSuffix) {
                    // Upstream assigns the id before the key, so a failed parse keeps both the raw
                    // key and id 0 (NBTConverter.java:300-312).
                    id = TAG_END;
                    if (target.hasKey(key)) {
                        diagnostics.warn("JSON/NBT formatting conflict on key '" + key + "'. Skipping...");
                        continue;
                    }
                }
            }

            target.setTag(keyToUse, toNbt(entry.getValue(), id, true));
        }
        return target;
    }

    /** Convenience overload returning a fresh compound. */
    public NBTTagCompound toNbt(JsonObject source, boolean format) {
        return toNbt(source, new NBTTagCompound(), format);
    }

    /**
     * Converts one element under the declared tag id, falling back to {@link #fallbackTagId} when
     * {@code id} is non-positive. Mirrors NBTConverter.java:324-403; every failure degrades to an
     * empty string tag rather than propagating, so one damaged member cannot abort a whole database
     * load.
     */
    public NBTBase toNbt(JsonElement source, byte id, boolean format) {
        if (source == null) {
            return emptyString();
        }

        byte tagId = id <= TAG_END ? fallbackTagId(source) : id;
        try {
            if (tagId == TAG_BYTE && (id <= TAG_END || source.getAsJsonPrimitive().isBoolean())) {
                // BQ2 legacy files stored booleans where a byte tag is declared.
                return new NBTTagByte("", source.getAsBoolean() ? (byte) 1 : (byte) 0);
            }
            if (tagId >= TAG_BYTE && tagId <= TAG_DOUBLE) {
                return instanceNumber(source.getAsNumber(), tagId);
            }
            if (tagId == TAG_STRING) {
                return new NBTTagString("", source.getAsString());
            }
            if (tagId == TAG_COMPOUND) {
                return toNbt(source.getAsJsonObject(), new NBTTagCompound(), format);
            }
            if (tagId == TAG_BYTE_ARRAY) {
                JsonArray array = source.getAsJsonArray();
                byte[] values = new byte[array.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = array.get(index).getAsByte();
                }
                return new NBTTagByteArray("", values);
            }
            if (tagId == TAG_INT_ARRAY) {
                JsonArray array = source.getAsJsonArray();
                int[] values = new int[array.size()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = array.get(index).getAsInt();
                }
                return new NBTTagIntArray("", values);
            }
            if (tagId == TAG_LIST) {
                return toNbtList(source, format);
            }
        } catch (RuntimeException failure) {
            diagnostics.warn("An error occurred while parsing JsonElement to NBTBase ("
                + tagId + "): " + failure);
        }

        diagnostics.warn("Unknown NBT representation for " + source + " (ID: " + tagId + ")");
        return emptyString();
    }

    private NBTTagList toNbtList(JsonElement source, boolean format) {
        NBTTagList list = new NBTTagList("");
        if (source.isJsonArray()) {
            for (JsonElement element : source.getAsJsonArray()) {
                list.appendTag(toNbt(element, TAG_END, format));
            }
            return list;
        }
        if (source.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                byte elementId = TAG_END;
                int lastColon = key.lastIndexOf(':');
                if (lastColon != -1) {
                    try {
                        elementId = Byte.parseByte(key.substring(lastColon + 1));
                    } catch (NumberFormatException malformedSuffix) {
                        // Matches upstream NBTConverter.java:387-389: an unparsable index suffix
                        // falls back to inference instead of dropping the element.
                        elementId = TAG_END;
                    }
                }
                list.appendTag(toNbt(entry.getValue(), elementId, format));
            }
        }
        return list;
    }

    /**
     * Infers a tag id for an element whose type was not declared. Mirrors
     * NBTConverter.java:454-516.
     *
     * <p>Arrays always resolve to {@link #TAG_LIST}: upstream scans the members to detect a byte or
     * int array, but then overwrites the result with 9 unconditionally at NBTConverter.java:510, and
     * every throw inside that scan is caught by its own handler. The scan is therefore
     * unobservable and is not reproduced.
     */
    static byte fallbackTagId(JsonElement source) {
        if (source.isJsonPrimitive()) {
            JsonPrimitive primitive = source.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                // Widest container that can hold the literal, judged only by the textual form.
                return primitive.getAsString().contains(".") ? TAG_DOUBLE : TAG_LONG;
            }
            return primitive.isBoolean() ? TAG_BYTE : TAG_STRING;
        }
        if (source.isJsonArray()) {
            return TAG_LIST;
        }
        return TAG_COMPOUND;
    }

    /**
     * Narrows a JSON-sourced number into the requested tag. Mirrors NBTConverter.java:437-452,
     * which relies on {@link Number} conversion semantics (truncation toward zero) rather than the
     * NBT accessor semantics in {@code NbtNumbers}; the two are intentionally different because the
     * source here is a JSON literal, not an NBT tag.
     *
     * <p>Known fidelity limit, inherited rather than introduced: a number that came from
     * {@code JsonParser} is backed by Gson's {@code LazilyParsedNumber}, whose {@code intValue} and
     * {@code longValue} fall back to {@code BigInteger} and therefore <em>throw</em> on a
     * fractional or exponent literal instead of truncating. A hand-edited {@code "count:3": 1.5} or
     * {@code "count:4": 1E3} is consequently reported through {@link #diagnostics} and degrades to
     * an empty string tag, losing the value. Upstream fails the same way, so this is not corrected
     * here; correcting it would make files written by this port unreadable to upstream.
     */
    private static NBTBase instanceNumber(Number value, byte tagId) {
        switch (tagId) {
            case TAG_BYTE:
                return new NBTTagByte("", value.byteValue());
            case TAG_SHORT:
                return new NBTTagShort("", value.shortValue());
            case TAG_INT:
                return new NBTTagInt("", value.intValue());
            case TAG_LONG:
                return new NBTTagLong("", value.longValue());
            case TAG_FLOAT:
                return new NBTTagFloat("", value.floatValue());
            default:
                return new NBTTagDouble("", value.doubleValue());
        }
    }

    /**
     * Upstream's failure placeholder was {@code new NBTTagString()}, whose data defaulted to the
     * empty string in 1.7.10. MITE has no no-argument constructor and its one-argument form takes
     * the tag name while leaving {@code data} null, so the name and the value are supplied
     * explicitly here.
     */
    private static NBTTagString emptyString() {
        return new NBTTagString("", "");
    }

    /**
     * MITE's {@code NBTTagString(String name)} leaves {@code data} null, a state 1.7.10 could not
     * reach. Serializing null through {@code JsonPrimitive} would produce a document this codec
     * cannot read back, so a null degrades to the empty string.
     */
    private static String stringData(NBTTagString tag) {
        return tag.data == null ? "" : tag.data;
    }

    /** MITE's one-argument array constructors leave the payload null; see {@link #stringData}. */
    private static byte[] byteArrayData(NBTTagByteArray tag) {
        return tag.byteArray == null ? new byte[0] : tag.byteArray;
    }

    /** MITE's one-argument array constructors leave the payload null; see {@link #stringData}. */
    private static int[] intArrayData(NBTTagIntArray tag) {
        return tag.intArray == null ? new int[0] : tag.intArray;
    }
}
