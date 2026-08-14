package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.MalformedJsonDocumentException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded, structural-only handling for the world-root QuestLoot.json. */
public final class QuestLootPersistence {
    public static final String PATH = "QuestLoot.json";
    public static final long MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L;
    static final int MAX_STRUCTURE_DEPTH = 128;
    private static final int MAX_COLLISIONS = 100;
    private static final String CURRENT_FORMAT = "1";
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path worldRoot;
    private final ArtifactWriter artifactWriter;
    private AnalysisResult completed;

    public QuestLootPersistence(Path worldRoot) {
        this(worldRoot, new NioArtifactWriter(Clock.systemUTC(), new NioDurability(),
            new NioArtifactAccess()));
    }

    QuestLootPersistence(Path worldRoot, ArtifactWriter artifactWriter) {
        this.worldRoot = Objects.requireNonNull(worldRoot, "worldRoot")
            .toAbsolutePath().normalize();
        this.artifactWriter = Objects.requireNonNull(artifactWriter, "artifactWriter");
    }

    QuestLootPersistence(Path worldRoot, Clock clock, Durability durability,
        ArtifactAccess access) {
        this(worldRoot, new NioArtifactWriter(clock, durability, access));
    }

    /** Recognizes the envelope without loading, converting, or writing loot semantics. */
    public synchronized AnalysisResult analyze() throws IOException {
        if (completed != null) return completed;
        RootSnapshot root = canonicalRoot();
        Optional<CapturedSource> captured = captureSource(root);
        if (captured.isEmpty()) {
            return complete(Status.ABSENT, Optional.empty(), "QuestLoot.json is absent");
        }

        CapturedSource source = captured.orElseThrow();
        if (source.bytes().length > MAX_DOCUMENT_BYTES) {
            return complete(Status.OVERSIZED, Optional.empty(),
                "document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }

        final JsonObject documentRoot;
        try {
            String document = decodeStrictUtf8(source.bytes());
            documentRoot = JsonDocuments.parseBoundedObject(document, MAX_STRUCTURE_DEPTH);
        } catch (MalformedJsonDocumentException malformed) {
            return quarantine(source, malformed.getMessage());
        }

        JsonElement version = documentRoot.get("mitePortFormat:8");
        if (version != null) {
            if (hasTypeConflict(documentRoot, "mitePortFormat", "mitePortFormat:8")) {
                return quarantine(source, "mitePortFormat has an unsupported structural type");
            }
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString()) {
                return quarantine(source, "mitePortFormat:8 must be a string");
            }
            String value = version.getAsString();
            if (CURRENT_FORMAT.equals(value)) {
                return blocked(source,
                    "current mitePortFormat recognized; LootRegistry semantics are deferred to stage 7");
            }
            if (isCanonicalPositiveInteger(value)) {
                return blocked(source, "unsupported future mitePortFormat: " + value);
            }
            return quarantine(source,
                "mitePortFormat:8 must be a canonical positive integer string");
        }
        if (hasTypeConflict(documentRoot, "mitePortFormat", "mitePortFormat:8")) {
            return quarantine(source, "mitePortFormat has an unsupported structural type");
        }
        if (hasTypeConflict(documentRoot, "groups", "groups:9")) {
            return quarantine(source, "groups has an unsupported structural type");
        }
        JsonElement groups = documentRoot.get("groups:9");
        if (groups == null || !groups.isJsonObject()) {
            return quarantine(source, "expected groups:9 object at document root");
        }
        return blocked(source,
            "legacy QuestLoot envelope recognized; LootRegistry semantics are deferred to stage 7");
    }

    private RootSnapshot canonicalRoot() throws IOException {
        BasicFileAttributes supplied = readAttributes(worldRoot);
        requireDirectory(worldRoot, supplied);
        Path canonical = worldRoot.toRealPath();
        BasicFileAttributes canonicalAttributes = readAttributes(canonical);
        requireDirectory(canonical, canonicalAttributes);
        Path source = canonical.resolve(PATH).normalize();
        if (!canonical.equals(source.getParent())) {
            throw new IOException("QuestLoot source escapes canonical world root: " + source);
        }
        return new RootSnapshot(canonical, canonicalAttributes.fileKey());
    }

    private Optional<CapturedSource> captureSource(RootSnapshot root) throws IOException {
        root.revalidate();
        Path source = root.path().resolve(PATH);
        final BasicFileAttributes before;
        try {
            before = readAttributes(source);
        } catch (NoSuchFileException missing) {
            root.revalidate();
            return Optional.empty();
        }
        requireRegularSource(source, before);

        byte[] bytes;
        try (SeekableByteChannel channel = openSource(source, before, root);
             InputStream input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes((int) MAX_DOCUMENT_BYTES + 1);
        }

        BasicFileAttributes after = readAttributes(source);
        requireRegularSource(source, after);
        requireSameSource(source, before, after);
        root.revalidate();
        return Optional.of(new CapturedSource(root.path(), bytes));
    }

    private static SeekableByteChannel openSource(Path source, BasicFileAttributes before,
        RootSnapshot root) throws IOException {
        try {
            return Files.newByteChannel(source,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            root.revalidate();
            BasicFileAttributes rechecked = readAttributes(source);
            requireRegularSource(source, rechecked);
            requireSameSource(source, before, rechecked);
            return Files.newByteChannel(source, StandardOpenOption.READ);
        }
    }

    private AnalysisResult blocked(CapturedSource source, String detail) throws IOException {
        Path backup = artifactWriter.write(source.root(), "recognized", source.bytes());
        return complete(Status.BLOCKED, Optional.of(backup), detail);
    }

    private AnalysisResult quarantine(CapturedSource source, String detail) throws IOException {
        Path evidence = artifactWriter.write(source.root(), "corrupt", source.bytes());
        return complete(Status.QUARANTINED, Optional.of(evidence), detail);
    }

    private AnalysisResult complete(Status status, Optional<Path> artifact, String detail) {
        completed = new AnalysisResult(status, artifact, detail);
        return completed;
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireDirectory(Path path, BasicFileAttributes attributes)
        throws IOException {
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("QuestLoot world root is not a non-symlink directory: " + path);
        }
    }

    private static void requireRegularSource(Path path, BasicFileAttributes attributes)
        throws IOException {
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException(
                "QuestLoot source is not a regular non-symlink world-root file: " + path);
        }
    }

    private static void requireSameSource(Path path, BasicFileAttributes before,
        BasicFileAttributes after) throws IOException {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        boolean changed = beforeKey != null && afterKey != null && !beforeKey.equals(afterKey);
        changed |= before.size() != after.size();
        changed |= !before.lastModifiedTime().equals(after.lastModifiedTime());
        if (changed) throw new IOException("QuestLoot source changed during capture: " + path);
    }

    private static String decodeStrictUtf8(byte[] bytes) throws MalformedJsonDocumentException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new MalformedJsonDocumentException(
                "QuestLoot.json contains invalid UTF-8", malformed);
        }
    }

    private static boolean isCanonicalPositiveInteger(String value) {
        if (value.isEmpty() || value.charAt(0) < '1' || value.charAt(0) > '9') return false;
        for (int index = 1; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') return false;
        }
        return true;
    }

    private static boolean hasTypeConflict(JsonObject root, String field, String expectedKey) {
        for (var entry : root.entrySet()) {
            String key = entry.getKey();
            if (!expectedKey.equals(key) && (field.equals(key) || key.startsWith(field + ":"))) {
                return true;
            }
        }
        return false;
    }

    private record CapturedSource(Path root, byte[] bytes) {
    }

    private record RootSnapshot(Path path, Object fileKey) {
        void revalidate() throws IOException {
            BasicFileAttributes attributes = readAttributes(path);
            requireDirectory(path, attributes);
            if (fileKey != null && attributes.fileKey() != null
                && !fileKey.equals(attributes.fileKey())) {
                throw new IOException("QuestLoot canonical world root identity changed: " + path);
            }
        }
    }

    interface ArtifactWriter {
        Path write(Path directory, String classification, byte[] bytes) throws IOException;
    }

    interface Durability {
        void syncFile(FileChannel channel) throws IOException;

        void syncDirectory(Path directory) throws IOException;
    }

    interface ArtifactAccess {
        FileChannel createNew(Path path) throws IOException;

        int write(FileChannel channel, ByteBuffer buffer) throws IOException;

        void deleteIfExists(Path path) throws IOException;
    }

    /**
     * Reserves the final collision-safe name with {@code CREATE_NEW}, then writes and forces the
     * captured bytes through that channel. The final path can be visible while it is being written;
     * success is reported only after the file and, where supported, its parent directory are
     * synced. Ordinary failures remove the partial file, while abrupt termination can leave a
     * partial target that a later retry treats as a collision.
     */
    static final class NioArtifactWriter implements ArtifactWriter {
        private final Clock clock;
        private final Durability durability;
        private final ArtifactAccess access;

        NioArtifactWriter(Clock clock, Durability durability, ArtifactAccess access) {
            this.clock = Objects.requireNonNull(clock, "clock");
            this.durability = Objects.requireNonNull(durability, "durability");
            this.access = Objects.requireNonNull(access, "access");
        }

        @Override
        public Path write(Path directory, String classification, byte[] bytes)
            throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            String timestamp = TIMESTAMP.format(clock.instant());
            String finalSuffix = "recognized".equals(classification)
                ? ".recognized.bak" : ".corrupt.evidence";
            CreatedFile created = null;
            try {
                created = createTarget(directory, timestamp, finalSuffix);
                try (FileChannel channel = created.channel()) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) access.write(channel, buffer);
                    durability.syncFile(channel);
                }
                durability.syncDirectory(directory);
                return created.path();
            } catch (IOException | RuntimeException failure) {
                cleanupOne(created == null ? null : created.path(), failure);
                throw failure;
            }
        }

        private CreatedFile createTarget(Path directory, String timestamp, String finalSuffix)
            throws IOException {
            for (int suffix = 0; suffix < MAX_COLLISIONS; suffix++) {
                String collision = suffix == 0 ? "" : "-" + suffix;
                Path candidate = contained(directory,
                    PATH + "." + timestamp + collision + finalSuffix);
                try {
                    return new CreatedFile(candidate, access.createNew(candidate));
                } catch (FileAlreadyExistsException ignored) {
                }
            }
            throw new IOException("Unable to allocate preserved copy for " + PATH
                + " after " + MAX_COLLISIONS + " collisions");
        }

        private void cleanupOne(Path path, Throwable failure) {
            if (path == null) return;
            try {
                access.deleteIfExists(path);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private static Path contained(Path directory, String name) throws IOException {
            Path candidate = directory.resolve(name).normalize();
            if (!directory.equals(candidate.getParent())) {
                throw new IOException("Preserved QuestLoot copy escapes world root: " + candidate);
            }
            return candidate;
        }
    }

    private record CreatedFile(Path path, FileChannel channel) {
    }

    static final class NioArtifactAccess implements ArtifactAccess {
        @Override
        public FileChannel createNew(Path path) throws IOException {
            return FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        }

        @Override
        public int write(FileChannel channel, ByteBuffer buffer) throws IOException {
            return channel.write(buffer);
        }

        @Override
        public void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }

    static final class NioDurability implements Durability {
        @Override
        public void syncFile(FileChannel channel) throws IOException {
            channel.force(true);
        }

        @Override
        public void syncDirectory(Path directory) throws IOException {
            if (File.separatorChar == '\\') return;
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    public enum Status { ABSENT, BLOCKED, QUARANTINED, OVERSIZED }

    public record AnalysisResult(Status status, Optional<Path> artifactPath, String detail) {
        public AnalysisResult {
            Objects.requireNonNull(status, "status");
            artifactPath = Objects.requireNonNull(artifactPath, "artifactPath");
            detail = Objects.requireNonNull(detail, "detail");
            boolean needsCopy = status == Status.BLOCKED || status == Status.QUARANTINED;
            if (needsCopy != artifactPath.isPresent()) {
                throw new IllegalArgumentException(
                    "BLOCKED and QUARANTINED results require a preserved copy");
            }
        }

        public Optional<Path> backupPath() {
            return status == Status.BLOCKED ? artifactPath : Optional.empty();
        }

        public Optional<Path> evidencePath() {
            return status == Status.QUARANTINED ? artifactPath : Optional.empty();
        }

        public boolean sourcePreserved() {
            return true;
        }

        public boolean writesDisabled() {
            return true;
        }
    }
}
