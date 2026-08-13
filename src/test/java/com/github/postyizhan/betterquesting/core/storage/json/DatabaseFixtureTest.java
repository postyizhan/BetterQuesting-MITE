package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden database-shape fixtures for the JSON storage layer (batch B3a, plan.md stage 3 item 5).
 *
 * <p>The files under {@code fixtures/database} are hand-authored to the field names and nesting the
 * upstream {@code writeToNBT} methods emit, in the {@code format=true} dialect that real saves use
 * (SaveLoadHandler.java:314/341/369). They are <b>not</b> captured from a real 1.7.10 world; no such
 * world exists in the repository, so nothing here proves byte-level upstream compatibility. What is
 * proven is that {@link NbtJsonCodec} and the transplanted {@link UpstreamNbtConverterOracle} agree
 * on every document, in both directions, for these realistic shapes.
 *
 * <p>Categories: empty databases (QuestDatabase, QuestLineDatabase, Parties, QuestSettings); a
 * typical multi-line database with prerequisites of a non-{@code NORMAL} {@code RequirementType},
 * tasks, rewards and property containers; a quest line with twelve quests to exercise the
 * past-index-9 list-key ordering hazard; the legacy single-file {@code QuestProgress.json}; and the
 * per-player {@code QuestProgress/<uuid>.json} shape. The oversized case is generated here rather
 * than committed as a multi-megabyte blob.
 *
 * <p>Comparisons go through {@link NbtCanonical}: compounds are diffed by parsed structure, never by
 * serialized string, because upstream's real save path iterates {@code HashMap} key order and no
 * multi-key compound has a byte-level upstream baseline (handoff.md 4.2d). Assertions on an exact
 * serialized string below verify only this port's own convention and claim nothing about upstream.
 *
 * <p>Not a pure-JVM test: the domain layer exposes {@code net.minecraft.NBT*}, so the Gradle
 * Minecraft classpath is required.
 */
class DatabaseFixtureTest {
    private static final String DATABASE = "database";

    private final NbtJsonCodec codec = new NbtJsonCodec();

    @TempDir
    Path dataDirectory;

    private final List<String> warnings = new ArrayList<>();

    /** Every committed fixture file name, so a file added without a test is still exercised. */
    static Stream<String> databaseFixtures() {
        return MalformedJsonFixtures.names(DATABASE).stream();
    }

    // ------------------------------------------------------------- bidirectional oracle diffs

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseFixtures")
    void readMatchesTheOracle(String fixture) throws IOException {
        JsonObject parsed = parseFixture(fixture);

        // JSON -> NBT through both implementations; structural (not string) NBT equality.
        assertEquals(
            NbtCanonical.nbt(UpstreamNbtConverterOracle.JSONtoNBT_Object(parsed, new NBTTagCompound(), true)),
            NbtCanonical.nbt(codec.toNbt(parsed, new NBTTagCompound(), true)),
            fixture + " read diverged");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseFixtures")
    void writeMatchesTheOracle(String fixture) throws IOException {
        NBTTagCompound root = codec.toNbt(parseFixture(fixture), new NBTTagCompound(), true);

        // NBT -> JSON through both the element path and the streaming path; order-insensitive
        // structural equality, since upstream's real save path leaves compound key order unsorted
        // (NBTConverter.java:185) while this port sorts.
        String oracleElement = UpstreamNbtConverterOracle
            .NBTtoJSON_Compound(root, new JsonObject(), true).toString();
        assertEquals(NbtCanonical.json(parse(oracleElement)),
            NbtCanonical.json(codec.toJson(root, true)), fixture + " element write diverged");
        assertEquals(NbtCanonical.json(parse(oracleElement)),
            NbtCanonical.json(parse(streamed(root))), fixture + " streaming write diverged");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseFixtures")
    void roundTripThroughTheStoreIsStable(String fixture) throws IOException {
        // parse -> NBT -> JSON -> NBT: the second NBT must equal the first, so a save/load cycle on
        // realistic data neither drops nor mutates a tag. This is this port's own stability contract.
        NBTTagCompound first = codec.toNbt(parseFixture(fixture), new NBTTagCompound(), true);
        NBTTagCompound second = codec.toNbt(parse(streamed(first)), new NBTTagCompound(), true);
        assertEquals(NbtCanonical.nbt(first), NbtCanonical.nbt(second), fixture + " round trip drifted");
    }

    @Test
    void everyDatabaseFixtureFileIsExpected() {
        // A deletion or an accidental rename is caught here rather than silently shrinking coverage.
        assertEquals(
            List.of(
                "empty-parties.json",
                "empty-quest-database.json",
                "empty-quest-line-database.json",
                "empty-quest-settings.json",
                "fluid-placeholder.json",
                "legacy-quest-progress.json",
                "missing-dimension.json",
                "missing-entity.json",
                "missing-item.json",
                "player-quest-progress.json",
                "quest-line-twelve-quests.json",
                "typical-parties.json",
                "typical-quest-database.json"),
            MalformedJsonFixtures.names(DATABASE));
    }

    // -------------------------------------------------------------- list-order hazard, pinned

    @Test
    void twelveQuestListKeepsNaturalOrderPastIndexNine() throws IOException {
        // The quests list carries indices 0..11. The reader keys off the element-id suffix only and
        // appends in member order (NbtJsonCodec.java:352-365), so the list must come back 0..11, not
        // the lexicographic 0,1,10,11,2,... that a key-sorting external tool would impose. Same
        // hazard the synthetic NbtJsonCodecTest cases pin; here it rides a realistic quest line.
        NBTTagCompound root = codec.toNbt(parseFixture("quest-line-twelve-quests.json"), new NBTTagCompound(), true);
        NBTTagCompound line = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(root, "questLines"), 0);
        NBTTagList quests = NbtCompat.getListOrEmpty(line, "quests");

        List<Long> order = new ArrayList<>();
        for (int i = 0; i < quests.tagCount(); i++) {
            order.add(NbtCompat.getCompoundAt(quests, i).getLong("questIDHigh"));
        }
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), order,
            "list elements must stay in natural index order, not lexicographic key order");
    }

    @Test
    void modCustomStackTagKeyKeepsItsOwnColon() throws IOException {
        // A retrieval task's item stack carries a mod-authored tag named "somemod:charge", so the
        // encoded key is "somemod:charge:3" — two colons. The reader must split on the last one
        // (NbtJsonCodec.java:263), otherwise the key survives as the raw "somemod:charge:3" with
        // id 0. The synthetic cases pin the rule; this rides the real stack layout, where the
        // container key is MITE's lowercase "tag" (ItemStack.func_77955_b).
        NBTTagCompound root = codec.toNbt(parseFixture("typical-quest-database.json"), new NBTTagCompound(), true);
        NBTTagCompound quest = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(root, "questDatabase"), 1);
        NBTTagCompound task = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(quest, "tasks"), 0);
        NBTTagCompound stack = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(task, "requiredItems"), 0);
        NBTTagCompound stackTag = stack.getCompoundTag("tag");

        assertEquals(List.of("display", "somemod:charge"), NbtCompat.sortedKeys(stackTag),
            "the mod key must keep its internal colon and lose only the type suffix");
        assertEquals(42, stackTag.getInteger("somemod:charge"),
            "the value must land under the colon-bearing key, not a truncated one");
    }

    @Test
    void fixtureFieldNamesMatchTheUpstreamWritePaths() throws IOException {
        // Round-trip and oracle-diff tests read the same fixture on both sides, so a fixture that
        // invents a field name still passes everything. These names were each checked against the
        // upstream writer; pinning them here is what stops a silent drift back.
        NBTTagCompound root = codec.toNbt(parseFixture("typical-quest-database.json"), new NBTTagCompound(), true);
        NBTTagCompound quest = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(root, "questDatabase"), 0);
        NBTTagCompound reward = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(quest, "rewards"), 0);

        // RewardXP.java:57-58 writes setInteger("amount") and setBoolean("isLevels") — not xp/levels.
        assertEquals(List.of("amount", "index", "isLevels", "rewardID"), NbtCompat.sortedKeys(reward),
            "bq_standard:xp reward fields must match RewardXP.writeToNBT");

        // QuestLineDatabase.java:127 delegates to UuidValueType.QUEST_LINE.writeId, which writes the
        // two longs questLineIDHigh/questLineIDLow (NBTConverter.java:69-72) — never a bare
        // questLineID, which the read path at QuestLineDatabase.java:147-153 cannot consume.
        NBTTagCompound line = NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(root, "questLines"), 0);
        assertTrue(line.hasKey("questLineIDHigh") && line.hasKey("questLineIDLow"),
            "quest lines must carry the High/Low id pair");
        assertFalse(line.hasKey("questLineID"), "a bare questLineID is not an upstream field");
    }

    @Test
    void missingContentFixturesKeepSourceFieldsForMigration() throws IOException {
        // These are deliberately opaque boundary fixtures: the MITE port has no Forge item/entity/
        // dimension/fluid registry here, so B3 must preserve source identifiers and nested tags for
        // a later placeholder/reporting layer rather than silently replacing them.
        NBTTagCompound item = codec.toNbt(parseFixture("missing-item.json"), new NBTTagCompound(), true);
        NBTTagCompound itemTask = task(item);
        NBTTagCompound itemStack = NbtCompat.getCompoundAt(
            NbtCompat.getListOrEmpty(itemTask, "requiredItems"), 0);
        assertEquals("missingmod:crystal", itemStack.getString("id"));
        assertEquals(2, itemStack.getInteger("Count"));
        assertEquals(7, itemStack.getShort("Damage"));
        assertEquals("Unregistered crystal",
            itemStack.getCompoundTag("tag").getCompoundTag("display").getString("Name"));

        NBTTagCompound entity = codec.toNbt(parseFixture("missing-entity.json"), new NBTTagCompound(), true);
        NBTTagCompound entityTask = task(entity);
        assertEquals("missingmod:warden", entityTask.getString("target"));
        assertEquals("Unregistered target", entityTask.getCompoundTag("targetNBT").getString("CustomName"));

        NBTTagCompound dimension = codec.toNbt(parseFixture("missing-dimension.json"), new NBTTagCompound(), true);
        NBTTagCompound dimensionTask = task(dimension);
        assertEquals(77, dimensionTask.getInteger("dimension"));
        assertEquals(12, dimensionTask.getInteger("posX"));
        assertEquals(-9, dimensionTask.getInteger("posZ"));

        NBTTagCompound fluid = codec.toNbt(parseFixture("fluid-placeholder.json"), new NBTTagCompound(), true);
        NBTTagCompound fluidTask = task(fluid);
        NBTTagCompound fluidStack = NbtCompat.getCompoundAt(
            NbtCompat.getListOrEmpty(fluidTask, "requiredFluids"), 0);
        assertEquals("missingmod:brine", fluidStack.getString("FluidName"));
        assertEquals(1000, fluidStack.getInteger("Amount"));
        assertEquals(42, fluidStack.getCompoundTag("Tag").getInteger("purity"));
        assertTrue(fluidTask.hasKey("ignoreNBT") && fluidTask.hasKey("consume")
            && fluidTask.hasKey("groupDetect") && fluidTask.hasKey("autoConsume"));
    }

    private static NBTTagCompound task(NBTTagCompound document) {
        NBTTagCompound quest = NbtCompat.getCompoundAt(
            NbtCompat.getListOrEmpty(document, "questDatabase"), 0);
        return NbtCompat.getCompoundAt(NbtCompat.getListOrEmpty(quest, "tasks"), 0);
    }

    // -------------------------------------------------------------------- store load/save path

    @Test
    void aGoodFixtureLoadsThroughTheStoreWithoutQuarantine() throws IOException {
        byte[] document = MalformedJsonFixtures.bytes(DATABASE, "typical-quest-database.json");
        Files.write(dataDirectory.resolve("QuestDatabase.json"), document);

        JsonDocumentStore.LoadResult loaded = store().load("QuestDatabase.json", true);

        assertEquals(JsonDocumentStore.Outcome.LOADED, loaded.outcome(),
            () -> "a well-formed database must not be quarantined; warnings=" + warnings);
        assertTrue(loaded.quarantinePath().isEmpty(), "no quarantine copy for a good file");
        // The bundled databases are all present after a load.
        assertTrue(loaded.root().hasKey("questDatabase"), "questDatabase list missing after load");
        assertTrue(loaded.root().hasKey("questLines"), "questLines list missing after load");
        assertTrue(loaded.root().hasKey("questSettings"), "questSettings compound missing after load");
    }

    @Test
    void saveThenLoadPreservesEveryTag() throws IOException {
        NBTTagCompound root = codec.toNbt(parseFixture("typical-quest-database.json"), new NBTTagCompound(), true);
        JsonDocumentStore store = store();

        // The save path validates by re-parsing the temp file before it replaces the target
        // (readback), so this also exercises that hook on realistic data.
        store.save("QuestDatabase.json", root, true);
        JsonDocumentStore.LoadResult reloaded = store.load("QuestDatabase.json", true);

        assertEquals(JsonDocumentStore.Outcome.LOADED, reloaded.outcome());
        assertEquals(NbtCanonical.nbt(root), NbtCanonical.nbt(reloaded.root()),
            "a save/load cycle through the store must be lossless");
    }

    // ------------------------------------------------------------------------- oversized files

    @Test
    void aWideListParsesAndRoundTripsWithinBudget() throws IOException {
        String document = MalformedJsonFixtures.hugeIntArray(MalformedJsonFixtures.HUGE_ARRAY_ELEMENTS);

        long start = System.nanoTime();
        NBTTagCompound root = codec.toNbt(parse(document), new NBTTagCompound(), true);
        long parseToNbtMs = (System.nanoTime() - start) / 1_000_000L;

        int[] values = root.getIntArray("questIDs");
        assertEquals(MalformedJsonFixtures.HUGE_ARRAY_ELEMENTS, values.length,
            "every element of a wide int array must survive JSON -> NBT");
        assertEquals(0, values[0]);
        assertEquals(MalformedJsonFixtures.HUGE_ARRAY_ELEMENTS - 1, values[values.length - 1]);
        // Re-encode and re-read rather than diff a multi-megabyte string.
        NBTTagCompound restored = codec.toNbt(parse(streamed(root)), new NBTTagCompound(), true);
        assertEquals(values.length, restored.getIntArray("questIDs").length, "wide array drifted on round trip");

        record("wide int array elements=" + MalformedJsonFixtures.HUGE_ARRAY_ELEMENTS
            + " bytes=" + document.length() + " parse+toNbt(ms)=" + parseToNbtMs);
    }

    @Test
    void deeplyNestedDocumentIsRejectedNotSilentlyAccepted() throws IOException {
        // Recording, not assuming: this captures whichever failure Gson 2.2.2 raises on deep nesting.
        // parseObject only catches RuntimeException, so a StackOverflowError propagates raw and
        // bypasses quarantine entirely, which is a real gap the observation documents.
        Throwable thrown = assertThrows(Throwable.class,
            () -> parse(MalformedJsonFixtures.nestedObjects(MalformedJsonFixtures.OVERFLOWING_DEPTH)));
        record("deep overflow depth=" + MalformedJsonFixtures.OVERFLOWING_DEPTH
            + " threw=" + thrown.getClass().getName());
        assertTrue(thrown instanceof StackOverflowError || thrown instanceof MalformedJsonDocumentException,
            () -> "expected overflow or malformed rejection, got " + thrown.getClass().getName());

        // A depth well inside the ceiling parses and round-trips cleanly, so the failure above is a
        // depth limit rather than a blanket refusal of nested data.
        String accepted = MalformedJsonFixtures.nestedObjects(MalformedJsonFixtures.ACCEPTED_DEPTH);
        NBTTagCompound root = codec.toNbt(parse(accepted), new NBTTagCompound(), true);
        assertEquals(NbtCanonical.nbt(root),
            NbtCanonical.nbt(codec.toNbt(parse(streamed(root)), new NBTTagCompound(), true)),
            "accepted-depth document must round-trip");
        record("accepted depth=" + MalformedJsonFixtures.ACCEPTED_DEPTH + " parsed and round-tripped");
    }

    // -------------------------------------------------------------------------------- helpers

    private static JsonObject parse(String document) throws IOException {
        return JsonDocuments.parseObject(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonObject parseFixture(String fixture) throws IOException {
        return JsonDocuments.parseObject(new ByteArrayInputStream(MalformedJsonFixtures.bytes(DATABASE, fixture)));
    }

    private String streamed(NBTTagCompound root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonWriter json = new JsonWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8));
        codec.write(root, json, true);
        json.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private JsonDocumentStore store() {
        return new JsonDocumentStore(
            new com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage(dataDirectory),
            new NbtJsonCodec(warnings::add), warnings::add);
    }

    /** Appends one observation line to a build artifact; Gradle swallows stdout. Best-effort. */
    private static void record(String line) {
        try {
            Path dir = Path.of("build", "test-observations");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("database-oversize.log"), line + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // An observation log failure must not fail the test.
        }
    }
}
