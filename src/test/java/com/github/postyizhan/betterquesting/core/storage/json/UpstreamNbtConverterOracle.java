package com.github.postyizhan.betterquesting.core.storage.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
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
 * Differential-testing oracle: upstream {@code betterquesting.api.utils.NBTConverter} transplanted
 * into the test source set so {@link NbtJsonCodec} can be diffed against the reference bytes instead
 * of against a reading of the reference source.
 *
 * <p>Reference: {@code BetterQuesting-master/src/main/java/betterquesting/api/utils/NBTConverter.java}
 * (MC 1.7.10 / Forge). Method names, branch order, control flow and the reference's defects are
 * preserved verbatim. Line references below are that file.
 *
 * <p><b>This class is not a port and must never be treated as one.</b> It exists only to produce the
 * reference output. Every departure from a character-for-character transplant is enumerated here;
 * anything not listed is verbatim.
 *
 * <h2>Deviations forced by the platform</h2>
 * <ol>
 *   <li><b>{@code NBTBase.NBTPrimitive} does not exist on MITE</b> (imported at :19, used at :142).
 *       {@code value instanceof NBTPrimitive} becomes {@code id >= 1 && id <= 6}. Not an invention:
 *       the reference's own {@code JsonObject} write path at :207 already discriminates numeric tags
 *       with {@code getId() >= 1 && getId() <= 6}, and 1..6 are exactly byte/short/int/long/float/
 *       double. The two spellings select the same six classes.</li>
 *   <li><b>1.7.10 obfuscated accessors do not exist on MITE.</b> Each is replaced by the public
 *       field MITE exposes for the same value, so no semantics change:
 *       {@code NBTTagString.func_150285_a_()} (:143, :211) becomes {@code .data};
 *       {@code NBTTagByteArray.func_150292_c()} (:146, :243) becomes {@code .byteArray};
 *       {@code NBTTagIntArray.func_150302_c()} (:152, :251) becomes {@code .intArray};
 *       {@code func_150290_f/150289_e/150287_d/150288_h/150286_g/150291_c} in {@code getNumber}
 *       (:418-434) become {@code .data} on the matching tag class. The reference's accessors return
 *       the tag's own primitive type, and so do the fields, so {@link #getNumber} still boxes
 *       Byte/Short/Integer/Long/Float/Double exactly as :418 does.</li>
 *   <li><b>{@code NBTTagCompound.func_150296_c()} (:185, :267) does not exist on MITE.</b>
 *       {@link #keySet} reflects the private {@code tagMap} field and returns its {@code keySet()}.
 *       That is what {@code func_150296_c} returned, and MITE's {@code tagMap} is a
 *       {@code HashMap} exactly as 1.7.10's was, so the unsorted iteration order the streaming path
 *       depends on at :185 is preserved. The production compatibility helper
 *       {@code NbtCompat.sortedKeys} is deliberately <em>not</em> used here: it sorts, which would
 *       import the port's ordering decision into the oracle and make the ordering diff vacuous.</li>
 *   <li><b>{@code NBTTagList.tagList} reflection (:134, :518-531) is kept, not replaced.</b> The
 *       reference tried {@code field_74747_a} then {@code tagList}; MITE's field is named
 *       {@code tagList}, which is the reference's own second candidate, so {@link #getTagList} keeps
 *       reflecting rather than calling {@code tagCount()}/{@code tagAt(int)}. This is intentional:
 *       reading elements by a different mechanism than {@code NbtCompat.elements} keeps the two
 *       sides of the diff independent, so a defect in that helper cannot cancel out.</li>
 *   <li><b>Static-initializer failure is fatal here</b> (:518-531 logged and continued with a null
 *       field, degrading every list to empty). A silently empty oracle would make the diff pass
 *       while proving nothing, so {@link #F_TAG_LIST}/{@link #F_TAG_MAP} throw on failure.</li>
 *   <li><b>{@code QuestingAPI.getLogger()} (:308, :396, :400, :528) is dropped.</b> All four sites
 *       only emit log lines; {@link #log} is a no-op. No branch is affected: the {@code continue} at
 *       :310 and the {@code return new NBTTagString()} at :402 are kept.</li>
 *   <li><b>{@code new NBTTagString()} (:326, :402) has no MITE equivalent.</b> 1.7.10's no-argument
 *       constructor left {@code data} as {@code ""}; MITE's one-argument constructor takes the tag
 *       <em>name</em> and leaves {@code data} null, so {@code new NBTTagString("", "")} is used to
 *       reach the same state the reference produced.</li>
 *   <li><b>Numeric and array constructors take a leading name on MITE.</b> {@code new NBTTagByte(b)}
 *       becomes {@code new NBTTagByte("", b)} and likewise for short/int/long/float/double and
 *       {@code NBTTagByteArray}/{@code NBTTagIntArray} (:335, :353, :364, :437-452). MITE's
 *       {@code new NBTTagList()} and {@code new NBTTagCompound()} already default the name to
 *       {@code ""}, so :341 and :366 are verbatim.</li>
 *   <li><b>The {@code UuidValueType} inner class (:48-132) is not transplanted.</b> It is the only
 *       user of {@code net.minecraftforge.common.util.Constants} (:105, :123), which Forge supplies
 *       and MITE does not, and it takes no part in JSON conversion.</li>
 * </ol>
 *
 * <h2>Defects preserved on purpose</h2>
 * <ul>
 *   <li>:167-171 opens a JSON array for a {@code format=false} list and never closes it. Kept, so
 *       {@code NbtJsonCodec}'s correction is provable rather than asserted.</li>
 *   <li>:510 overwrites the array-scanning result with {@code tagID = 9} unconditionally, making
 *       :477-509 dead. Kept so the diff shows the scan is unobservable.</li>
 *   <li>:146 and :152 iterate {@code byteArray}/{@code intArray} with no null guard, and :143 and
 *       :211 pass {@code data} through unguarded.</li>
 *   <li>:185 iterates keys unsorted while :267 sorts them, so the reference's two write paths do not
 *       agree byte-for-byte on key order.</li>
 * </ul>
 */
final class UpstreamNbtConverterOracle {
    private static final Field F_TAG_LIST;
    private static final Field F_TAG_MAP;

    private UpstreamNbtConverterOracle() {
    }

    /** Replaces {@code QuestingAPI.getLogger().log(...)}; see deviation 6. */
    private static void log(String message) {
        // Intentionally empty: the reference only logged here.
    }

    /** Deviation 3: stands in for {@code parent.func_150296_c()} at :185 and :267. */
    @SuppressWarnings("unchecked")
    private static Set<String> keySet(NBTTagCompound parent) {
        try {
            return ((Map<String, NBTBase>) F_TAG_MAP.get(parent)).keySet();
        } catch (IllegalAccessException unreachable) {
            throw new AssertionError(unreachable);
        }
    }

    /**
     * Pulls the raw list out of the NBTTagList
     *
     * <p>Reference :408-415, with deviation 5: the reference swallowed the failure and returned an
     * empty list.
     */
    static List<NBTBase> getTagList(NBTTagList tag) {
        try {
            return (ArrayList<NBTBase>) F_TAG_LIST.get(tag);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------- streaming write path, reference :139-197
    // This is the path real save files take: SaveLoadHandler.java:314/341/349/357/369 write
    // QuestDatabase, QuestLines, Parties, NameCache and Lives through it, always with format=true.

    /**
     * Convert NBT tags to a JSON object
     *
     * <p>Reference :139-179.
     */
    private static void NBTtoJSON_Base(NBTBase value, boolean format, JsonWriter out) throws IOException {
        if (value == null || value.getId() == 0) out.beginObject()
            .endObject();
        else if (value.getId() >= 1 && value.getId() <= 6) out.value(getNumber(value)); // deviation 1
        else if (value instanceof NBTTagString) out.value(((NBTTagString) value).data); // deviation 2
        else if (value instanceof NBTTagByteArray) {
            out.beginArray();
            for (byte b : ((NBTTagByteArray) value).byteArray) { // deviation 2
                out.value(b);
            }
            out.endArray();
        } else if (value instanceof NBTTagIntArray) {
            out.beginArray();
            for (int b : ((NBTTagIntArray) value).intArray) { // deviation 2
                out.value(b);
            }
            out.endArray();
        } else if (value instanceof NBTTagList) {
            List<NBTBase> tagList = getTagList((NBTTagList) value);
            if (format) {
                out.beginObject();
                for (int i = 0; i < tagList.size(); i++) {
                    NBTBase tag = tagList.get(i);
                    out.name(i + ":" + tag.getId());
                    NBTtoJSON_Base(tag, true, out);
                }
                out.endObject();
            } else {
                out.beginArray();
                for (NBTBase tag : tagList) {
                    NBTtoJSON_Base(tag, false, out);
                }
                // Reference :171 ends here with no endArray(). Preserved; see "Defects preserved".
            }
        } else if (value instanceof NBTTagCompound) {
            NBTtoJSON_Compound((NBTTagCompound) value, out, format);
        } else {
            // idk man what is this
            out.beginObject()
                .endObject();
        }
    }

    /** Reference :181-197. Iterates keys unsorted, unlike the {@code JsonObject} path at :267. */
    public static void NBTtoJSON_Compound(NBTTagCompound parent, JsonWriter out, boolean format) throws IOException {
        out.beginObject();

        if (parent != null) for (String key : keySet(parent)) { // deviation 3
            NBTBase tag = parent.getTag(key);

            if (format) {
                out.name(key + ":" + tag.getId());
                NBTtoJSON_Base(tag, true, out);
            } else {
                out.name(key);
                NBTtoJSON_Base(tag, false, out);
            }
        }
        out.endObject();
    }

    // --------------------------------------------- JsonObject write path, reference :199-279
    // Reached only by the /bq_admin default export command and bq_standard loot, never by a save.
    // Unlike the streaming path this one sorts keys through a TreeSet at :267.

    /**
     * Convert NBT tags to a JSON object
     *
     * <p>Reference :199-259.
     */
    private static JsonElement NBTtoJSON_Base(NBTBase tag, boolean format) {
        if (tag == null) {
            return new JsonObject();
        }

        if (tag.getId() >= 1 && tag.getId() <= 6) {
            return new JsonPrimitive(getNumber(tag));
        }
        if (tag instanceof NBTTagString) {
            return new JsonPrimitive(((NBTTagString) tag).data); // deviation 2
        } else if (tag instanceof NBTTagCompound) {
            return NBTtoJSON_Compound((NBTTagCompound) tag, new JsonObject(), format);
        } else if (tag instanceof NBTTagList) {
            if (format) {
                JsonObject jAry = new JsonObject();

                List<NBTBase> tagList = getTagList((NBTTagList) tag);

                for (int i = 0; i < tagList.size(); i++) {
                    jAry.add(
                        i + ":"
                            + tagList.get(i)
                                .getId(),
                        NBTtoJSON_Base(tagList.get(i), true));
                }

                return jAry;
            } else {
                JsonArray jAry = new JsonArray();

                List<NBTBase> tagList = getTagList((NBTTagList) tag);

                for (NBTBase t : tagList) {
                    jAry.add(NBTtoJSON_Base(t, false));
                }

                return jAry;
            }
        } else if (tag instanceof NBTTagByteArray) {
            JsonArray jAry = new JsonArray();

            for (byte b : ((NBTTagByteArray) tag).byteArray) { // deviation 2
                jAry.add(new JsonPrimitive(b));
            }

            return jAry;
        } else if (tag instanceof NBTTagIntArray) {
            JsonArray jAry = new JsonArray();

            for (int i : ((NBTTagIntArray) tag).intArray) { // deviation 2
                jAry.add(new JsonPrimitive(i));
            }

            return jAry;
        } else {
            return new JsonObject(); // No valid types found. We'll just return this to prevent a NPE
        }
    }

    /** Reference :261-279. */
    public static JsonObject NBTtoJSON_Compound(NBTTagCompound parent, JsonObject jObj, boolean format) {
        if (parent == null) {
            return jObj;
        }

        final TreeSet<String> sortedKeys = new TreeSet<>(keySet(parent)); // deviation 3
        for (String key : sortedKeys) {
            NBTBase tag = parent.getTag(key);

            if (format) {
                jObj.add(key + ":" + tag.getId(), NBTtoJSON_Base(tag, true));
            } else {
                jObj.add(key, NBTtoJSON_Base(tag, false));
            }
        }

        return jObj;
    }

    // ------------------------------------------------------- read path, reference :281-403
    // Only one read path exists; both dialects come back through here.

    /**
     * Convert JsonObject to a NBTTagCompound
     *
     * <p>Reference :281-319.
     */
    public static NBTTagCompound JSONtoNBT_Object(JsonObject jObj, NBTTagCompound tags, boolean format) {
        if (jObj == null) {
            return tags;
        }

        for (Entry<String, JsonElement> entry : jObj.entrySet()) {
            String key = entry.getKey();

            if (!format) {
                tags.setTag(key, JSONtoNBT_Element(entry.getValue(), (byte) 0, false));
            } else {
                // Optimized key parsing without String.split()
                byte id = 0;
                String keyToUse = key;

                try {
                    int lastColonIndex = key.lastIndexOf(':');
                    if (lastColonIndex != -1) {
                        id = Byte.parseByte(key.substring(lastColonIndex + 1));
                        keyToUse = key.substring(0, lastColonIndex); // Simple colon cut
                    }
                } catch (Exception e) { // Catch all exceptions
                    // Invalid ID format, use original key and id=0
                    if (tags.hasKey(key)) {
                        log("JSON/NBT formatting conflict on key '" + key + "'. Skipping...");
                        continue;
                    }
                }

                tags.setTag(keyToUse, JSONtoNBT_Element(entry.getValue(), id, true));
            }
        }

        return tags;
    }

    /**
     * Tries to interpret the tagID from the JsonElement's contents
     *
     * <p>Reference :321-403.
     */
    private static NBTBase JSONtoNBT_Element(JsonElement jObj, byte id, boolean format) {
        if (jObj == null) {
            return new NBTTagString("", ""); // deviation 7
        }

        byte tagID = id <= 0 ? fallbackTagID(jObj) : id;

        try {
            if (tagID == 1 && (id <= 0 || jObj.getAsJsonPrimitive()
                .isBoolean())) // Edge case for BQ2 legacy files
            {
                return new NBTTagByte("", jObj.getAsBoolean() ? (byte) 1 : (byte) 0); // deviation 8
            } else if (tagID >= 1 && tagID <= 6) {
                return instanceNumber(jObj.getAsNumber(), tagID);
            } else if (tagID == 8) {
                return new NBTTagString("", jObj.getAsString()); // deviation 8
            } else if (tagID == 10) {
                return JSONtoNBT_Object(jObj.getAsJsonObject(), new NBTTagCompound(), format);
            } else if (tagID == 7) // Byte array
            {
                JsonArray jAry = jObj.getAsJsonArray();

                byte[] bAry = new byte[jAry.size()];

                for (int i = 0; i < jAry.size(); i++) {
                    bAry[i] = jAry.get(i)
                        .getAsByte();
                }

                return new NBTTagByteArray("", bAry); // deviation 8
            } else if (tagID == 11) {
                JsonArray jAry = jObj.getAsJsonArray();

                int[] iAry = new int[jAry.size()];

                for (int i = 0; i < jAry.size(); i++) {
                    iAry[i] = jAry.get(i)
                        .getAsInt();
                }

                return new NBTTagIntArray("", iAry); // deviation 8
            } else if (tagID == 9) {
                NBTTagList tList = new NBTTagList();

                if (jObj.isJsonArray()) {
                    JsonArray jAry = jObj.getAsJsonArray();
                    // enhanced for-loop for better performance
                    for (JsonElement jElm : jAry) {
                        tList.appendTag(JSONtoNBT_Element(jElm, (byte) 0, format));
                    }
                } else if (jObj.isJsonObject()) {
                    JsonObject jAry = jObj.getAsJsonObject();

                    for (Entry<String, JsonElement> entry : jAry.entrySet()) {
                        try {
                            // Avoid String.split() for better performance
                            String key = entry.getKey();
                            byte id2 = 0;
                            int lastColonIndex = key.lastIndexOf(':');
                            if (lastColonIndex != -1) {
                                id2 = Byte.parseByte(key.substring(lastColonIndex + 1));
                            }
                            tList.appendTag(JSONtoNBT_Element(entry.getValue(), id2, format));
                        } catch (Exception e) {
                            tList.appendTag(JSONtoNBT_Element(entry.getValue(), (byte) 0, format));
                        }
                    }
                }

                return tList;
            }
        } catch (Exception e) {
            log("An error occured while parsing JsonElement to NBTBase (" + tagID + "):" + e);
        }

        log("Unknown NBT representation for " + jObj.toString() + " (ID: " + tagID + ")");
        return new NBTTagString("", ""); // deviation 7
    }

    // ------------------------------------------------- numeric helpers, reference :417-516

    /** Reference :417-434, with the accessors of deviation 2. */
    static Number getNumber(NBTBase tag) {
        if (tag instanceof NBTTagByte) {
            return ((NBTTagByte) tag).data;
        } else if (tag instanceof NBTTagShort) {
            return ((NBTTagShort) tag).data;
        } else if (tag instanceof NBTTagInt) {
            return ((NBTTagInt) tag).data;
        } else if (tag instanceof NBTTagFloat) {
            return ((NBTTagFloat) tag).data;
        } else if (tag instanceof NBTTagDouble) {
            return ((NBTTagDouble) tag).data;
        } else if (tag instanceof NBTTagLong) {
            return ((NBTTagLong) tag).data;
        } else {
            return 0;
        }
    }

    /** Reference :436-452, with the constructors of deviation 8. */
    static NBTBase instanceNumber(Number num, byte type) {
        switch (type) {
            case 1:
                return new NBTTagByte("", num.byteValue());
            case 2:
                return new NBTTagShort("", num.shortValue());
            case 3:
                return new NBTTagInt("", num.intValue());
            case 4:
                return new NBTTagLong("", num.longValue());
            case 5:
                return new NBTTagFloat("", num.floatValue());
            default:
                return new NBTTagDouble("", num.doubleValue());
        }
    }

    /**
     * Reference :454-516, verbatim including the unconditional {@code tagID = 9} at :510 that
     * discards the array scan above it.
     */
    private static byte fallbackTagID(JsonElement jObj) {
        byte tagID = 0;

        if (jObj.isJsonPrimitive()) {
            JsonPrimitive prim = jObj.getAsJsonPrimitive();

            if (prim.isNumber()) {
                if (prim.getAsString()
                    .contains(".")) // Just in case we'll choose the largest possible container supporting this number
                // type (Long or Double)
                {
                    tagID = 6;
                } else {
                    tagID = 4;
                }
            } else if (prim.isBoolean()) {
                tagID = 1;
            } else {
                tagID = 8; // Non-number primitive. Assume string
            }
        } else if (jObj.isJsonArray()) {
            JsonArray array = jObj.getAsJsonArray();

            for (JsonElement entry : array) {
                if (entry.isJsonPrimitive() && tagID == 0) // Note: TagLists can only support Integers, Bytes and
                // Compounds (Strings can be stored but require special
                // handling)
                {
                    try {
                        for (JsonElement element : array) {
                            // Make sure all entries can be bytes
                            if (element.getAsLong() != element.getAsByte()) // In case casting works but overflows
                            {
                                throw new ClassCastException();
                            }
                        }
                        tagID = 7; // Can be used as byte
                    } catch (Exception e1) {
                        try {
                            for (JsonElement element : array) {
                                // Make sure all entries can be integers
                                if (element.getAsLong() != element.getAsInt()) // In case casting works but overflows
                                {
                                    throw new ClassCastException();
                                }
                            }
                            tagID = 11;
                        } catch (Exception e2) {
                            tagID = 9; // Is primitive however requires TagList interpretation
                        }
                    }
                } else if (!entry.isJsonPrimitive()) {
                    break;
                }
            }

            tagID = 9; // No data to judge format. Assuming tag list
        } else {
            tagID = 10;
        }

        return tagID;
    }

    /** Reference :518-531, with deviation 5: failure is fatal instead of logged. */
    static {
        try {
            F_TAG_LIST = NBTTagList.class.getDeclaredField("tagList");
            F_TAG_LIST.setAccessible(true);
            F_TAG_MAP = NBTTagCompound.class.getDeclaredField("tagMap");
            F_TAG_MAP.setAccessible(true);
        } catch (ReflectiveOperationException e2) {
            throw new ExceptionInInitializerError(e2);
        }
    }
}
