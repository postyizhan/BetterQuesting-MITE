package com.github.postyizhan.betterquesting.storage.migration;

import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
import com.github.postyizhan.betterquesting.api.registry.IFactoryData;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.JsonSchemaFields;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.tasks.TaskRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistent, bounded history of unresolved migration boundaries. */
public final class MigrationReport {
    public static final String PATH = "MigrationReport.json";
    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    static final int MAX_STRUCTURE_DEPTH = 64;
    private static final int MAX_QUARANTINE_COLLISIONS = 100;
    private static final String ISSUES_KEY = "issues:9";
    private static final String MISSING_FACTORY = "unresolved_task_factory";
    private static final String CONSTRUCTION_FAILED = "task_factory_construction_failed";

    private final WorldStorage storage;
    private final String build;

    public MigrationReport(WorldStorage storage, String build) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.build = build;
    }

    /**
     * Validates an existing report before observing the current database. A blocked result means
     * the source remains canonical and an exact bounded evidence copy was made.
     */
    public Optional<Update> recordUnresolvedTaskFactories(QuestDatabase quests) throws IOException {
        List<Issue> observed = findUnresolvedTaskFactories(quests);
        Optional<byte[]> source = readSource();
        if (source.isEmpty()) {
            if (observed.isEmpty()) return Optional.empty();
            JsonObject root = new JsonObject();
            root.addProperty("format:8", JsonSchemaFields.UPSTREAM_FORMAT);
            root.addProperty("build:8", build == null ? "" : build);
            root.addProperty("mitePortFormat:8", JsonSchemaFields.MITE_PORT_FORMAT);
            root.add(ISSUES_KEY, new JsonObject());
            return writeObserved(root, observed);
        }

        JsonObject root;
        try {
            root = parseAndValidate(source.orElseThrow());
        } catch (ReportFormatException malformed) {
            return blocked(observed.size(), quarantine(source.orElseThrow()), malformed.reason());
        }

        if (observed.isEmpty()) return Optional.empty();
        return writeObserved(root, observed);
    }

    private Optional<Update> writeObserved(JsonObject root, List<Issue> observed) throws IOException {
        JsonObject issues = root.getAsJsonObject(ISSUES_KEY);
        Set<Issue> existing = new HashSet<>();
        int nextIndex = 0;
        try {
            for (var entry : issues.entrySet()) {
                int index = parseIssueIndex(entry.getKey());
                nextIndex = Math.max(nextIndex, index + 1);
                JsonObject issue = requireObject(entry.getValue(), "issues entry");
                Optional<Issue> parsed = parseKnownIssue(issue);
                if (parsed.isPresent()) existing.add(parsed.orElseThrow());
            }
        } catch (ReportFormatException malformed) {
            throw new IOException("MigrationReport.json changed shape during merge", malformed);
        }

        int added = 0;
        for (Issue issue : observed) {
            if (!existing.add(issue)) continue;
            issues.add(nextIndex++ + ":10", writeIssue(issue));
            added++;
        }
        if (added == 0) {
            return Optional.of(new Update(Status.UNCHANGED, observed.size(), 0,
                Optional.empty(), Optional.empty()));
        }

        byte[] encoded = encode(root);
        writeReport(encoded);
        return Optional.of(new Update(Status.RECORDED, observed.size(), added,
            Optional.empty(), Optional.empty()));
    }

    private Optional<byte[]> readSource() throws IOException {
        return storage.read(PATH, input -> input.readNBytes(MAX_DOCUMENT_BYTES + 1));
    }

    private void writeReport(byte[] bytes) throws IOException {
        storage.writeAtomically(PATH, output -> output.write(bytes), input -> {
            byte[] readback = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (readback.length > MAX_DOCUMENT_BYTES) {
                throw new IOException("MigrationReport.json exceeds " + MAX_DOCUMENT_BYTES + " bytes");
            }
            try {
                parseAndValidate(readback);
            } catch (ReportFormatException malformed) {
                throw new IOException("MigrationReport.json failed strict write readback", malformed);
            }
        });
    }

    private String quarantine(byte[] bytes) throws IOException {
        String base = JsonDocumentStore.quarantineNameFor(PATH);
        for (int suffix = 0; suffix < MAX_QUARANTINE_COLLISIONS; suffix++) {
            String candidate = suffix == 0 ? base : base + "." + suffix;
            Optional<byte[]> existing = storage.read(candidate,
                input -> input.readNBytes(bytes.length + 1));
            if (existing.isPresent()) {
                if (Arrays.equals(existing.orElseThrow(), bytes)) return candidate;
                continue;
            }
            storage.writeAtomically(candidate, output -> output.write(bytes));
            return candidate;
        }
        throw new IOException("Unable to allocate MigrationReport evidence copy");
    }

    private static Optional<Update> blocked(int observed, String quarantinePath, BlockReason reason) {
        return Optional.of(new Update(Status.BLOCKED, observed, 0,
            Optional.ofNullable(quarantinePath), Optional.of(reason)));
    }

    private static List<Issue> findUnresolvedTaskFactories(QuestDatabase quests) {
        List<Issue> issues = new ArrayList<>();
        synchronized (quests) {
            for (var questEntry : quests.entrySet()) {
                IQuest quest = questEntry.getValue();
                if (quest == null) continue;
                for (DBEntry<ITask> taskEntry : quest.getTasks().getEntries()) {
                    if (!(taskEntry.getValue() instanceof TaskPlaceholder placeholder)) continue;
                    String factory = placeholder.getTaskConfigData().getString("taskID");
                    if (factory == null || factory.isEmpty()) continue;
                    if ("betterquesting:placeholder".equals(factory)) continue;
                    IFactoryData<ITask, net.minecraft.NBTTagCompound> registered =
                        TaskRegistry.INSTANCE.getFactory(ResourceKey.parse(factory));
                    IssueKind kind = registered == null
                        ? IssueKind.MISSING_FACTORY : IssueKind.CONSTRUCTION_FAILED;
                    issues.add(new Issue(kind, questEntry.getKey(), taskEntry.getID(), factory));
                }
            }
        }
        issues.sort(Comparator.comparing((Issue issue) -> issue.quest().toString())
            .thenComparingInt(Issue::taskIndex).thenComparing(Issue::factory));
        return List.copyOf(issues);
    }

    private static JsonObject parseAndValidate(byte[] bytes) throws ReportFormatException {
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new ReportFormatException(BlockReason.OVERSIZED,
                "MigrationReport.json exceeds byte bound");
        }
        String document;
        try {
            document = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new ReportFormatException(BlockReason.MALFORMED,
                "MigrationReport.json contains invalid UTF-8", malformed);
        }
        JsonReader reader = new JsonReader(new StringReader(document));
        reader.setLenient(false);
        try {
            JsonElement parsed = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw malformed("trailing content after MigrationReport.json");
            }
            if (!parsed.isJsonObject()) throw malformed("report root must be an object");
            JsonObject root = parsed.getAsJsonObject();
            requireRootKey(root, "format:8", "format");
            requireRootKey(root, "build:8", "build");
            requireRootKey(root, "mitePortFormat:8", "mitePortFormat");
            requireRootKey(root, ISSUES_KEY, "issues");
            validateCompound(root);
            if (!JsonSchemaFields.UPSTREAM_FORMAT.equals(root.get("format:8").getAsString())) {
                throw new ReportFormatException(BlockReason.UNSUPPORTED_UPSTREAM_FORMAT,
                    "unsupported upstream report format");
            }
            if (!JsonSchemaFields.MITE_PORT_FORMAT.equals(root.get("mitePortFormat:8").getAsString())) {
                throw new ReportFormatException(BlockReason.UNSUPPORTED_PORT_FORMAT,
                    "unsupported port report format");
            }
            requireObject(root.get(ISSUES_KEY), "issues");
            validateIssues(root.getAsJsonObject(ISSUES_KEY));
            return root;
        } catch (ReportFormatException malformed) {
            throw malformed;
        } catch (IOException | RuntimeException malformed) {
            throw new ReportFormatException(BlockReason.MALFORMED,
                "malformed strict report", malformed);
        }
    }

    private static void validateIssues(JsonObject issues) throws ReportFormatException {
        Set<Issue> known = new HashSet<>();
        int expectedIndex = 0;
        for (var entry : issues.entrySet()) {
            if (!entry.getKey().endsWith(":10")) {
                throw malformed("issues entry is not a compound");
            }
            if (parseIssueIndex(entry.getKey()) != expectedIndex++) {
                throw malformed("issues entries are not a canonical list");
            }
            JsonObject issue = requireObject(entry.getValue(), "issue");
            validateCompound(issue);
            Optional<Issue> parsed = parseKnownIssue(issue);
            if (parsed.isPresent() && !known.add(parsed.orElseThrow())) {
                throw malformed("duplicate migration issue");
            }
        }
    }

    private static Optional<Issue> parseKnownIssue(JsonObject issue) throws ReportFormatException {
        if (!issue.has("kind:8")) return Optional.empty();
        rejectTypedConflict(issue, "kind", "kind:8");
        rejectTypedConflict(issue, "quest", "quest:8");
        rejectTypedConflict(issue, "taskIndex", "taskIndex:3");
        rejectTypedConflict(issue, "factory", "factory:8");
        if (!isString(issue.get("kind:8"))) throw malformed("invalid migration issue kind");
        String kindValue = issue.get("kind:8").getAsString();
        IssueKind kind;
        if (MISSING_FACTORY.equals(kindValue)) {
            kind = IssueKind.MISSING_FACTORY;
        } else if (CONSTRUCTION_FAILED.equals(kindValue)) {
            kind = IssueKind.CONSTRUCTION_FAILED;
        } else {
            throw new ReportFormatException(BlockReason.UNSUPPORTED_SCHEMA,
                "unsupported migration issue kind");
        }
        if (!isString(issue.get("quest:8")) || !isInteger(issue.get("taskIndex:3"))
            || !isString(issue.get("factory:8")) || issue.get("factory:8").getAsString().isEmpty()) {
            throw malformed("invalid migration issue");
        }
        String questValue = issue.get("quest:8").getAsString();
        UUID quest;
        try {
            quest = UUID.fromString(questValue);
        } catch (IllegalArgumentException invalid) {
            throw malformed("invalid migration quest UUID", invalid);
        }
        if (!quest.toString().equals(questValue)) throw malformed("non-canonical migration quest UUID");
        int taskIndex = issue.get("taskIndex:3").getAsInt();
        if (taskIndex < 0) throw malformed("negative migration task index");
        return Optional.of(new Issue(kind, quest, taskIndex,
            issue.get("factory:8").getAsString()));
    }

    private static void rejectTypedConflict(JsonObject object, String base, String expected)
        throws ReportFormatException {
        for (var entry : object.entrySet()) {
            if ((entry.getKey().equals(base) || entry.getKey().startsWith(base + ":"))
                && !expected.equals(entry.getKey())) {
                throw malformed("conflicting type for migration issue field " + base);
            }
        }
    }

    private static boolean isInteger(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return false;
        String value = element.getAsString();
        if (value.isEmpty()) return false;
        try {
            int parsed = Integer.parseInt(value);
            return Integer.toString(parsed).equals(value);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static JsonObject requireObject(JsonElement element, String description)
        throws ReportFormatException {
        if (element == null || !element.isJsonObject()) throw malformed(description + " must be an object");
        return element.getAsJsonObject();
    }

    private static void requireRootKey(JsonObject root, String key, String base)
        throws ReportFormatException {
        if (!root.has(key)) {
            for (var entry : root.entrySet()) {
                if (entry.getKey().equals(base) || entry.getKey().startsWith(base + ":")) {
                    throw new ReportFormatException(BlockReason.UNSUPPORTED_SCHEMA,
                        "wrong type for " + base);
                }
            }
            throw new ReportFormatException(BlockReason.UNSUPPORTED_SCHEMA, "missing " + key);
        }
    }

    private static TypedKey parseTypedKey(String key) throws ReportFormatException {
        int colon = key.lastIndexOf(':');
        if (colon <= 0 || colon == key.length() - 1) throw malformed("untagged report field");
        String suffix = key.substring(colon + 1);
        try {
            int type = Integer.parseInt(suffix);
            if (type < 1 || type > 11) throw new NumberFormatException();
            if (!Integer.toString(type).equals(suffix)) throw new NumberFormatException();
            return new TypedKey(key.substring(0, colon), type);
        } catch (NumberFormatException invalid) {
            throw malformed("invalid report field type", invalid);
        }
    }

    private static void validateCompound(JsonObject object) throws ReportFormatException {
        Set<String> bases = new HashSet<>();
        for (var entry : object.entrySet()) {
            TypedKey key = parseTypedKey(entry.getKey());
            if (!bases.add(key.base())) throw malformed("conflicting typed report field: " + key.base());
            validateTypedValue(entry.getValue(), key.type());
        }
    }

    private static void validateTypedValue(JsonElement value, int type) throws ReportFormatException {
        if (type == 1 && isBoolean(value)) return;
        if (type >= 1 && type <= 4) {
            if (!isIntegerForType(value, type)) throw malformed("invalid integer report value");
            return;
        }
        if (type == 5 || type == 6) {
            if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
                throw malformed("invalid floating report value");
            }
            return;
        }
        if (type == 7 || type == 11) {
            if (value == null || !value.isJsonArray()) throw malformed("invalid array report value");
            for (JsonElement element : value.getAsJsonArray()) {
                if (!isIntegerForType(element, type == 7 ? 1 : 3)) {
                    throw malformed("invalid array report element");
                }
            }
            return;
        }
        if (type == 8) {
            if (!isString(value)) throw malformed("invalid string report value");
            return;
        }
        JsonObject nested = requireObject(value, type == 9 ? "list" : "compound");
        if (type == 9) validateList(nested); else validateCompound(nested);
    }

    private static void validateList(JsonObject list) throws ReportFormatException {
        int expectedIndex = 0;
        Integer elementType = null;
        for (var entry : list.entrySet()) {
            TypedKey key = parseTypedKey(entry.getKey());
            int index = parseNonNegativeIndex(key.base());
            if (index != expectedIndex++) throw malformed("non-canonical report list index");
            if (elementType == null) elementType = key.type();
            else if (elementType != key.type()) throw malformed("mixed report list types");
            validateTypedValue(entry.getValue(), key.type());
        }
    }

    private static boolean isIntegerForType(JsonElement element, int type) {
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) return false;
        String value = element.getAsString();
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) return false;
            return switch (type) {
                case 1 -> parsed >= Byte.MIN_VALUE && parsed <= Byte.MAX_VALUE;
                case 2 -> parsed >= Short.MIN_VALUE && parsed <= Short.MAX_VALUE;
                case 3 -> parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE;
                default -> true;
            };
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive()
            && element.getAsJsonPrimitive().isBoolean();
    }

    private static int parseIssueIndex(String key) throws ReportFormatException {
        TypedKey typed = parseTypedKey(key);
        if (typed.type() != 10) throw malformed("invalid issue key");
        return parseNonNegativeIndex(typed.base());
    }

    private static int parseNonNegativeIndex(String value) throws ReportFormatException {
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            throw malformed("invalid report list index");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || !Integer.toString(parsed).equals(value)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw malformed("invalid report list index", invalid);
        }
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static JsonObject writeIssue(Issue issue) {
        JsonObject serialized = new JsonObject();
        serialized.addProperty("kind:8", issue.kind().serialized());
        serialized.addProperty("quest:8", issue.quest().toString());
        serialized.addProperty("taskIndex:3", issue.taskIndex());
        serialized.addProperty("factory:8", issue.factory());
        return serialized;
    }

    private static byte[] encode(JsonObject root) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        JsonWriter writer = JsonDocuments.writer(bytes);
        new Gson().toJson(root, writer);
        writer.flush();
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_DOCUMENT_BYTES) throw new IOException("MigrationReport.json exceeds byte bound");
        return encoded;
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException, ReportFormatException {
        if (depth >= MAX_STRUCTURE_DEPTH) {
            throw new ReportFormatException(BlockReason.TOO_DEEP, "report structure is too deep");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readObject(reader, depth + 1);
            case BEGIN_ARRAY -> readArray(reader, depth + 1);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new RawNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw malformed("unexpected report token " + reader.peek());
        };
    }

    private static JsonObject readObject(JsonReader reader, int depth) throws IOException, ReportFormatException {
        JsonObject object = new JsonObject();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) throw malformed("duplicate report field: " + name);
            object.add(name, read(reader, depth));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readArray(JsonReader reader, int depth) throws IOException, ReportFormatException {
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) array.add(read(reader, depth));
        reader.endArray();
        return array;
    }

    private enum IssueKind {
        MISSING_FACTORY(MigrationReport.MISSING_FACTORY),
        CONSTRUCTION_FAILED(MigrationReport.CONSTRUCTION_FAILED);

        private final String serialized;
        IssueKind(String serialized) { this.serialized = serialized; }
        private String serialized() { return serialized; }
    }

    private record TypedKey(String base, int type) {
    }

    private static final class RawNumber extends Number {
        private final String value;
        private RawNumber(String value) { this.value = value; }
        @Override public int intValue() { return new BigDecimal(value).intValue(); }
        @Override public long longValue() { return new BigDecimal(value).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(value); }
        @Override public double doubleValue() { return Double.parseDouble(value); }
        @Override public String toString() { return value; }
    }

    private record Issue(IssueKind kind, UUID quest, int taskIndex, String factory) {
        private Issue {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(quest, "quest");
            Objects.requireNonNull(factory, "factory");
            if (taskIndex < 0) throw new IllegalArgumentException("taskIndex");
        }
    }

    private static final class ReportFormatException extends Exception {
        private final BlockReason reason;
        private ReportFormatException(BlockReason reason, String message) {
            super(message);
            this.reason = reason;
        }
        private ReportFormatException(BlockReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
        private BlockReason reason() { return reason; }
    }

    private static ReportFormatException malformed(String message) {
        return new ReportFormatException(BlockReason.MALFORMED, message);
    }

    private static ReportFormatException malformed(String message, Throwable cause) {
        return new ReportFormatException(BlockReason.MALFORMED, message, cause);
    }

    public enum Status { RECORDED, UNCHANGED, BLOCKED }

    public enum BlockReason {
        MALFORMED,
        OVERSIZED,
        TOO_DEEP,
        UNSUPPORTED_UPSTREAM_FORMAT,
        UNSUPPORTED_PORT_FORMAT,
        UNSUPPORTED_SCHEMA
    }

    public record Update(Status status, int observed, int added, Optional<String> quarantinePath,
                         Optional<BlockReason> blockReason) {
        public Update {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(quarantinePath, "quarantinePath");
            Objects.requireNonNull(blockReason, "blockReason");
            if ((status == Status.BLOCKED) != blockReason.isPresent()) {
                throw new IllegalArgumentException("blockReason must identify only blocked reports");
            }
        }
    }
}
