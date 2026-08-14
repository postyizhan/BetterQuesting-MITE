package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.MalformedJsonDocumentException;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import com.github.postyizhan.betterquesting.core.storage.json.OversizedJsonDocumentException;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/** Safe boundary for upstream QuestProgress.json and QuestProgress/&lt;uuid&gt;.json data. */
public final class QuestProgressPersistence {
    public static final String LEGACY_PATH = "QuestProgress.json";
    public static final String DIRECTORY = "QuestProgress";
    public static final long MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L;

    private final QuestDatabase quests;
    private final JsonDocumentStore store;
    private final WorldStorage storage;
    private final NbtJsonCodec codec = new NbtJsonCodec();

    public QuestProgressPersistence(QuestDatabase quests, WorldStorage storage) {
        this(quests, new JsonDocumentStore(storage), storage);
    }

    public QuestProgressPersistence(QuestDatabase quests, JsonDocumentStore store, WorldStorage storage) {
        this.quests = Objects.requireNonNull(quests, "quests");
        this.store = Objects.requireNonNull(store, "store");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Loads every canonical per-player file as one transaction. Legacy data is never auto-linked. */
    public synchronized LoadReport load() throws IOException {
        clearProgress();
        if (storage.exists(LEGACY_PATH)) {
            LegacyMigrationReport legacy = analyzeLegacy();
            LoadStatus status = switch (legacy.status()) {
                case QUARANTINED -> LoadStatus.QUARANTINED;
                case OVERSIZED -> LoadStatus.OVERSIZED;
                case BLOCKED -> LoadStatus.BLOCKED;
                case ABSENT -> LoadStatus.ABSENT;
            };
            return new LoadReport(status, List.of(), legacy.issues(), Optional.of(legacy));
        }

        List<String> files = new ArrayList<>(storage.list(DIRECTORY, ".json"));
        List<StagedPlayer> staged = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        boolean oversized = false;
        boolean invalid = false;
        boolean blocked = false;
        for (String file : files) {
            Optional<UUID> pathUuid = parseCanonicalFileUuid(file);
            if (pathUuid.isEmpty()) {
                issues.add("invalid player progress filename: " + file);
                invalid = true;
                continue;
            }
            UUID uuid = pathUuid.orElseThrow();
            ReadResult read = readDocument(DIRECTORY + "/" + file);
            if (!read.loaded()) {
                issues.add(read.issue());
                oversized |= read.oversized();
                invalid |= !read.oversized();
                continue;
            }
            ValidationResult validation = validatePlayerRoot(read.root(), uuid);
            if (validation.status() == ValidationStatus.INVALID) {
                issues.add(DIRECTORY + "/" + file + ": " + validation.issue());
                quarantine(DIRECTORY + "/" + file);
                invalid = true;
                continue;
            }
            if (validation.status() == ValidationStatus.BLOCKED) {
                issues.add(DIRECTORY + "/" + file + ": " + validation.issue());
                blocked = true;
                continue;
            }
            staged.add(new StagedPlayer(uuid, read.root()));
        }
        if (!issues.isEmpty()) {
            LoadStatus status = oversized ? LoadStatus.OVERSIZED
                : invalid ? LoadStatus.QUARANTINED : blocked ? LoadStatus.BLOCKED : LoadStatus.QUARANTINED;
            return new LoadReport(status,
                List.of(), List.copyOf(issues));
        }

        NBTTagList snapshot = quests.writeProgressToNBT(new NBTTagList(), null);
        try {
            for (StagedPlayer player : staged) {
                quests.readProgressFromNBT(NbtCompat.getListOrEmpty(player.root(), "questProgress"), true);
            }
        } catch (RuntimeException failure) {
            quests.readProgressFromNBT(snapshot, false);
            throw failure;
        }
        List<UUID> loaded = staged.stream().map(StagedPlayer::uuid).toList();
        return new LoadReport(loaded.isEmpty() ? LoadStatus.ABSENT : LoadStatus.LOADED,
            loaded, List.of());
    }

    /** Saves exactly one player's progress using the upstream root shape and canonical UUID path. */
    public synchronized void savePlayer(UUID uuid) throws IOException {
        NBTTagCompound snapshot;
        try {
            snapshot = snapshotPlayer(uuid);
        } catch (IllegalStateException unsafeSnapshot) {
            throw new IOException(unsafeSnapshot.getMessage(), unsafeSnapshot);
        }
        savePlayer(uuid, snapshot);
    }

    public synchronized NBTTagCompound snapshotPlayer(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("questProgress", quests.writeProgressToNBT(new NBTTagList(), List.of(uuid)));
        ValidationResult validation = validatePlayerRoot(root, uuid);
        if (validation.status() != ValidationStatus.ACCEPTED) {
            throw new IllegalStateException("Refusing unsafe player progress snapshot: " + validation.issue());
        }
        return root;
    }

    public synchronized void savePlayer(UUID uuid, NBTTagCompound root) throws IOException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(root, "root");
        ValidationResult validation = validatePlayerRoot(root, uuid);
        if (validation.status() != ValidationStatus.ACCEPTED) {
            throw new IOException("Refusing unsafe player progress snapshot: " + validation.issue());
        }
        store.save(pathFor(uuid), root, true);
    }

    public synchronized boolean deletePlayer(UUID uuid) throws IOException {
        Objects.requireNonNull(uuid, "uuid");
        return storage.delete(pathFor(uuid));
    }

    /** Clears only progress, retaining the quest definitions for the next world/session. */
    public synchronized void clearProgress() {
        for (IQuest quest : quests.values()) {
            if (quest != null) {
                quest.resetUser(null, true);
            }
        }
    }

    /**
     * Analyzes legacy progress and deliberately returns BLOCKED: current APIs cannot prove task
     * ownership for every record, so writing a split file would risk cross-linking a player.
     */
    public synchronized LegacyMigrationReport migrateLegacy() throws IOException {
        if (!storage.exists(LEGACY_PATH)) {
            return new LegacyMigrationReport(MigrationStatus.ABSENT, List.of(), Optional.empty(),
                true, List.of());
        }
        return analyzeLegacy(Optional.empty());
    }

    /** Parses and reports legacy data without attempting conversion or creating a timestamped backup. */
    public synchronized LegacyMigrationReport analyzeLegacy() throws IOException {
        if (!storage.exists(LEGACY_PATH)) {
            return new LegacyMigrationReport(MigrationStatus.ABSENT, List.of(), Optional.empty(),
                true, List.of());
        }
        return analyzeLegacy(Optional.empty());
    }

    private LegacyMigrationReport analyzeLegacy(Optional<java.nio.file.Path> backup) throws IOException {
        ReadResult read = readDocument(LEGACY_PATH);
        if (!read.loaded()) {
            MigrationStatus status = read.oversized() ? MigrationStatus.OVERSIZED : MigrationStatus.QUARANTINED;
            return new LegacyMigrationReport(status, List.of(), backup,
                true, List.of(read.issue()));
        }
        ValidationResult validation = validateProgressRoot(read.root());
        if (validation.status() == ValidationStatus.BLOCKED) {
            return new LegacyMigrationReport(MigrationStatus.BLOCKED, List.of(), backup,
                true, List.of(validation.issue()));
        }
        UserScan users = scanUsers(read.root());
        if (validation.status() == ValidationStatus.INVALID) {
            quarantine(LEGACY_PATH);
            List<String> issues = new ArrayList<>();
            issues.add(validation.issue());
            issues.addAll(users.issues());
            return new LegacyMigrationReport(MigrationStatus.QUARANTINED, users.users(), backup,
                true, issues);
        }
        if (!users.issues().isEmpty()) {
            quarantine(LEGACY_PATH);
            return new LegacyMigrationReport(MigrationStatus.QUARANTINED, users.users(), backup,
                true, users.issues());
        }
        List<String> issues = new ArrayList<>(users.issues());
        issues.add("task progress ownership is not independently recoverable from existing APIs; explicit per-record mapping and a staged importer are required");
        return new LegacyMigrationReport(MigrationStatus.BLOCKED, users.users(), backup,
            true, issues);
    }

    public static String pathFor(UUID uuid) {
        return DIRECTORY + "/" + Objects.requireNonNull(uuid, "uuid") + ".json";
    }

    private ReadResult readDocument(String path) throws IOException {
        try {
            Optional<NBTTagCompound> root = storage.read(path, input -> {
                byte[] bytes = readBounded(input);
                try {
                    return codec.toNbt(JsonDocuments.parseObject(
                        new java.io.ByteArrayInputStream(bytes)), new NBTTagCompound(), true);
                } catch (MalformedJsonDocumentException malformed) {
                    throw malformed;
                }
            });
            if (root.isEmpty()) return ReadResult.missing(path);
            return ReadResult.loaded(root.orElseThrow());
        } catch (OversizedJsonDocumentException oversized) {
            return ReadResult.oversized(path + ": " + oversized.getMessage());
        } catch (MalformedJsonDocumentException malformed) {
            quarantine(path);
            return ReadResult.failed(path + ": malformed JSON");
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes((int) MAX_DOCUMENT_BYTES + 1);
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new OversizedJsonDocumentException("document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        return bytes;
    }

    private void quarantine(String path) throws IOException {
        store.quarantine(path);
    }

    private static Optional<UUID> parseCanonicalFileUuid(String file) {
        if (file == null || !file.endsWith(".json")) return Optional.empty();
        String value = file.substring(0, file.length() - ".json".length());
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static ValidationResult validateProgressRoot(NBTTagCompound root) {
        if (root.hasKey("mitePortFormat")) {
            if (NbtCompat.getTagId(root, "mitePortFormat") != 8) {
                return ValidationResult.invalid("mitePortFormat must be a string");
            }
            if (!"1".equals(root.getString("mitePortFormat"))) {
                return ValidationResult.blocked(
                    "unsupported future mitePortFormat: " + root.getString("mitePortFormat"));
            }
        }
        if (!root.hasKey("questProgress") || NbtCompat.getTagId(root, "questProgress") != 9) {
            return ValidationResult.invalid("expected questProgress list at document root");
        }
        return ValidationResult.accepted();
    }

    private ValidationResult validatePlayerRoot(NBTTagCompound root, UUID uuid) {
        ValidationResult rootValidation = validateProgressRoot(root);
        if (rootValidation.status() != ValidationStatus.ACCEPTED) return rootValidation;
        UserScan users = scanUsers(root);
        if (!users.issues().isEmpty()) return ValidationResult.invalid(users.issues().get(0));
        for (UUID found : users.completionUsers()) {
            if (!uuid.equals(found)) {
                return ValidationResult.invalid("content UUID " + found + " does not match path UUID " + uuid);
            }
        }
        for (QuestRecordId quest : users.questIds()) {
            if (quests.get(quest.id()) == null) {
                return ValidationResult.blocked(quest.location() + " has unknown quest ID " + quest.id());
            }
        }
        if (users.nonEmptyTaskProgress()) {
            return ValidationResult.blocked("non-empty task progress is unsupported by completion-only persistence");
        }
        if (!users.emptyCompletionRecords().isEmpty()) {
            return ValidationResult.blocked(users.emptyCompletionRecords().get(0)
                + " has no completion record for the path player");
        }
        return ValidationResult.accepted();
    }

    private static UserScan scanUsers(NBTTagCompound root) {
        Set<UUID> users = new LinkedHashSet<>();
        Set<UUID> completionUsers = new LinkedHashSet<>();
        List<QuestRecordId> questIds = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<String> emptyCompletionRecords = new ArrayList<>();
        NBTTagList quests = NbtCompat.getListOrEmpty(root, "questProgress");
        boolean nonEmptyTaskProgress = false;
        for (int i = 0; i < quests.tagCount(); i++) {
            NBTTagCompound quest = NbtCompat.getCompoundAt(quests, i);
            String questLocation = "questProgress[" + i + "]";
            if (quest == null) {
                issues.add(questLocation + " is not a compound");
                continue;
            }
            QuestIdScan questId = scanQuestId(quest, questLocation);
            if (questId.issue() == null) {
                questIds.add(new QuestRecordId(questId.id(), questLocation));
            } else {
                issues.add(questId.issue());
            }
            if (quest.hasKey("completed") && NbtCompat.getTagId(quest, "completed") != 9) {
                issues.add(questLocation + ".completed is not a list");
            }
            NBTTagList completed = NbtCompat.getListOrEmpty(quest, "completed");
            if (completed.tagCount() == 0) emptyCompletionRecords.add(questLocation);
            for (int j = 0; j < completed.tagCount(); j++) {
                NBTTagCompound item = NbtCompat.getCompoundAt(completed, j);
                String location = questLocation + ".completed[" + j + "]";
                if (item == null) {
                    issues.add(location + " is not a compound");
                    continue;
                }
                if (NbtCompat.getTagId(item, "uuid") != 8) {
                    issues.add(location + ".uuid is missing or is not a string");
                    continue;
                }
                addUuid(completionUsers, issues, item.getString("uuid"), location + ".uuid");
            }
            users.addAll(completionUsers);
            if (quest.hasKey("tasks") && NbtCompat.getTagId(quest, "tasks") != 9) {
                issues.add(questLocation + ".tasks is not a list");
            }
            TaskScan taskScan = scanTasks(NbtCompat.getListOrEmpty(quest, "tasks"),
                questLocation + ".tasks");
            users.addAll(taskScan.users());
            issues.addAll(taskScan.issues());
            nonEmptyTaskProgress |= taskScan.nonEmpty();
        }
        return new UserScan(
            users.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
            completionUsers.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
            List.copyOf(questIds), issues, nonEmptyTaskProgress, List.copyOf(emptyCompletionRecords));
    }

    private static QuestIdScan scanQuestId(NBTTagCompound quest, String location) {
        int highType = NbtCompat.getTagId(quest, "questIDHigh");
        int lowType = NbtCompat.getTagId(quest, "questIDLow");
        if (highType != 0 || lowType != 0) {
            if (highType != 4 || lowType != 4) {
                return QuestIdScan.invalid(location
                    + " quest ID must use long questIDHigh and questIDLow fields");
            }
            return QuestIdScan.valid(UuidValueType.QUEST.readId(quest));
        }
        if (NbtCompat.getTagId(quest, "questID") != 3) {
            return QuestIdScan.invalid(location + " legacy quest ID must use an int field");
        }
        try {
            return QuestIdScan.valid(UuidConverter.convertLegacyId(quest.getInteger("questID")));
        } catch (IllegalArgumentException invalid) {
            return QuestIdScan.invalid(location + " has an invalid legacy quest ID");
        }
    }

    private static TaskScan scanTasks(NBTTagList tasks, String location) {
        Set<UUID> users = new LinkedHashSet<>();
        List<String> issues = new ArrayList<>();
        boolean nonEmpty = tasks.tagCount() > 0;
        for (int i = 0; i < tasks.tagCount(); i++) {
            NBTTagCompound task = NbtCompat.getCompoundAt(tasks, i);
            String taskLocation = location + "[" + i + "]";
            if (task == null) {
                issues.add(taskLocation + " is not a compound");
                continue;
            }
            boolean hasCompleteUsers = task.hasKey("completeUsers");
            if (hasCompleteUsers && NbtCompat.getTagId(task, "completeUsers") != 9) {
                issues.add(taskLocation + ".completeUsers is not a list");
                continue;
            }
            if (hasCompleteUsers) {
                NBTTagList completeUsers = task.getTagList("completeUsers");
                for (int j = 0; j < completeUsers.tagCount(); j++) {
                    if (completeUsers.tagAt(j).getId() != 8) {
                        issues.add(taskLocation + ".completeUsers[" + j + "] is not a string");
                    } else {
                        addUuid(users, issues, stringValue(completeUsers.tagAt(j)),
                            taskLocation + ".completeUsers[" + j + "]");
                    }
                }
            }
        }
        return new TaskScan(users, issues, nonEmpty);
    }

    private static String stringValue(net.minecraft.NBTBase tag) {
        String value = tag.toString();
        return value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
            ? value.substring(1, value.length() - 1) : value;
    }

    private static void addUuid(Set<UUID> users, List<String> issues, String value, String location) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                issues.add(location + " is not a canonical UUID: " + value);
            } else {
                users.add(uuid);
            }
        } catch (IllegalArgumentException failure) {
            issues.add(location + " is not a UUID: " + value);
        }
    }

    private record UserScan(List<UUID> users, List<UUID> completionUsers,
                            List<QuestRecordId> questIds, List<String> issues,
                            boolean nonEmptyTaskProgress, List<String> emptyCompletionRecords) {
    }

    private record TaskScan(Set<UUID> users, List<String> issues, boolean nonEmpty) {
    }

    private record QuestRecordId(UUID id, String location) {
    }

    private record QuestIdScan(UUID id, String issue) {
        static QuestIdScan valid(UUID id) { return new QuestIdScan(id, null); }
        static QuestIdScan invalid(String issue) { return new QuestIdScan(null, issue); }
    }

    private enum ValidationStatus { ACCEPTED, BLOCKED, INVALID }

    private record ValidationResult(ValidationStatus status, String issue) {
        static ValidationResult accepted() { return new ValidationResult(ValidationStatus.ACCEPTED, null); }
        static ValidationResult blocked(String issue) { return new ValidationResult(ValidationStatus.BLOCKED, issue); }
        static ValidationResult invalid(String issue) { return new ValidationResult(ValidationStatus.INVALID, issue); }
    }

    private record StagedPlayer(UUID uuid, NBTTagCompound root) {
    }

    private record ReadResult(boolean loaded, NBTTagCompound root, String issue, boolean oversized) {
        static ReadResult loaded(NBTTagCompound root) { return new ReadResult(true, root, null, false); }
        static ReadResult missing(String path) { return new ReadResult(false, null, "missing file: " + path, false); }
        static ReadResult failed(String issue) { return new ReadResult(false, null, issue, false); }
        static ReadResult oversized(String issue) { return new ReadResult(false, null, issue, true); }
    }

    public enum LoadStatus { ABSENT, LOADED, QUARANTINED, BLOCKED, OVERSIZED }

    public record LoadReport(LoadStatus status, List<UUID> loadedPlayers, List<String> issues,
                             Optional<LegacyMigrationReport> legacyMigration) {
        public LoadReport(LoadStatus status, List<UUID> loadedPlayers, List<String> issues) {
            this(status, loadedPlayers, issues, Optional.empty());
        }

        public LoadReport {
            loadedPlayers = List.copyOf(loadedPlayers);
            issues = List.copyOf(issues);
            legacyMigration = Objects.requireNonNull(legacyMigration, "legacyMigration");
        }
    }

    public enum MigrationStatus { ABSENT, BLOCKED, QUARANTINED, OVERSIZED }

    public record LegacyMigrationReport(MigrationStatus status, List<UUID> discoveredUuids,
                                        Optional<java.nio.file.Path> backupPath,
                                        boolean sourcePreserved, List<String> issues) {
        public LegacyMigrationReport {
            discoveredUuids = List.copyOf(discoveredUuids);
            backupPath = Objects.requireNonNull(backupPath, "backupPath");
            issues = List.copyOf(issues);
        }
    }
}
