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
import com.google.gson.JsonSyntaxException;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private static final String KIND = "unresolved_task_factory";

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
            return blocked(observed.size(), quarantine(source.orElseThrow()));
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
        if (added == 0) return Optional.of(new Update(Status.UNCHANGED, observed.size(), 0, Optional.empty()));

        byte[] encoded = encode(root);
        writeReport(encoded);
        return Optional.of(new Update(Status.RECORDED, observed.size(), added, Optional.empty()));
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
            Optional<byte[]> existing = storage.read(candidate, InputStream::readAllBytes);
            if (existing.isPresent()) {
                if (java.util.Arrays.equals(existing.orElseThrow(), bytes)) return candidate;
                continue;
            }
            storage.writeAtomically(candidate, output -> output.write(bytes));
            return candidate;
        }
        throw new IOException("Unable to allocate MigrationReport evidence copy");
    }

    private static Optional<Update> blocked(int observed, String quarantinePath) {
        return Optional.of(new Update(Status.BLOCKED, observed, 0,
            Optional.ofNullable(quarantinePath)));
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
                    if (registered != null) continue;
                    issues.add(new Issue(questEntry.getKey(), taskEntry.getID(), factory));
                }
            }
        }
        issues.sort(Comparator.comparing((Issue issue) -> issue.quest().toString())
            .thenComparingInt(Issue::taskIndex).thenComparing(Issue::factory));
        return List.copyOf(issues);
    }

    private static JsonObject parseAndValidate(byte[] bytes) throws ReportFormatException {
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new ReportFormatException("MigrationReport.json exceeds byte bound");
        }
        String document;
        try {
            document = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new ReportFormatException("MigrationReport.json contains invalid UTF-8", malformed);
        }
        JsonReader reader = new JsonReader(new StringReader(document));
        reader.setLenient(false);
        try {
            JsonElement parsed = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ReportFormatException("trailing content after MigrationReport.json");
            }
            if (!parsed.isJsonObject()) throw new ReportFormatException("report root must be an object");
            JsonObject root = parsed.getAsJsonObject();
            rejectTypeConflicts(root, "format", "format:8");
            rejectTypeConflicts(root, "build", "build:8");
            rejectTypeConflicts(root, "mitePortFormat", "mitePortFormat:8");
            rejectTypeConflicts(root, "issues", ISSUES_KEY);
            requireTypedKey(root, "format:8", "format");
            requireTypedKey(root, "build:8", "build");
            requireTypedKey(root, "mitePortFormat:8", "mitePortFormat");
            requireString(root, "mitePortFormat:8", JsonSchemaFields.MITE_PORT_FORMAT);
            requireTypedValue(root, "format:8", 8);
            if (root.has("format:8") && !JsonSchemaFields.UPSTREAM_FORMAT.equals(root.get("format:8").getAsString())) {
                throw new ReportFormatException("unsupported upstream report format");
            }
            requireTypedValue(root, "build:8", 8);
            requireTypedValue(root, ISSUES_KEY, 9);
            requireObject(root.get(ISSUES_KEY), "issues");
            validateIssues(root.getAsJsonObject(ISSUES_KEY));
            for (var entry : root.entrySet()) validateTypedKey(entry.getKey());
            return root;
        } catch (ReportFormatException malformed) {
            throw malformed;
        } catch (IOException | RuntimeException malformed) {
            throw new ReportFormatException("malformed strict report", malformed);
        }
    }

    private static void validateIssues(JsonObject issues) throws ReportFormatException {
        for (var entry : issues.entrySet()) {
            if (!entry.getKey().endsWith(":10")) {
                throw new ReportFormatException("issues entry is not a compound");
            }
            parseIssueIndex(entry.getKey());
            JsonObject issue = requireObject(entry.getValue(), "issue");
            for (var field : issue.entrySet()) validateTypedKey(field.getKey());
            Optional<Issue> parsed = parseKnownIssue(issue);
            if (issue.has("kind:8") && parsed.isEmpty()) {
                throw new ReportFormatException("invalid unresolved task issue");
            }
        }
    }

    private static Optional<Issue> parseKnownIssue(JsonObject issue) {
        if (!issue.has("kind:8")) return Optional.empty();
        if (!isString(issue.get("kind:8")) || !KIND.equals(issue.get("kind:8").getAsString())) {
            return Optional.empty();
        }
        if (!isString(issue.get("quest:8")) || !isInteger(issue.get("taskIndex:3"))
            || !isString(issue.get("factory:8")) || issue.get("factory:8").getAsString().isEmpty()) {
            return Optional.empty();
        }
        String questValue = issue.get("quest:8").getAsString();
        UUID quest;
        try {
            quest = UUID.fromString(questValue);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        if (!quest.toString().equals(questValue)) return Optional.empty();
        return Optional.of(new Issue(quest, issue.get("taskIndex:3").getAsInt(),
            issue.get("factory:8").getAsString()));
    }

    private static boolean isInteger(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return false;
        String value = element.getAsString();
        if (value.isEmpty() || value.charAt(0) == '-') return false;
        if (value.length() > 1 && value.charAt(0) == '0') return false;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && Integer.toString(parsed).equals(value);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static JsonObject requireObject(JsonElement element, String description)
        throws ReportFormatException {
        if (element == null || !element.isJsonObject()) throw new ReportFormatException(description + " must be an object");
        return element.getAsJsonObject();
    }

    private static void requireString(JsonObject root, String key, String expected)
        throws ReportFormatException {
        if (!isString(root.get(key)) || !expected.equals(root.get(key).getAsString())) {
            throw new ReportFormatException("unsupported " + key);
        }
    }

    private static void requireTypedValue(JsonObject root, String key, int type)
        throws ReportFormatException {
        if (root.has(key)) {
            if (type == 8 && !isString(root.get(key))) throw new ReportFormatException("wrong type for " + key);
            if (type == 9 && !root.get(key).isJsonObject()) throw new ReportFormatException("wrong type for " + key);
        }
    }

    private static void requireTypedKey(JsonObject root, String key, String base)
        throws ReportFormatException {
        if (root.has(base) || root.has(base + ":3") || root.has(base + ":9") || root.has(base + ":10")) {
            throw new ReportFormatException("wrong type for " + base);
        }
        if (!root.has(key)) throw new ReportFormatException("missing " + key);
    }

    private static void rejectTypeConflicts(JsonObject root, String base, String expected)
        throws ReportFormatException {
        for (var entry : root.entrySet()) {
            if (entry.getKey().startsWith(base + ":") && !expected.equals(entry.getKey())) {
                throw new ReportFormatException("wrong type for " + base);
            }
            if (entry.getKey().equals(base)) {
                throw new ReportFormatException("wrong type for " + base);
            }
        }
    }

    private static void validateTypedKey(String key) throws ReportFormatException {
        int colon = key.lastIndexOf(':');
        if (colon <= 0 || colon == key.length() - 1) throw new ReportFormatException("untagged report field");
        try {
            int type = Integer.parseInt(key.substring(colon + 1));
            if (type < 1 || type > 11) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new ReportFormatException("invalid report field type");
        }
    }

    private static int parseIssueIndex(String key) throws ReportFormatException {
        if (!key.endsWith(":10")) throw new ReportFormatException("invalid issue key");
        String value = key.substring(0, key.length() - 3);
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            throw new ReportFormatException("invalid issue index");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException("negative issue index");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new ReportFormatException("invalid issue index", invalid);
        }
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static JsonObject writeIssue(Issue issue) {
        JsonObject serialized = new JsonObject();
        serialized.addProperty("kind:8", KIND);
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
        if (depth >= MAX_STRUCTURE_DEPTH) throw new ReportFormatException("report structure is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readObject(reader, depth + 1);
            case BEGIN_ARRAY -> readArray(reader, depth + 1);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new ReportFormatException("unexpected report token " + reader.peek());
        };
    }

    private static JsonObject readObject(JsonReader reader, int depth) throws IOException, ReportFormatException {
        JsonObject object = new JsonObject();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) throw new ReportFormatException("duplicate report field: " + name);
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

    private record Issue(UUID quest, int taskIndex, String factory) {
        private Issue {
            Objects.requireNonNull(quest, "quest");
            Objects.requireNonNull(factory, "factory");
            if (taskIndex < 0) throw new IllegalArgumentException("taskIndex");
        }
    }

    private static final class ReportFormatException extends Exception {
        private ReportFormatException(String message) { super(message); }
        private ReportFormatException(String message, Throwable cause) { super(message, cause); }
    }

    public enum Status { RECORDED, UNCHANGED, BLOCKED }

    public record Update(Status status, int observed, int added, Optional<String> quarantinePath) {
        public Update {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(quarantinePath, "quarantinePath");
        }
    }
}
