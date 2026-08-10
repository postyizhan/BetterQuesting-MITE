package com.github.postyizhan.betterquesting.core.storage.json;

import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.params.provider.Arguments;

/**
 * Inputs shared by {@link NbtJsonCodecOracleDiffTest}. Kept separate from the assertions so the
 * coverage matrix is readable in one place.
 *
 * <p>Every case is rebuilt on each call: the NBT tags are mutable and
 * {@code UpstreamNbtConverterOracle.JSONtoNBT_Object} writes into its target, so no instance may be
 * shared between the two sides of a diff.
 */
final class NbtDiffFixtures {
    private NbtDiffFixtures() {
    }

    /** Named NBT roots, each supplied fresh. Consumed by the NBT-to-JSON diffs. */
    static List<Arguments> nbtCases() {
        List<Arguments> cases = new ArrayList<>();

        cases.add(nbt("emptyRoot", root -> {
        }));
        cases.add(nbt("singleKey", root -> root.setInteger("only", 1)));

        // All ten tag types that can appear as a compound value, plus TAG_End (id 0) which no
        // well-formed file contains but which both sides must survive.
        cases.add(nbt("everyTagType", root -> {
            root.setTag("t01byte", new NBTTagByte("", (byte) 7));
            root.setTag("t02short", new NBTTagShort("", (short) 8));
            root.setTag("t03int", new NBTTagInt("", 9));
            root.setTag("t04long", new NBTTagLong("", 10L));
            root.setTag("t05float", new NBTTagFloat("", 11.5f));
            root.setTag("t06double", new NBTTagDouble("", 12.5d));
            root.setTag("t07byteArray", new NBTTagByteArray("", new byte[] {1, 2}));
            root.setTag("t08string", new NBTTagString("", "s"));
            NBTTagList list = new NBTTagList("");
            list.appendTag(new NBTTagInt("", 13));
            root.setTag("t09list", list);
            NBTTagCompound child = new NBTTagCompound();
            child.setInteger("inner", 14);
            root.setTag("t10compound", child);
            root.setTag("t11intArray", new NBTTagIntArray("", new int[] {3, 4}));
        }));
        cases.add(nbt("tagEndAsCompoundValue", root -> root.setTag("end", new NBTTagEnd())));
        cases.add(nbt("tagEndInsideList", root -> {
            NBTTagList list = new NBTTagList("");
            list.appendTag(new NBTTagEnd());
            list.appendTag(new NBTTagInt("", 1));
            root.setTag("entries", list);
        }));

        // Boundary values. NaN and the infinities are excluded on purpose: JsonWriter rejects them
        // and JsonPrimitive does not, so the two write paths disagree for reasons that have nothing
        // to do with this port. NonFiniteDoublesTest covers them separately.
        cases.add(nbt("integralBoundaries", root -> {
            root.setTag("byteMin", new NBTTagByte("", Byte.MIN_VALUE));
            root.setTag("byteMax", new NBTTagByte("", Byte.MAX_VALUE));
            root.setTag("byteNeg", new NBTTagByte("", (byte) -7));
            root.setTag("shortMin", new NBTTagShort("", Short.MIN_VALUE));
            root.setTag("shortMax", new NBTTagShort("", Short.MAX_VALUE));
            root.setTag("shortNeg", new NBTTagShort("", (short) -300));
            root.setTag("intMin", new NBTTagInt("", Integer.MIN_VALUE));
            root.setTag("intMax", new NBTTagInt("", Integer.MAX_VALUE));
            root.setTag("longMin", new NBTTagLong("", Long.MIN_VALUE));
            root.setTag("longMax", new NBTTagLong("", Long.MAX_VALUE));
            root.setTag("longBeyondDouble", new NBTTagLong("", 9007199254740993L));
            root.setTag("zero", new NBTTagLong("", 0L));
        }));
        cases.add(nbt("floatingBoundaries", root -> {
            root.setTag("floatTenth", new NBTTagFloat("", 0.1f));
            root.setTag("doubleTenth", new NBTTagDouble("", 0.1d));
            root.setTag("floatMin", new NBTTagFloat("", Float.MIN_VALUE));
            root.setTag("floatMax", new NBTTagFloat("", Float.MAX_VALUE));
            root.setTag("doubleMin", new NBTTagDouble("", Double.MIN_VALUE));
            root.setTag("doubleMax", new NBTTagDouble("", Double.MAX_VALUE));
            root.setTag("negativeZero", new NBTTagDouble("", -0.0d));
            root.setTag("negativeFraction", new NBTTagDouble("", -3.7d));
            root.setTag("floatWhole", new NBTTagFloat("", 2.0f));
            root.setTag("doubleWhole", new NBTTagDouble("", 2.0d));
        }));

        cases.add(nbt("arrayBoundaries", root -> {
            root.setTag("bytes", new NBTTagByteArray("", new byte[] {0, 1, -1, Byte.MIN_VALUE, Byte.MAX_VALUE}));
            root.setTag("ints", new NBTTagIntArray("", new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}));
        }));
        cases.add(nbt("emptyContainers", root -> {
            root.setTag("emptyByteArray", new NBTTagByteArray("", new byte[0]));
            root.setTag("emptyIntArray", new NBTTagIntArray("", new int[0]));
            root.setTag("emptyList", new NBTTagList(""));
            root.setTag("emptyCompound", new NBTTagCompound());
        }));

        cases.add(nbt("listOfLists", root -> {
            NBTTagList outer = new NBTTagList("");
            NBTTagList first = new NBTTagList("");
            first.appendTag(new NBTTagInt("", 1));
            first.appendTag(new NBTTagInt("", 2));
            NBTTagList second = new NBTTagList("");
            outer.appendTag(first);
            outer.appendTag(second);
            root.setTag("outer", outer);
        }));
        cases.add(nbt("listOfCompounds", root -> {
            NBTTagList list = new NBTTagList("");
            for (int i = 0; i < 3; i++) {
                NBTTagCompound element = new NBTTagCompound();
                element.setInteger("index", i);
                element.setString("label", "e" + i);
                list.appendTag(element);
            }
            root.setTag("entries", list);
        }));
        cases.add(nbt("listOfTwelveElements", root -> {
            // Past index 9 the "<index>:<id>" keys stop sorting the way they are emitted.
            NBTTagList list = new NBTTagList("");
            for (int i = 0; i < 12; i++) {
                list.appendTag(new NBTTagLong("", i));
            }
            root.setTag("entries", list);
        }));
        cases.add(nbt("listOfTwelveCompounds", root -> {
            NBTTagList list = new NBTTagList("");
            for (int i = 0; i < 12; i++) {
                NBTTagCompound element = new NBTTagCompound();
                element.setInteger("i", i);
                list.appendTag(element);
            }
            root.setTag("entries", list);
        }));
        cases.add(nbt("mixedTypeList", root -> {
            NBTTagList list = new NBTTagList("");
            list.appendTag(new NBTTagString("", "s"));
            list.appendTag(new NBTTagDouble("", 1.5d));
            list.appendTag(new NBTTagByteArray("", new byte[] {9}));
            list.appendTag(new NBTTagList(""));
            list.appendTag(new NBTTagCompound());
            root.setTag("entries", list);
        }));

        cases.add(nbt("compoundNestedFourDeep", root -> {
            NBTTagCompound level3 = new NBTTagCompound();
            level3.setString("leaf", "bottom");
            NBTTagCompound level2 = new NBTTagCompound();
            level2.setTag("level3", level3);
            NBTTagCompound level1 = new NBTTagCompound();
            level1.setTag("level2", level2);
            root.setTag("level1", level1);
        }));
        cases.add(nbt("deepMixedStructure", root -> {
            NBTTagList quests = new NBTTagList("");
            for (int i = 0; i < 2; i++) {
                NBTTagCompound quest = new NBTTagCompound();
                quest.setInteger("questID", i);
                quest.setString("betterquesting:name", "Quest " + i);
                NBTTagList tasks = new NBTTagList("");
                NBTTagCompound task = new NBTTagCompound();
                task.setString("taskID", "bq_standard:retrieval");
                task.setIntArray("required", new int[] {1, 2, 3});
                tasks.appendTag(task);
                quest.setTag("tasks", tasks);
                quests.appendTag(quest);
            }
            root.setTag("questDatabase", quests);
            root.setInteger("format", 2);
        }));

        cases.add(nbt("keysContainingColons", root -> {
            root.setString("betterquesting:editmode", "on");
            root.setString("a:b:c", "deep");
            root.setInteger(":", 1);
            root.setInteger("trailing:", 2);
            root.setInteger(":leading", 3);
            root.setInteger("colon:9", 4);
        }));
        cases.add(nbt("specialCharacters", root -> {
            root.setString("emoji", "\uD83D\uDE00\uD83E\uDD84");
            root.setString("cjk", "\u4E2D\u6587\u6E2C\u8A66");
            root.setString("quotes", "he said \"hi\"");
            root.setString("backslash", "C:\\path\\to");
            root.setString("newline", "a\nb\r\tc");
            root.setString("sqlish", "' OR 1=1 --");
            root.setString("nul", "\u0000end");
            root.setString("control", "\u0001\u001F");
            root.setString("empty", "");
            root.setString("\uD83D\uDE00key", "emojiKey");
            root.setString("", "emptyKey");
        }));
        cases.add(nbt("manyKeysForOrdering", root -> {
            for (int i = 0; i < 30; i++) {
                root.setInteger("k" + i, i);
            }
        }));

        return cases;
    }

    /** Named JSON documents for the JSON-to-NBT diffs, as text so the input is exactly auditable. */
    static List<Arguments> formatJsonCases() {
        List<Arguments> cases = new ArrayList<>();

        cases.add(json("emptyDocument", "{}"));
        cases.add(json("everyDeclaredId",
            "{\"a:1\":1,\"b:2\":2,\"c:3\":3,\"d:4\":4,\"e:5\":5.5,\"f:6\":6.5,\"g:7\":[1,2],"
                + "\"h:8\":\"s\",\"i:9\":{\"0:3\":1},\"j:10\":{\"k:3\":2},\"l:11\":[3,4]}"));
        cases.add(json("declaredIdWinsOverLiteralShape",
            "{\"asByte:1\":300,\"asShort:2\":70000,\"asInt:3\":5,\"asLong:4\":6,\"asFloat:5\":1.5,"
                + "\"asDouble:6\":2}"));
        cases.add(json("integralBoundaryLiterals",
            "{\"longMin:4\":-9223372036854775808,\"longMax:4\":9223372036854775807,"
                + "\"intMin:3\":-2147483648,\"intMax:3\":2147483647,\"byteMin:1\":-128,"
                + "\"byteMax:1\":127,\"shortMin:2\":-32768,\"shortMax:2\":32767}"));
        cases.add(json("floatingLiterals",
            "{\"tenthAsFloat:5\":0.1,\"tenthAsDouble:6\":0.1,\"negZero:6\":-0.0,\"negFraction:6\":-3.7,"
                + "\"huge:6\":1.7976931348623157E308,\"tiny:6\":4.9E-324,\"overflow:6\":1e999,"
                + "\"precise:6\":0.1234567890123456789}"));
        cases.add(json("fractionalLiteralUnderIntegralId", "{\"n:3\":-3.7,\"m:4\":2.9,\"b:1\":1.5,\"s:2\":2.5}"));
        cases.add(json("exponentLiteralUnderIntegralId", "{\"e:4\":1E3,\"f:3\":1e3}"));
        cases.add(json("beyondLongRange", "{\"big:4\":99999999999999999999,\"bigDouble:6\":99999999999999999999}"));
        cases.add(json("booleansUnderByteId", "{\"on:1\":true,\"off:1\":false,\"bare\":true,\"bareFalse\":false}"));
        cases.add(json("undeclaredPrimitives",
            "{\"whole\":7,\"fraction\":7.0,\"exponentNoDot\":1E3,\"text\":\"s\",\"flag\":true,"
                + "\"negative\":-5,\"negativeFraction\":-5.5}"));
        cases.add(json("jsonNullValues", "{\"a:10\":null,\"b:8\":null,\"c:4\":null,\"d\":null}"));

        cases.add(json("unparsableSuffixes",
            "{\"betterquesting:name\":\"x\",\"a:xyz\":1,\"big:300\":2,\"neg:-1\":3,\"neg:-129\":4,"
                + "\"empty:\":5,\"onlyColon:\":6}"));
        cases.add(json("colonKeyShapes",
            "{\"::1\":1,\"trailing::8\":\"t\",\":leading:3\":2,\"a:b:c:8\":\"deep\",\":8\":\"emptyKey\"}"));
        cases.add(json("duplicateKeyAfterSuffixStrip", "{\"a:3\":1,\"a:8\":\"second\"}"));
        cases.add(json("unknownIds", "{\"weird:99\":1,\"alsoWeird:12\":2,\"zero:0\":3,\"neg:-3\":4}"));
        cases.add(json("typeMismatchAgainstDeclaredId",
            "{\"stringAsInt:3\":\"nope\",\"objectAsString:8\":{},\"objectAsByte:1\":{},"
                + "\"arrayAsCompound:10\":[1],\"primitiveAsList:9\":5,\"objectAsByteArray:7\":{},"
                + "\"stringInByteArray:7\":[\"x\"],\"objectAsIntArray:11\":{}}"));

        cases.add(json("listWithIndexedIds", "{\"entries:9\":{\"0:5\":1.5,\"1:2\":3,\"2:8\":\"s\",\"3:10\":{}}}"));
        cases.add(json("listWithTwelveIndexedEntries",
            "{\"entries:9\":{\"0:4\":0,\"1:4\":1,\"2:4\":2,\"3:4\":3,\"4:4\":4,\"5:4\":5,\"6:4\":6,"
                + "\"7:4\":7,\"8:4\":8,\"9:4\":9,\"10:4\":10,\"11:4\":11}}"));
        cases.add(json("listKeysSortedLexicographically",
            "{\"entries:9\":{\"0:4\":0,\"1:4\":1,\"10:4\":10,\"11:4\":11,\"2:4\":2,\"3:4\":3,\"4:4\":4,"
                + "\"5:4\":5,\"6:4\":6,\"7:4\":7,\"8:4\":8,\"9:4\":9}}"));
        cases.add(json("listWithUnparsableIndexSuffix",
            "{\"entries:9\":{\"zero\":1.5,\"one:zz\":2,\"two:300\":3,\"three:\":4}}"));
        cases.add(json("listAsJsonArrayUnderListId", "{\"entries:9\":[1,2.5,\"s\",{},[]]}"));
        cases.add(json("nestedListsAndCompounds",
            "{\"outer:9\":{\"0:9\":{\"0:9\":{\"0:8\":\"deep\"}},\"1:10\":{\"inner:9\":{\"0:3\":1}}}}"));
        cases.add(json("emptyContainers",
            "{\"emptyObjectAsCompound:10\":{},\"emptyObjectAsList:9\":{},\"emptyArrayAsList:9\":[],"
                + "\"emptyArrayAsByteArray:7\":[],\"emptyArrayAsIntArray:11\":[],\"bare\":{},\"bareArray\":[]}"));
        cases.add(json("specialCharacters",
            "{\"emoji:8\":\"\uD83D\uDE00\",\"cjk:8\":\"\u4E2D\u6587\",\"quotes:8\":\"he \\\"said\\\"\","
                + "\"escapes:8\":\"a\\nb\\tc\\\\d\",\"nul:8\":\"\\u0000end\",\"sqlish:8\":\"' OR 1=1 --\","
                + "\"\uD83D\uDE00key:8\":\"emojiKey\"}"));

        return cases;
    }

    /** Documents read back under {@code format=false}; suffixes here are literal key characters. */
    static List<Arguments> plainJsonCases() {
        List<Arguments> cases = new ArrayList<>();

        cases.add(json("emptyDocument", "{}"));
        cases.add(json("scalars", "{\"whole\":7,\"fraction\":7.5,\"text\":\"s\",\"flag\":true,\"nil\":null}"));
        cases.add(json("suffixesAreLiteralKeys", "{\"a:1\":1,\"betterquesting:name\":\"x\",\"weird:99\":2}"));
        cases.add(json("arraysBecomeLists", "{\"bytes\":[1,-2],\"ints\":[5,-6],\"empty\":[],"
            + "\"mixed\":[1,\"s\",{},[2]],\"nested\":[[1,2],[3]]}"));
        cases.add(json("nestedObjects", "{\"a\":{\"b\":{\"c\":1}},\"empty\":{}}"));
        cases.add(json("numericBoundaries",
            "{\"longMax\":9223372036854775807,\"longMin\":-9223372036854775808,\"beyondLong\":"
                + "99999999999999999999,\"exponent\":1E3,\"exponentDotted\":1.0E3,\"negZero\":-0.0}"));
        cases.add(json("specialCharacters",
            "{\"emoji\":\"\uD83D\uDE00\",\"escapes\":\"a\\nb\\tc\",\"nul\":\"\\u0000\",\"empty\":\"\"}"));

        return cases;
    }

    private static Arguments nbt(String name, java.util.function.Consumer<NBTTagCompound> builder) {
        return Arguments.of(name, (java.util.function.Supplier<NBTTagCompound>) () -> {
            NBTTagCompound root = new NBTTagCompound();
            builder.accept(root);
            return root;
        });
    }

    private static Arguments json(String name, String document) {
        return Arguments.of(name, document);
    }
}
