package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

/** Strict, completion-only copy migration for an upstream world-root QuestProgress.json. */
final class LegacyQuestProgressImporter {
    static final String BACKUP_PATH = "QuestProgress.json.legacy-migration.bak";
    static final String PREPARED_MARKER_PATH = "QuestProgress.legacy-migration.prepared";
    static final String COMPLETE_MARKER_PATH = "QuestProgress.legacy-migration.complete";
    static final int MAX_STRUCTURE_DEPTH = 128;

    private static final int MAX_PLAYER_OUTPUTS = 4096;
    private static final long MAX_GENERATED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_MARKER_BYTES = 1024 * 1024;
    private static final String MARKER_HEADER = "BQ_MITE_LEGACY_QUEST_PROGRESS_MIGRATION_V1";
    private static final BigInteger BYTE_MIN = BigInteger.valueOf(Byte.MIN_VALUE);
    private static final BigInteger BYTE_MAX = BigInteger.valueOf(Byte.MAX_VALUE);
    private static final BigInteger SHORT_MIN = BigInteger.valueOf(Short.MIN_VALUE);
    private static final BigInteger SHORT_MAX = BigInteger.valueOf(Short.MAX_VALUE);
    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final Path configuredRoot;
    private final QuestDatabase quests;
    private final NbtJsonCodec codec;
    private final MigrationIo io;

    LegacyQuestProgressImporter(Path configuredRoot, QuestDatabase quests, NbtJsonCodec codec,
        MigrationIo io) {
        this.configuredRoot = Objects.requireNonNull(configuredRoot, "configuredRoot")
            .toAbsolutePath().normalize();
        this.quests = Objects.requireNonNull(quests, "quests");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.io = Objects.requireNonNull(io, "io");
    }

    Result run(boolean migrate) throws IOException {
        Optional<RootSnapshot> optionalRoot = canonicalRoot();
        if (optionalRoot.isEmpty()) return Result.absent();
        RootSnapshot root = optionalRoot.orElseThrow();
        Layout layout = inspectLayout(root);
        if (!layout.sourceExists()) {
            if (layout.hasMigrationState()) {
                return Result.blocked(List.of(),
                    "migration marker exists but the preserved QuestProgress.json source is absent");
            }
            return Result.absent();
        }

        CapturedFile source = captureRegular(root, root.path().resolve(QuestProgressPersistence.LEGACY_PATH),
            QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1, "legacy source");
        if (source.bytes().length > QuestProgressPersistence.MAX_DOCUMENT_BYTES) {
            if (layout.hasMigrationState()) {
                return Result.blocked(List.of(),
                    "migration state exists but the preserved source exceeds the safety limit");
            }
            return Result.oversized("document exceeds "
                + QuestProgressPersistence.MAX_DOCUMENT_BYTES + " bytes");
        }

        final JsonObject document;
        try {
            document = StrictJson.parseObject(source.bytes(), MAX_STRUCTURE_DEPTH);
        } catch (ContentException malformed) {
            if (layout.hasMigrationState()) {
                return Result.blocked(List.of(),
                    "migration state exists but the preserved source is no longer valid: "
                        + malformed.getMessage());
            }
            return Result.quarantined(malformed.getMessage());
        }

        Validation validation = validateLegacy(document);
        if (validation.status() == ValidationStatus.BLOCKED) {
            return Result.blocked(validation.users(), validation.issue());
        }
        if (validation.status() == ValidationStatus.INVALID) {
            return Result.quarantined(validation.users(), validation.issue());
        }

        Plan plan;
        try {
            plan = buildPlan(root.path(), source.bytes(), document, validation);
        } catch (ContentException unsafe) {
            return Result.blocked(validation.users(), unsafe.getMessage());
        }

        State state = validateState(root, layout, plan);
        if (state.status() == StateStatus.BLOCKED) {
            return Result.blocked(validation.users(), state.issue());
        }
        if (state.status() == StateStatus.COMPLETE) {
            return Result.migrated(validation.users(), plan.backupPath(),
                "validated complete marker and every retained source, backup, and output digest");
        }
        if (!migrate) {
            String issue = state.status() == StateStatus.PREPARED
                ? "incomplete prepared migration requires startup recovery"
                : "completion-only legacy progress is eligible for explicit copy migration";
            return Result.blocked(validation.users(), issue);
        }

        execute(root, plan, state.status() == StateStatus.PREPARED);
        Layout completedLayout = inspectLayout(root);
        State completed = validateState(root, completedLayout, plan);
        if (completed.status() != StateStatus.COMPLETE) {
            throw new IOException("Legacy progress migration did not reach a validated complete state: "
                + completed.issue());
        }
        return Result.migrated(validation.users(), plan.backupPath(),
            "source retained; exact backup and split outputs are protected by durable digest markers");
    }

    /** Validates the old complete manifest while allowing only known lifecycle-owned changes. */
    void requireRefreshableState(Map<UUID, ExpectedOutput> dirtyOutputs) throws IOException {
        refreshState(dirtyOutputs);
    }

    /** Refreshes the complete manifest after this process durably changes live player files. */
    void refreshCompleteMarker(Map<UUID, ExpectedOutput> dirtyOutputs) throws IOException {
        RefreshState state = refreshState(dirtyOutputs);
        Path outputDirectory = state.root().path().resolve(QuestProgressPersistence.DIRECTORY);
        if (state.layout().outputDirectoryExists()) io.syncDirectory(outputDirectory);
        final byte[] refreshed;
        try {
            refreshed = marker("complete", state.sourceBytes().length,
                sha256(state.sourceBytes()), state.currentOutputs());
        } catch (ContentException unsafe) {
            throw new IOException("Cannot describe current player outputs safely", unsafe);
        }
        replaceCompleteMarker(state.root(), refreshed);
    }

    ExpectedOutput expectedOutput(NBTTagCompound root) throws IOException {
        try {
            return ExpectedOutput.present(encode(root));
        } catch (ContentException oversized) {
            throw new IOException(oversized.getMessage(), oversized);
        }
    }

    static ExpectedOutput deletedOutput() {
        return ExpectedOutput.deleted();
    }

    private RefreshState refreshState(Map<UUID, ExpectedOutput> dirtyOutputs) throws IOException {
        Objects.requireNonNull(dirtyOutputs, "dirtyOutputs");
        RootSnapshot root = canonicalRoot().orElseThrow(
            () -> new IOException("BetterQuesting data root disappeared during marker refresh"));
        Layout layout = inspectLayout(root);
        if (!layout.sourceExists() || !layout.preparedExists() || !layout.completeExists()) {
            throw new IOException("Cannot refresh an incomplete legacy progress migration");
        }
        CapturedFile source = captureRegular(root,
            root.path().resolve(QuestProgressPersistence.LEGACY_PATH),
            QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1, "legacy source");
        if (source.bytes().length > QuestProgressPersistence.MAX_DOCUMENT_BYTES) {
            throw new IOException("Preserved legacy source exceeds the safety limit during refresh");
        }
        final JsonObject document;
        try {
            document = StrictJson.parseObject(source.bytes(), MAX_STRUCTURE_DEPTH);
        } catch (ContentException malformed) {
            throw new IOException("Preserved legacy source is invalid during refresh", malformed);
        }
        Validation validation = validateLegacy(document);
        if (validation.status() != ValidationStatus.ACCEPTED) {
            throw new IOException("Preserved legacy source is no longer migration-safe: "
                + validation.issue());
        }
        final Plan initial;
        try {
            initial = buildPlan(root.path(), source.bytes(), document, validation);
        } catch (ContentException unsafe) {
            throw new IOException("Cannot regenerate the prepared migration plan", unsafe);
        }
        if (!matches(root, root.path().resolve(PREPARED_MARKER_PATH), initial.preparedMarker(),
            MAX_MARKER_BYTES, "prepared marker")) {
            throw new IOException("Prepared migration marker changed before complete-marker refresh");
        }
        if (!matches(root, initial.backupPath(), initial.sourceBytes(),
            QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1, "legacy backup")) {
            throw new IOException("Exact legacy backup changed before complete-marker refresh");
        }

        final List<ManifestOutput> oldOutputs;
        final List<ManifestOutput> currentOutputs;
        try {
            oldOutputs = readCompleteMarker(root, source.bytes());
            currentOutputs = currentOutputs(root, layout);
        } catch (ContentException unsafe) {
            throw new IOException("Cannot validate current player outputs safely", unsafe);
        }
        validateRefreshOutputs(oldOutputs, currentOutputs, dirtyOutputs);
        return new RefreshState(root, layout, source.bytes(), currentOutputs);
    }

    void requireCompleteState() throws IOException {
        Result result = run(false);
        if (result.status() != Status.MIGRATED) {
            throw new IOException("Legacy migration state is not complete: " + result.issues());
        }
    }

    private Optional<RootSnapshot> canonicalRoot() throws IOException {
        final BasicFileAttributes supplied;
        try {
            supplied = attributes(configuredRoot);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        requireDirectory(configuredRoot, supplied, "BetterQuesting data root");
        Path canonical = configuredRoot.toRealPath();
        BasicFileAttributes canonicalAttributes = attributes(canonical);
        requireDirectory(canonical, canonicalAttributes, "canonical BetterQuesting data root");
        return Optional.of(new RootSnapshot(canonical, canonicalAttributes.fileKey()));
    }

    private Layout inspectLayout(RootSnapshot root) throws IOException {
        root.revalidate();
        Path source = directChild(root.path(), QuestProgressPersistence.LEGACY_PATH);
        Path backup = directChild(root.path(), BACKUP_PATH);
        Path prepared = directChild(root.path(), PREPARED_MARKER_PATH);
        Path complete = directChild(root.path(), COMPLETE_MARKER_PATH);
        Path preparedTemp = directChild(root.path(), PREPARED_MARKER_PATH + ".tmp");
        Path completeTemp = directChild(root.path(), COMPLETE_MARKER_PATH + ".tmp");
        boolean sourceExists = validateOptionalRegular(source, "legacy source");
        boolean backupExists = validateOptionalRegular(backup, "legacy backup");
        boolean preparedExists = validateOptionalRegular(prepared, "prepared migration marker");
        boolean completeExists = validateOptionalRegular(complete, "complete migration marker");
        boolean preparedTempExists = validateOptionalRegular(preparedTemp, "prepared marker temporary");
        boolean completeTempExists = validateOptionalRegular(completeTemp, "complete marker temporary");

        Path outputDirectory = directChild(root.path(), QuestProgressPersistence.DIRECTORY);
        List<Path> outputEntries = new ArrayList<>();
        boolean outputDirectoryExists = Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS);
        if (outputDirectoryExists) {
            BasicFileAttributes directoryAttributes = attributes(outputDirectory);
            requireDirectory(outputDirectory, directoryAttributes, "QuestProgress output directory");
            Path canonicalOutput = outputDirectory.toRealPath();
            if (!canonicalOutput.getParent().equals(root.path())) {
                throw new IOException("QuestProgress output directory escapes canonical data root: "
                    + canonicalOutput);
            }
            try (var entries = Files.list(outputDirectory)) {
                for (Path entry : entries.sorted().toList()) {
                    BasicFileAttributes entryAttributes = attributes(entry);
                    requireRegular(entry, entryAttributes, "QuestProgress output entry");
                    if (!entry.toRealPath().getParent().equals(canonicalOutput)) {
                        throw new IOException("QuestProgress output escapes canonical directory: " + entry);
                    }
                    outputEntries.add(entry);
                }
            }
        }
        root.revalidate();
        return new Layout(sourceExists, backupExists, preparedExists, completeExists,
            preparedTempExists, completeTempExists, outputDirectoryExists,
            List.copyOf(outputEntries));
    }

    private State validateState(RootSnapshot root, Layout layout, Plan plan) throws IOException {
        if (layout.completeExists() && !layout.preparedExists()) {
            return State.blocked("complete marker exists without its prepared marker");
        }
        if (!layout.preparedExists()) {
            if (layout.backupExists()) return State.blocked("legacy backup path already exists");
            if (layout.completeExists()) return State.blocked("complete marker path already exists");
            if (layout.preparedTempExists() || layout.completeTempExists()) {
                return State.blocked("migration marker temporary path already exists");
            }
            if (!layout.outputEntries().isEmpty()) {
                return State.blocked("QuestProgress already contains split progress files");
            }
            return State.none();
        }

        if (!matches(root, root.path().resolve(PREPARED_MARKER_PATH), plan.preparedMarker(),
            MAX_MARKER_BYTES, "prepared marker")) {
            return State.blocked("prepared marker does not match the preserved source and planned outputs");
        }
        if (layout.completeExists()) {
            if (!layout.backupExists()) {
                return State.blocked("complete marker exists but the exact source backup is absent");
            }
            if (!matches(root, plan.backupPath(), plan.sourceBytes(),
                QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1, "legacy backup")) {
                return State.blocked("complete marker exists but the legacy backup digest does not match");
            }
            final byte[] expectedComplete;
            try {
                expectedComplete = currentCompleteMarker(root, layout, plan.sourceBytes());
            } catch (ContentException unsafe) {
                return State.blocked(unsafe.getMessage());
            }
            if (!matches(root, root.path().resolve(COMPLETE_MARKER_PATH), expectedComplete,
                MAX_MARKER_BYTES, "complete marker")) {
                return State.blocked("complete marker does not match every current player output digest");
            }
            return State.complete();
        }
        if (layout.completeTempExists()) {
            // A valid prepared marker makes this fixed temporary name ours. Recovery removes it
            // before republishing the immutable complete marker.
            io.deleteIfExists(root.path().resolve(COMPLETE_MARKER_PATH + ".tmp"));
            io.syncDirectory(root.path());
        }
        if (layout.preparedTempExists()) {
            io.deleteIfExists(root.path().resolve(PREPARED_MARKER_PATH + ".tmp"));
            io.syncDirectory(root.path());
        }
        return State.prepared();
    }

    private void execute(RootSnapshot root, Plan plan, boolean recovering) throws IOException {
        List<Path> created = new ArrayList<>();
        Path prepared = root.path().resolve(PREPARED_MARKER_PATH);
        boolean preparedCreated = false;
        boolean outputDirectoryCreated = false;
        try {
            if (!recovering) {
                publishMarker(root, prepared, plan.preparedMarker(), created);
                preparedCreated = true;
                io.checkpoint(Checkpoint.PREPARED, prepared);
            }

            ensureArtifact(root, plan.backupPath(), plan.sourceBytes(), recovering, created);
            io.checkpoint(Checkpoint.BACKUP, plan.backupPath());

            Path outputDirectory = root.path().resolve(QuestProgressPersistence.DIRECTORY);
            if (!Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(outputDirectory);
                outputDirectoryCreated = true;
                io.syncDirectory(root.path());
            } else {
                requireDirectory(outputDirectory, attributes(outputDirectory),
                    "QuestProgress output directory");
            }
            for (PlayerOutput output : plan.outputs()) {
                ensureArtifact(root, output.path(), output.bytes(), recovering, created);
                io.checkpoint(Checkpoint.OUTPUT, output.path());
            }

            Path complete = root.path().resolve(COMPLETE_MARKER_PATH);
            publishMarker(root, complete, plan.completeMarker(), created);
            io.checkpoint(Checkpoint.COMPLETE, complete);
        } catch (IOException | RuntimeException failure) {
            rollback(root, created, prepared, preparedCreated, outputDirectoryCreated, failure);
            throw failure;
        }
    }

    private void ensureArtifact(RootSnapshot root, Path path, byte[] bytes, boolean recovering,
        List<Path> created) throws IOException {
        root.revalidate();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes existing = attributes(path);
            requireRegular(path, existing, "migration artifact");
            if (matches(root, path, bytes, bytes.length + 1L, "migration artifact")) return;
            if (!recovering) throw new FileAlreadyExistsException(path.toString());
            io.deleteIfExists(path);
            io.syncDirectory(path.getParent());
        }
        writeNewFile(path, bytes, created);
        io.syncDirectory(path.getParent());
        root.revalidate();
    }

    private void publishMarker(RootSnapshot root, Path marker, byte[] bytes, List<Path> created)
        throws IOException {
        root.revalidate();
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(marker.toString());
        }
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(temporary.toString());
        }
        writeNewFile(temporary, bytes, created);
        io.publishMarker(temporary, marker);
        created.remove(temporary);
        created.add(marker);
        io.syncDirectory(marker.getParent());
        root.revalidate();
    }

    private void replaceCompleteMarker(RootSnapshot root, byte[] bytes) throws IOException {
        Path marker = root.path().resolve(COMPLETE_MARKER_PATH);
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(temporary.toString());
        }
        boolean published = false;
        try {
            try (FileChannel channel = io.createNew(temporary)) {
                ByteBuffer remaining = ByteBuffer.wrap(bytes);
                while (remaining.hasRemaining()) io.write(channel, remaining);
                io.syncFile(temporary, channel);
            }
            io.replaceMarker(temporary, marker);
            published = true;
            io.syncDirectory(root.path());
            root.revalidate();
        } finally {
            if (!published) io.deleteIfExists(temporary);
        }
    }

    private void writeNewFile(Path path, byte[] bytes, List<Path> created) throws IOException {
        try (FileChannel channel = io.createNew(path)) {
            created.add(path);
            ByteBuffer remaining = ByteBuffer.wrap(bytes);
            while (remaining.hasRemaining()) io.write(channel, remaining);
            io.syncFile(path, channel);
        }
    }

    private void rollback(RootSnapshot root, List<Path> created, Path prepared,
        boolean preparedCreated, boolean outputDirectoryCreated, Throwable failure) {
        boolean clean = true;
        for (int index = created.size() - 1; index >= 0; index--) {
            Path path = created.get(index);
            if (path.equals(prepared)) continue;
            if (!cleanup(path, failure)) {
                clean = false;
                break;
            }
        }
        Path outputDirectory = root.path().resolve(QuestProgressPersistence.DIRECTORY);
        if (outputDirectoryCreated && clean) clean &= cleanup(outputDirectory, failure);
        if (preparedCreated && clean) cleanup(prepared, failure);
    }

    private boolean cleanup(Path path, Throwable failure) {
        try {
            io.deleteIfExists(path);
            io.syncDirectory(path.getParent());
            return true;
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            return false;
        }
    }

    private Plan buildPlan(Path root, byte[] sourceBytes, JsonObject document,
        Validation validation) throws IOException, ContentException {
        Map<UUID, JsonObject> playerLists = new LinkedHashMap<>();
        JsonObject questsList = document.getAsJsonObject("questProgress:9");
        int questIndex = 0;
        for (var questEntry : questsList.entrySet()) {
            JsonObject quest = questEntry.getValue().getAsJsonObject();
            UUID questId = questId(typedMembers(quest, "questProgress[" + questIndex + "]"),
                "questProgress[" + questIndex + "]");
            JsonObject completions = quest.getAsJsonObject("completed:9");
            for (var completionEntry : completions.entrySet()) {
                JsonObject completion = completionEntry.getValue().getAsJsonObject();
                UUID uuid = UUID.fromString(completion.get("uuid:8").getAsString());
                JsonObject playerQuests = playerLists.computeIfAbsent(uuid, ignored -> new JsonObject());
                JsonObject splitQuest = new JsonObject();
                splitQuest.add("questIDHigh:4",
                    new JsonPrimitive(Long.valueOf(questId.getMostSignificantBits())));
                splitQuest.add("questIDLow:4",
                    new JsonPrimitive(Long.valueOf(questId.getLeastSignificantBits())));
                JsonObject splitCompletions = new JsonObject();
                splitCompletions.add("0:10", completion);
                splitQuest.add("completed:9", splitCompletions);
                splitQuest.add("tasks:9", new JsonObject());
                playerQuests.add(questIndex(playerQuests) + ":10", splitQuest);
            }
            questIndex++;
        }
        if (playerLists.size() > MAX_PLAYER_OUTPUTS) {
            throw new ContentException("legacy progress would create " + playerLists.size()
                + " player files, above the safety limit " + MAX_PLAYER_OUTPUTS);
        }

        List<PlayerOutput> outputs = new ArrayList<>();
        long totalBytes = 0;
        for (Map.Entry<UUID, JsonObject> player : playerLists.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString))).toList()) {
            JsonObject rootJson = new JsonObject();
            rootJson.add("questProgress:9", player.getValue());
            NBTTagCompound nbt = codec.toNbt(rootJson, new NBTTagCompound(), true);
            byte[] bytes = encode(nbt);
            totalBytes += bytes.length;
            if (totalBytes > MAX_GENERATED_BYTES) {
                throw new ContentException("generated progress exceeds " + MAX_GENERATED_BYTES
                    + " bytes across all player files");
            }
            Path path = containedOutput(root, player.getKey());
            outputs.add(new PlayerOutput(player.getKey(), path, bytes, sha256(bytes)));
        }

        Path backup = directChild(root, BACKUP_PATH);
        String sourceDigest = sha256(sourceBytes);
        List<ManifestOutput> manifest = outputs.stream()
            .map(output -> new ManifestOutput(output.uuid(),
                QuestProgressPersistence.pathFor(output.uuid()), output.bytes().length, output.digest()))
            .toList();
        byte[] prepared = marker("prepared", sourceBytes.length, sourceDigest, manifest);
        byte[] complete = marker("complete", sourceBytes.length, sourceDigest, manifest);
        return new Plan(sourceBytes, backup, List.copyOf(outputs), prepared, complete);
    }

    private byte[] currentCompleteMarker(RootSnapshot root, Layout layout, byte[] sourceBytes)
        throws IOException, ContentException {
        return marker("complete", sourceBytes.length, sha256(sourceBytes),
            currentOutputs(root, layout));
    }

    private List<ManifestOutput> currentOutputs(RootSnapshot root, Layout layout)
        throws IOException, ContentException {
        List<ManifestOutput> outputs = new ArrayList<>();
        for (Path path : layout.outputEntries()) {
            String file = path.getFileName().toString();
            Optional<UUID> uuid = parseCanonicalFileUuid(file);
            if (uuid.isEmpty()) {
                throw new ContentException("QuestProgress contains an unexpected output file: " + file);
            }
            CapturedFile captured = captureRegular(root, path,
                QuestProgressPersistence.MAX_DOCUMENT_BYTES + 1, "player output");
            if (captured.bytes().length > QuestProgressPersistence.MAX_DOCUMENT_BYTES) {
                throw new ContentException("player output exceeds safety limit: " + file);
            }
            outputs.add(new ManifestOutput(uuid.orElseThrow(),
                QuestProgressPersistence.pathFor(uuid.orElseThrow()), captured.bytes().length,
                sha256(captured.bytes())));
        }
        outputs.sort(Comparator.comparing(output -> output.uuid().toString()));
        return List.copyOf(outputs);
    }

    private static Optional<UUID> parseCanonicalFileUuid(String file) {
        if (!file.endsWith(".json")) return Optional.empty();
        String value = file.substring(0, file.length() - ".json".length());
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static int questIndex(JsonObject playerQuests) {
        int count = 0;
        for (var ignored : playerQuests.entrySet()) count++;
        return count;
    }

    private byte[] encode(NBTTagCompound nbt) throws IOException, ContentException {
        BoundedOutputStream output = new BoundedOutputStream(
            Math.toIntExact(QuestProgressPersistence.MAX_DOCUMENT_BYTES));
        JsonWriter writer = JsonDocuments.writer(output);
        try {
            codec.write(nbt, writer, true);
            writer.flush();
        } catch (OutputLimitException oversized) {
            throw new ContentException("generated player output exceeds "
                + QuestProgressPersistence.MAX_DOCUMENT_BYTES + " bytes", oversized);
        }
        return output.toByteArray();
    }

    private List<ManifestOutput> readCompleteMarker(RootSnapshot root, byte[] sourceBytes)
        throws IOException, ContentException {
        CapturedFile captured = captureRegular(root, root.path().resolve(COMPLETE_MARKER_PATH),
            MAX_MARKER_BYTES + 1L, "complete marker");
        if (captured.bytes().length > MAX_MARKER_BYTES) {
            throw new ContentException("complete marker exceeds " + MAX_MARKER_BYTES + " bytes");
        }
        String document = decodeUtf8(captured.bytes(), "complete marker");
        String[] lines = document.split("\\n", -1);
        if (lines.length < 9 || !lines[lines.length - 1].isEmpty()) {
            throw new ContentException("complete marker has invalid line framing");
        }
        if (!MARKER_HEADER.equals(lines[0]) || !"complete".equals(field(lines[1], "state"))
            || !QuestProgressPersistence.LEGACY_PATH.equals(field(lines[2], "source.path"))) {
            throw new ContentException("complete marker has an invalid header or state");
        }
        int sourceSize = canonicalInt(field(lines[3], "source.size"), "source.size",
            QuestProgressPersistence.MAX_DOCUMENT_BYTES);
        String sourceDigest = digest(field(lines[4], "source.sha256"), "source.sha256");
        if (!BACKUP_PATH.equals(field(lines[5], "backup.path"))
            || !sourceDigest.equals(digest(field(lines[6], "backup.sha256"), "backup.sha256"))) {
            throw new ContentException("complete marker has invalid backup metadata");
        }
        int count = canonicalInt(field(lines[7], "output.count"), "output.count",
            MAX_PLAYER_OUTPUTS);
        if (lines.length != 9 + count * 4) {
            throw new ContentException("complete marker output count does not match its lines");
        }
        List<ManifestOutput> outputs = new ArrayList<>();
        Set<UUID> uuids = new HashSet<>();
        String previous = null;
        for (int index = 0; index < count; index++) {
            int offset = 8 + index * 4;
            String uuidValue = field(lines[offset], "output." + index + ".uuid");
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidValue);
            } catch (IllegalArgumentException invalid) {
                throw new ContentException("complete marker contains an invalid output UUID");
            }
            if (!uuid.toString().equals(uuidValue) || !uuids.add(uuid)
                || previous != null && previous.compareTo(uuidValue) >= 0) {
                throw new ContentException("complete marker output UUIDs are not canonical and sorted");
            }
            previous = uuidValue;
            String relativePath = field(lines[offset + 1], "output." + index + ".path");
            if (!QuestProgressPersistence.pathFor(uuid).equals(relativePath)) {
                throw new ContentException("complete marker output path does not match its UUID");
            }
            int size = canonicalInt(field(lines[offset + 2], "output." + index + ".size"),
                "output size", QuestProgressPersistence.MAX_DOCUMENT_BYTES);
            String outputDigest = digest(field(lines[offset + 3],
                "output." + index + ".sha256"), "output digest");
            outputs.add(new ManifestOutput(uuid, relativePath, size, outputDigest));
        }
        if (sourceSize != sourceBytes.length || !sourceDigest.equals(sha256(sourceBytes))) {
            throw new ContentException("complete marker does not match the preserved source");
        }
        byte[] canonical = marker("complete", sourceSize, sourceDigest, outputs);
        if (!MessageDigest.isEqual(captured.bytes(), canonical)) {
            throw new ContentException("complete marker is not canonically encoded");
        }
        return List.copyOf(outputs);
    }

    private static void validateRefreshOutputs(List<ManifestOutput> oldOutputs,
        List<ManifestOutput> currentOutputs, Map<UUID, ExpectedOutput> dirtyOutputs)
        throws IOException {
        Map<UUID, ManifestOutput> oldByUuid = manifestByUuid(oldOutputs);
        Map<UUID, ManifestOutput> currentByUuid = manifestByUuid(currentOutputs);
        for (Map.Entry<UUID, ExpectedOutput> dirty : dirtyOutputs.entrySet()) {
            UUID uuid = Objects.requireNonNull(dirty.getKey(), "dirty output UUID");
            ExpectedOutput expected = Objects.requireNonNull(dirty.getValue(), "dirty output state");
            if (!expected.matches(currentByUuid.get(uuid))) {
                throw new IOException("Lifecycle-owned player output no longer matches its known "
                    + "save/delete: " + uuid);
            }
        }
        Set<UUID> all = new LinkedHashSet<>(oldByUuid.keySet());
        all.addAll(currentByUuid.keySet());
        for (UUID uuid : all) {
            if (dirtyOutputs.containsKey(uuid)) continue;
            if (!Objects.equals(oldByUuid.get(uuid), currentByUuid.get(uuid))) {
                throw new IOException("Unrelated player output changed before complete-marker "
                    + "refresh: " + uuid);
            }
        }
    }

    private static Map<UUID, ManifestOutput> manifestByUuid(List<ManifestOutput> outputs) {
        Map<UUID, ManifestOutput> byUuid = new LinkedHashMap<>();
        for (ManifestOutput output : outputs) byUuid.put(output.uuid(), output);
        return byUuid;
    }

    private static String field(String line, String name) throws ContentException {
        String prefix = name + "=";
        if (!line.startsWith(prefix)) {
            throw new ContentException("complete marker is missing field " + name);
        }
        return line.substring(prefix.length());
    }

    private static int canonicalInt(String value, String description, long maximum)
        throws ContentException {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new ContentException("complete marker has invalid " + description);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed > maximum) throw new NumberFormatException();
            return Math.toIntExact(parsed);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new ContentException("complete marker has out-of-range " + description);
        }
    }

    private static String digest(String value, String description) throws ContentException {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new ContentException("complete marker has invalid " + description);
        }
        return value;
    }

    private static String decodeUtf8(byte[] bytes, String description) throws ContentException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new ContentException(description + " contains invalid UTF-8", malformed);
        }
    }

    private static byte[] marker(String state, int sourceSize, String sourceDigest,
        List<ManifestOutput> outputs) throws ContentException {
        StringBuilder marker = new StringBuilder();
        marker.append(MARKER_HEADER).append('\n');
        marker.append("state=").append(state).append('\n');
        marker.append("source.path=").append(QuestProgressPersistence.LEGACY_PATH).append('\n');
        marker.append("source.size=").append(sourceSize).append('\n');
        marker.append("source.sha256=").append(sourceDigest).append('\n');
        marker.append("backup.path=").append(BACKUP_PATH).append('\n');
        marker.append("backup.sha256=").append(sourceDigest).append('\n');
        marker.append("output.count=").append(outputs.size()).append('\n');
        for (int index = 0; index < outputs.size(); index++) {
            ManifestOutput output = outputs.get(index);
            marker.append("output.").append(index).append(".uuid=").append(output.uuid()).append('\n');
            marker.append("output.").append(index).append(".path=")
                .append(output.relativePath()).append('\n');
            marker.append("output.").append(index).append(".size=").append(output.size()).append('\n');
            marker.append("output.").append(index).append(".sha256=").append(output.digest()).append('\n');
        }
        byte[] bytes = marker.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MARKER_BYTES) {
            throw new ContentException("migration marker exceeds " + MAX_MARKER_BYTES + " bytes");
        }
        return bytes;
    }

    private Validation validateLegacy(JsonObject root) {
        try {
            Map<String, TypedMember> rootMembers = typedMembers(root, "document root");
            TypedMember portFormat = rootMembers.get("mitePortFormat");
            if (portFormat != null) {
                if (portFormat.type() == 8 && portFormat.value().isJsonPrimitive()
                    && portFormat.value().getAsJsonPrimitive().isString()
                    && portFormat.value().getAsString().matches("[1-9][0-9]*")) {
                    return Validation.blocked(List.of(),
                        "mitePortFormat is present; current and future port formats are never legacy-imported");
                }
                return Validation.invalid("mitePortFormat is present with a malformed typed value");
            }
            TypedMember progress = rootMembers.get("questProgress");
            if (progress == null || progress.type() != 9 || !progress.value().isJsonObject()) {
                return Validation.invalid("expected exact questProgress:9 object at document root");
            }
            if (rootMembers.size() != 1) {
                return Validation.blocked(List.of(),
                    "ownerless document-root fields cannot be assigned losslessly to player files");
            }

            List<JsonObject> records = compoundList(progress.value().getAsJsonObject(), 10,
                "questProgress");
            Set<UUID> questIds = new LinkedHashSet<>();
            Set<UUID> users = new LinkedHashSet<>();
            for (int index = 0; index < records.size(); index++) {
                String location = "questProgress[" + index + "]";
                JsonObject quest = records.get(index);
                Map<String, TypedMember> fields = typedMembers(quest, location);
                for (String field : fields.keySet()) {
                    if (!Set.of("questID", "questIDHigh", "questIDLow", "completed", "tasks")
                        .contains(field)) {
                        return Validation.blocked(sorted(users), location + "." + field
                            + " is ownerless quest data and cannot be split losslessly");
                    }
                }
                UUID questId = questId(fields, location);
                if (!questIds.add(questId)) {
                    return Validation.invalid(sorted(users),
                        location + " duplicates quest record " + questId);
                }
                boolean unknownQuest = quests.get(questId) == null;

                TypedMember completed = fields.get("completed");
                if (completed == null || completed.type() != 9 || !completed.value().isJsonObject()) {
                    return Validation.invalid(sorted(users), location
                        + " must contain exact completed:9 object");
                }
                TypedMember tasks = fields.get("tasks");
                if (tasks == null || tasks.type() != 9 || !tasks.value().isJsonObject()) {
                    return Validation.invalid(sorted(users), location
                        + " must contain exact tasks:9 object");
                }
                List<JsonObject> completions = compoundList(completed.value().getAsJsonObject(), 10,
                    location + ".completed");
                if (completions.isEmpty()) {
                    return Validation.blocked(sorted(users), location
                        + " has no completion owner; the record cannot be assigned losslessly");
                }
                Set<UUID> questUsers = new LinkedHashSet<>();
                for (int completionIndex = 0; completionIndex < completions.size(); completionIndex++) {
                    String completionLocation = location + ".completed[" + completionIndex + "]";
                    JsonObject completion = completions.get(completionIndex);
                    validateExactCompound(completion, completionLocation);
                    Map<String, TypedMember> completionFields = typedMembers(completion,
                        completionLocation);
                    TypedMember uuidField = completionFields.get("uuid");
                    if (uuidField == null || uuidField.type() != 8
                        || !uuidField.value().isJsonPrimitive()
                        || !uuidField.value().getAsJsonPrimitive().isString()) {
                        return Validation.invalid(sorted(users), completionLocation
                            + " must contain exact uuid:8 string");
                    }
                    String uuidValue = uuidField.value().getAsString();
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidValue);
                    } catch (IllegalArgumentException invalid) {
                        return Validation.invalid(sorted(users), completionLocation
                            + ".uuid is not a UUID: " + uuidValue);
                    }
                    if (!uuid.toString().equals(uuidValue)) {
                        return Validation.invalid(sorted(users), completionLocation
                            + ".uuid is not canonical: " + uuidValue);
                    }
                    if (!questUsers.add(uuid)) {
                        return Validation.invalid(sorted(users), location
                            + " contains duplicate completion UUID " + uuid);
                    }
                    users.add(uuid);
                }
                if (unknownQuest) {
                    return Validation.blocked(sorted(users),
                        location + " has unknown quest ID " + questId);
                }
                if (!tasks.value().getAsJsonObject().entrySet().isEmpty()) {
                    return Validation.blocked(sorted(users), location
                        + ".tasks is non-empty and task ownership cannot be recovered safely");
                }
            }
            return Validation.accepted(sorted(users));
        } catch (ContentException malformed) {
            return Validation.invalid(malformed.getMessage());
        }
    }

    private static UUID questId(Map<String, TypedMember> fields, String location)
        throws ContentException {
        TypedMember legacy = fields.get("questID");
        TypedMember high = fields.get("questIDHigh");
        TypedMember low = fields.get("questIDLow");
        boolean hasLegacy = legacy != null;
        boolean hasPair = high != null || low != null;
        if (hasLegacy && hasPair) {
            throw new ContentException(location + " contains ambiguous dual quest IDs");
        }
        if (hasLegacy) {
            if (legacy.type() != 3) {
                throw new ContentException(location + ".questID must be an int tag");
            }
            int id = integral(legacy.value(), INT_MIN, INT_MAX, location + ".questID").intValue();
            try {
                return UuidConverter.convertLegacyId(id);
            } catch (IllegalArgumentException invalid) {
                throw new ContentException(location + ".questID must be non-negative");
            }
        }
        if (high == null || low == null || high.type() != 4 || low.type() != 4) {
            throw new ContentException(location
                + " must contain both questIDHigh:4 and questIDLow:4 or one questID:3");
        }
        long highValue = integral(high.value(), LONG_MIN, LONG_MAX,
            location + ".questIDHigh").longValue();
        long lowValue = integral(low.value(), LONG_MIN, LONG_MAX,
            location + ".questIDLow").longValue();
        return new UUID(highValue, lowValue);
    }

    private static void validateExactCompound(JsonObject compound, String location)
        throws ContentException {
        for (TypedMember member : typedMembers(compound, location).values()) {
            validateExactValue(member.value(), member.type(), location + "." + member.base());
        }
    }

    private static void validateExactValue(JsonElement value, int type, String location)
        throws ContentException {
        switch (type) {
            case 1 -> {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return;
                integral(value, BYTE_MIN, BYTE_MAX, location);
            }
            case 2 -> integral(value, SHORT_MIN, SHORT_MAX, location);
            case 3 -> integral(value, INT_MIN, INT_MAX, location);
            case 4 -> integral(value, LONG_MIN, LONG_MAX, location);
            case 5, 6 -> throw new ContentException(location
                + " uses floating-point NBT; exact automatic conversion is not proven");
            case 7 -> validateIntegerArray(value, BYTE_MIN, BYTE_MAX, location);
            case 8 -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new ContentException(location + " must be a string");
                }
            }
            case 9 -> {
                if (!value.isJsonObject()) {
                    throw new ContentException(location + " must use the formatted object-list shape");
                }
                validateExactList(value.getAsJsonObject(), location);
            }
            case 10 -> {
                if (!value.isJsonObject()) throw new ContentException(location + " must be an object");
                validateExactCompound(value.getAsJsonObject(), location);
            }
            case 11 -> validateIntegerArray(value, INT_MIN, INT_MAX, location);
            default -> throw new ContentException(location + " has unsupported NBT type " + type);
        }
    }

    private static void validateExactList(JsonObject list, String location) throws ContentException {
        int index = 0;
        for (var entry : list.entrySet()) {
            TypedKey key = typedKey(entry.getKey(), location);
            if (!key.base().equals(Integer.toString(index))) {
                throw new ContentException(location + " must use contiguous ordered list index " + index);
            }
            validateExactValue(entry.getValue(), key.type(), location + "[" + index + "]");
            index++;
        }
    }

    private static void validateIntegerArray(JsonElement value, BigInteger minimum,
        BigInteger maximum, String location) throws ContentException {
        if (!value.isJsonArray()) throw new ContentException(location + " must be an array");
        JsonArray array = value.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            integral(array.get(index), minimum, maximum, location + "[" + index + "]");
        }
    }

    private static BigInteger integral(JsonElement value, BigInteger minimum, BigInteger maximum,
        String location) throws ContentException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ContentException(location + " must be an integer JSON number");
        }
        String literal = value.getAsJsonPrimitive().getAsString();
        if (!literal.matches("-?(0|[1-9][0-9]*)")) {
            throw new ContentException(location + " must be a canonical integral literal");
        }
        BigInteger parsed;
        try {
            parsed = new BigInteger(literal);
        } catch (NumberFormatException invalid) {
            throw new ContentException(location + " is not an integer");
        }
        if (parsed.compareTo(minimum) < 0 || parsed.compareTo(maximum) > 0) {
            throw new ContentException(location + " is outside [" + minimum + ", " + maximum + "]");
        }
        return parsed;
    }

    private static List<JsonObject> compoundList(JsonObject list, int elementType, String location)
        throws ContentException {
        List<JsonObject> values = new ArrayList<>();
        int index = 0;
        for (var entry : list.entrySet()) {
            String expected = index + ":" + elementType;
            if (!expected.equals(entry.getKey()) || !entry.getValue().isJsonObject()) {
                throw new ContentException(location + " must contain contiguous " + expected
                    + " compound entries");
            }
            values.add(entry.getValue().getAsJsonObject());
            index++;
        }
        return values;
    }

    private static Map<String, TypedMember> typedMembers(JsonObject object, String location)
        throws ContentException {
        Map<String, TypedMember> byBase = new LinkedHashMap<>();
        for (var entry : object.entrySet()) {
            TypedKey key = typedKey(entry.getKey(), location);
            TypedMember previous = byBase.putIfAbsent(key.base(),
                new TypedMember(key.base(), key.type(), entry.getValue()));
            if (previous != null) {
                throw new ContentException(location + " has conflicting typed keys for " + key.base());
            }
        }
        return byBase;
    }

    private static TypedKey typedKey(String raw, String location) throws ContentException {
        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            throw new ContentException(location + " contains untyped key " + raw);
        }
        int type;
        try {
            type = Integer.parseInt(raw.substring(colon + 1));
        } catch (NumberFormatException invalid) {
            throw new ContentException(location + " contains malformed typed key " + raw);
        }
        if (type < 1 || type > 11 || !Integer.toString(type).equals(raw.substring(colon + 1))) {
            throw new ContentException(location + " contains unsupported typed key " + raw);
        }
        return new TypedKey(raw.substring(0, colon), type);
    }

    private static List<UUID> sorted(Set<UUID> users) {
        return users.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private boolean matches(RootSnapshot root, Path path, byte[] expected, long maximum,
        String description) throws IOException {
        CapturedFile captured;
        try {
            captured = captureRegular(root, path, maximum, description);
        } catch (NoSuchFileException missing) {
            return false;
        }
        return captured.bytes().length == expected.length
            && MessageDigest.isEqual(sha256Bytes(captured.bytes()), sha256Bytes(expected));
    }

    private CapturedFile captureRegular(RootSnapshot root, Path path, long maximum,
        String description) throws IOException {
        root.revalidate();
        BasicFileAttributes before = attributes(path);
        requireRegular(path, before, description);
        byte[] bytes;
        try (SeekableByteChannel channel = openNoFollow(path, before, root, description)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(maximum, Integer.MAX_VALUE));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            }
            bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
        }
        BasicFileAttributes after = attributes(path);
        requireRegular(path, after, description);
        requireSame(path, before, after, description);
        root.revalidate();
        return new CapturedFile(bytes);
    }

    private static SeekableByteChannel openNoFollow(Path path, BasicFileAttributes before,
        RootSnapshot root, String description) throws IOException {
        try {
            return Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            root.revalidate();
            BasicFileAttributes rechecked = attributes(path);
            requireRegular(path, rechecked, description);
            requireSame(path, before, rechecked, description);
            return Files.newByteChannel(path, StandardOpenOption.READ);
        }
    }

    private static void requireSame(Path path, BasicFileAttributes before,
        BasicFileAttributes after, String description) throws IOException {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        boolean changed = beforeKey != null && afterKey != null && !beforeKey.equals(afterKey);
        changed |= before.size() != after.size();
        changed |= !before.lastModifiedTime().equals(after.lastModifiedTime());
        if (changed) throw new IOException(description + " changed during capture: " + path);
    }

    private static boolean validateOptionalRegular(Path path, String description) throws IOException {
        try {
            requireRegular(path, attributes(path), description);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireDirectory(Path path, BasicFileAttributes attributes,
        String description) throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException(description + " is not a non-reparse directory: " + path);
        }
    }

    private static void requireRegular(Path path, BasicFileAttributes attributes,
        String description) throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException(description + " is not a regular non-reparse file: " + path);
        }
    }

    private static Path directChild(Path root, String name) throws IOException {
        Path path = root.resolve(name).normalize();
        if (!root.equals(path.getParent())) {
            throw new IOException("Migration path escapes canonical data root: " + path);
        }
        return path;
    }

    private static Path containedOutput(Path root, UUID uuid) throws IOException {
        Path directory = root.resolve(QuestProgressPersistence.DIRECTORY).normalize();
        if (!root.equals(directory.getParent())) {
            throw new IOException("QuestProgress directory escapes canonical data root: " + directory);
        }
        Path output = directory.resolve(uuid + ".json").normalize();
        if (!directory.equals(output.getParent())) {
            throw new IOException("Player output escapes QuestProgress directory: " + output);
        }
        return output;
    }

    private static String sha256(byte[] bytes) {
        byte[] digest = sha256Bytes(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    enum Checkpoint { PREPARED, BACKUP, OUTPUT, COMPLETE }

    interface MigrationIo {
        FileChannel createNew(Path path) throws IOException;

        int write(FileChannel channel, ByteBuffer bytes) throws IOException;

        void syncFile(Path path, FileChannel channel) throws IOException;

        void syncDirectory(Path directory) throws IOException;

        void publishMarker(Path temporary, Path marker) throws IOException;

        void replaceMarker(Path temporary, Path marker) throws IOException;

        void deleteIfExists(Path path) throws IOException;

        void checkpoint(Checkpoint checkpoint, Path path);
    }

    static final class NioMigrationIo implements MigrationIo {
        @Override
        public FileChannel createNew(Path path) throws IOException {
            return FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            return channel.write(bytes);
        }

        @Override
        public void syncFile(Path path, FileChannel channel) throws IOException {
            channel.force(true);
        }

        @Override
        public void syncDirectory(Path directory) throws IOException {
            if (directory == null || File.separatorChar == '\\') return;
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (UnsupportedOperationException ignored) {
            }
        }

        @Override
        public void publishMarker(Path temporary, Path marker) throws IOException {
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic marker publication is unsupported for " + marker,
                    unsupported);
            }
        }

        @Override
        public void replaceMarker(Path temporary, Path marker) throws IOException {
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic marker replacement is unsupported for " + marker,
                    unsupported);
            }
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }

        @Override
        public void checkpoint(Checkpoint checkpoint, Path path) {
        }
    }

    enum Status { ABSENT, MIGRATED, BLOCKED, QUARANTINED, OVERSIZED }

    record Result(Status status, List<UUID> users, Optional<Path> backupPath, List<String> issues) {
        Result {
            users = List.copyOf(users);
            backupPath = Objects.requireNonNull(backupPath, "backupPath");
            issues = List.copyOf(issues);
        }

        static Result absent() {
            return new Result(Status.ABSENT, List.of(), Optional.empty(), List.of());
        }

        static Result migrated(List<UUID> users, Path backup, String issue) {
            return new Result(Status.MIGRATED, users, Optional.of(backup), List.of(issue));
        }

        static Result blocked(List<UUID> users, String issue) {
            return new Result(Status.BLOCKED, users, Optional.empty(), List.of(issue));
        }

        static Result quarantined(String issue) {
            return quarantined(List.of(), issue);
        }

        static Result quarantined(List<UUID> users, String issue) {
            return new Result(Status.QUARANTINED, users, Optional.empty(), List.of(issue));
        }

        static Result oversized(String issue) {
            return new Result(Status.OVERSIZED, List.of(), Optional.empty(), List.of(issue));
        }
    }

    private enum ValidationStatus { ACCEPTED, BLOCKED, INVALID }

    private record Validation(ValidationStatus status, List<UUID> users, String issue) {
        static Validation accepted(List<UUID> users) {
            return new Validation(ValidationStatus.ACCEPTED, users, null);
        }

        static Validation blocked(List<UUID> users, String issue) {
            return new Validation(ValidationStatus.BLOCKED, users, issue);
        }

        static Validation invalid(String issue) {
            return invalid(List.of(), issue);
        }

        static Validation invalid(List<UUID> users, String issue) {
            return new Validation(ValidationStatus.INVALID, users, issue);
        }
    }

    private enum StateStatus { NONE, PREPARED, COMPLETE, BLOCKED }

    private record State(StateStatus status, String issue) {
        static State none() { return new State(StateStatus.NONE, null); }
        static State prepared() { return new State(StateStatus.PREPARED, null); }
        static State complete() { return new State(StateStatus.COMPLETE, null); }
        static State blocked(String issue) { return new State(StateStatus.BLOCKED, issue); }
    }

    private record Layout(boolean sourceExists, boolean backupExists, boolean preparedExists,
                          boolean completeExists, boolean preparedTempExists,
                          boolean completeTempExists, boolean outputDirectoryExists,
                          List<Path> outputEntries) {
        boolean hasMigrationState() {
            return backupExists || preparedExists || completeExists || preparedTempExists
                || completeTempExists;
        }
    }

    private record RootSnapshot(Path path, Object fileKey) {
        void revalidate() throws IOException {
            BasicFileAttributes current = attributes(path);
            requireDirectory(path, current, "canonical BetterQuesting data root");
            if (fileKey != null && current.fileKey() != null && !fileKey.equals(current.fileKey())) {
                throw new IOException("Canonical BetterQuesting data root identity changed: " + path);
            }
        }
    }

    private record CapturedFile(byte[] bytes) {
    }

    private record Plan(byte[] sourceBytes, Path backupPath, List<PlayerOutput> outputs,
                        byte[] preparedMarker, byte[] completeMarker) {
    }

    private record RefreshState(RootSnapshot root, Layout layout, byte[] sourceBytes,
                                List<ManifestOutput> currentOutputs) {
    }

    private record PlayerOutput(UUID uuid, Path path, byte[] bytes, String digest) {
    }

    private record ManifestOutput(UUID uuid, String relativePath, int size, String digest) {
    }

    record ExpectedOutput(boolean exists, int size, String digest) {
        static ExpectedOutput present(byte[] bytes) {
            return new ExpectedOutput(true, bytes.length, sha256(bytes));
        }

        static ExpectedOutput deleted() {
            return new ExpectedOutput(false, 0, null);
        }

        boolean matches(ManifestOutput output) {
            return exists ? output != null && size == output.size() && digest.equals(output.digest())
                : output == null;
        }
    }

    private record TypedKey(String base, int type) {
    }

    private record TypedMember(String base, int type, JsonElement value) {
    }

    private static final class ContentException extends Exception {
        ContentException(String message) {
            super(message);
        }

        ContentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum;

        private BoundedOutputStream(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            requireCapacity(length);
            bytes.write(values, offset, length);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private void requireCapacity(int additional) throws OutputLimitException {
            if (additional > maximum - bytes.size()) throw new OutputLimitException();
        }
    }

    private static final class OutputLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class StrictJson {
        private StrictJson() {
        }

        static JsonObject parseObject(byte[] bytes, int maxDepth) throws ContentException {
            String document;
            try {
                document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException malformed) {
                throw new ContentException("QuestProgress.json contains invalid UTF-8", malformed);
            }
            if (!document.isEmpty() && document.charAt(0) == '\ufeff') {
                throw new ContentException("QuestProgress.json begins with a non-JSON byte-order mark");
            }
            JsonReader reader = new JsonReader(new StringReader(document));
            reader.setLenient(false);
            try {
                JsonElement value = read(reader, 0, maxDepth);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new ContentException("trailing content after JSON document");
                }
                if (!value.isJsonObject()) {
                    throw new ContentException("expected a JSON object at the document root");
                }
                return value.getAsJsonObject();
            } catch (ContentException malformed) {
                throw malformed;
            } catch (IOException | RuntimeException malformed) {
                throw new ContentException("malformed strict JSON: " + malformed.getMessage(), malformed);
            }
        }

        private static JsonElement read(JsonReader reader, int depth, int maxDepth)
            throws IOException, ContentException {
            JsonToken token = reader.peek();
            return switch (token) {
                case BEGIN_OBJECT -> readObject(reader, depth + 1, maxDepth);
                case BEGIN_ARRAY -> readArray(reader, depth + 1, maxDepth);
                case STRING -> new JsonPrimitive(requirePairedSurrogates(
                    reader.nextString(), "JSON string"));
                case NUMBER -> number(reader.nextString());
                case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                case NULL -> {
                    reader.nextNull();
                    yield JsonNull.INSTANCE;
                }
                default -> throw new ContentException("unexpected JSON token " + token);
            };
        }

        private static JsonObject readObject(JsonReader reader, int depth, int maxDepth)
            throws IOException, ContentException {
            requireDepth(depth, maxDepth);
            reader.beginObject();
            JsonObject object = new JsonObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                requirePairedSurrogates(name, "JSON member name");
                if (!names.add(name)) throw new ContentException("duplicate JSON object key: " + name);
                object.add(name, read(reader, depth, maxDepth));
            }
            reader.endObject();
            return object;
        }

        private static JsonArray readArray(JsonReader reader, int depth, int maxDepth)
            throws IOException, ContentException {
            requireDepth(depth, maxDepth);
            reader.beginArray();
            JsonArray array = new JsonArray();
            while (reader.hasNext()) array.add(read(reader, depth, maxDepth));
            reader.endArray();
            return array;
        }

        private static JsonPrimitive number(String literal) throws ContentException {
            if (!literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw new ContentException("non-canonical JSON number: " + literal);
            }
            if ("-0".equals(literal)) {
                throw new ContentException("non-canonical integer negative zero: " + literal);
            }
            try {
                return new JsonPrimitive(new LexicalNumber(literal, new BigDecimal(literal)));
            } catch (NumberFormatException invalid) {
                throw new ContentException("invalid JSON number: " + literal, invalid);
            }
        }

        private static String requirePairedSurrogates(String value, String description)
            throws ContentException {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                        throw new ContentException(description + " contains an unpaired UTF-16 surrogate");
                    }
                    index++;
                } else if (Character.isLowSurrogate(current)) {
                    throw new ContentException(description + " contains an unpaired UTF-16 surrogate");
                }
            }
            return value;
        }

        private static final class LexicalNumber extends Number {
            private static final long serialVersionUID = 1L;

            private final String literal;
            private final BigDecimal value;

            private LexicalNumber(String literal, BigDecimal value) {
                this.literal = literal;
                this.value = value;
            }

            @Override public int intValue() { return value.intValue(); }
            @Override public long longValue() { return value.longValue(); }
            @Override public float floatValue() { return value.floatValue(); }
            @Override public double doubleValue() { return value.doubleValue(); }
            @Override public byte byteValue() { return value.byteValue(); }
            @Override public short shortValue() { return value.shortValue(); }
            @Override public String toString() { return literal; }
        }

        private static void requireDepth(int depth, int maxDepth) throws ContentException {
            if (depth > maxDepth) {
                throw new ContentException("JSON structural depth exceeds " + maxDepth);
            }
        }
    }
}
