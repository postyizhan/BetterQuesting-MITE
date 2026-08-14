package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.storage.AtomicFileStorage;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.ISaveHandler;
import net.minecraft.SaveHandler;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * World-bound storage adapter. Instances must be created after world load and discarded on world
 * unload; caching one in a static field would retain the previous integrated-server save path.
 * Resolving before {@code worldServers} is assigned permanently produces a disabled instance, so
 * lifecycle wiring must resolve later than world loading.
 *
 * <p>Every method must be invoked on the server main thread, or callers must guarantee that the
 * same relative path is never accessed concurrently. Atomic writes share a fixed
 * {@code <target>.tmp} sibling, and append writes rely on a last-byte guard plus truncate rollback.
 * Upstream serialized these operations through BQThreadedIO; this port intentionally has no such
 * queue, so violating this constraint can corrupt either protocol.
 */
public final class MiteWorldStorage implements WorldStorage {
    private static final String DATA_DIRECTORY_NAME = "betterquesting";
    private static final WorldDirectoryAccess<MinecraftServer, WorldServer, ISaveHandler>
        MINECRAFT_WORLD_DIRECTORY_ACCESS = new ProductionWorldDirectoryAccess<>(
            server -> server.worldServers,
            WorldServer::getSaveHandler,
            SaveHandler.class::isInstance,
            handler -> handler.getClass().getName(),
            handler -> ((SaveHandler) handler).getWorldDirectory());

    private final DirectoryWorldStorage storage;
    private final Path dataDirectory;
    private final String disabledReason;

    private MiteWorldStorage(AtomicFileStorage files, Path dataDirectory, String disabledReason) {
        this.dataDirectory = dataDirectory;
        this.disabledReason = disabledReason;
        this.storage = dataDirectory == null ? null : new DirectoryWorldStorage(dataDirectory, files);
    }

    public static MiteWorldStorage resolve() {
        return resolve(MinecraftServer.getServer());
    }

    public static MiteWorldStorage resolve(MinecraftServer server) {
        AtomicFileStorage files = new AtomicFileStorage();
        try {
            return new MiteWorldStorage(files,
                betterQuestingDirectory(resolveWorldDirectory(server)), null);
        } catch (IOException unavailable) {
            return disabled(files, unavailable.getMessage());
        }
    }

    static Path resolveQuestLootRoot(MinecraftServer server) throws IOException {
        return resolveQuestLootRoot(server, MINECRAFT_WORLD_DIRECTORY_ACCESS);
    }

    static <S, W, H> Path resolveQuestLootRoot(S server,
        WorldDirectoryAccess<S, W, H> access) throws IOException {
        return questLootRoot(resolveWorldDirectory(server, access));
    }

    static Path betterQuestingDirectory(Path worldDirectory) {
        return questLootRoot(worldDirectory).resolve(DATA_DIRECTORY_NAME);
    }

    static Path questLootRoot(Path worldDirectory) {
        return worldDirectory.toAbsolutePath().normalize();
    }

    private static Path resolveWorldDirectory(MinecraftServer server) throws IOException {
        return resolveWorldDirectory(server, MINECRAFT_WORLD_DIRECTORY_ACCESS);
    }

    private static <S, W, H> Path resolveWorldDirectory(S server,
        WorldDirectoryAccess<S, W, H> access) throws IOException {
        if (server == null) {
            throw new IOException("MinecraftServer instance is unavailable");
        }
        W overworld = access.worldServer(server, 0);
        if (overworld == null) {
            throw new IOException("overworld is unavailable because worldServers[0] is missing");
        }

        H handler = access.saveHandler(overworld);
        if (handler == null) {
            throw new IOException("overworld save handler is null");
        }
        if (!access.isSaveHandler(handler)) {
            throw new IOException(
                "unsupported save handler implementation: " + access.saveHandlerType(handler));
        }

        File worldDirectoryFile = access.worldDirectory(handler);
        if (worldDirectoryFile == null) {
            throw new IOException("save handler returned a null world directory");
        }
        return questLootRoot(worldDirectoryFile.toPath());
    }

    interface WorldDirectoryAccess<S, W, H> {
        W worldServer(S server, int index);

        H saveHandler(W world);

        boolean isSaveHandler(H handler);

        String saveHandlerType(H handler);

        File worldDirectory(H handler);
    }

    static final class ProductionWorldDirectoryAccess<S, W, H>
        implements WorldDirectoryAccess<S, W, H> {
        private final Function<S, W[]> worlds;
        private final Function<W, H> saveHandler;
        private final Predicate<H> supportedHandler;
        private final Function<H, String> handlerType;
        private final Function<H, File> worldDirectory;

        ProductionWorldDirectoryAccess(Function<S, W[]> worlds, Function<W, H> saveHandler,
            Predicate<H> supportedHandler, Function<H, String> handlerType,
            Function<H, File> worldDirectory) {
            this.worlds = worlds;
            this.saveHandler = saveHandler;
            this.supportedHandler = supportedHandler;
            this.handlerType = handlerType;
            this.worldDirectory = worldDirectory;
        }

        @Override
        public W worldServer(S server, int index) {
            W[] available = worlds.apply(server);
            return available == null || index < 0 || index >= available.length
                ? null : available[index];
        }

        @Override
        public H saveHandler(W world) {
            return saveHandler.apply(world);
        }

        @Override
        public boolean isSaveHandler(H handler) {
            return supportedHandler.test(handler);
        }

        @Override
        public String saveHandlerType(H handler) {
            return handlerType.apply(handler);
        }

        @Override
        public File worldDirectory(H handler) {
            return worldDirectory.apply(handler);
        }
    }

    private static MiteWorldStorage disabled(AtomicFileStorage files, String reason) {
        BetterQuestingMod.LOGGER.error("BetterQuesting world storage disabled: {}", reason);
        return new MiteWorldStorage(files, null, reason);
    }

    @Override
    public boolean isAvailable() {
        return dataDirectory != null;
    }

    @Override
    public Optional<Path> getDataDirectory() {
        return Optional.ofNullable(dataDirectory);
    }

    @Override
    public Optional<String> getDisabledReason() {
        return Optional.ofNullable(disabledReason);
    }

    @Override
    public boolean exists(String relativePath) throws IOException {
        return storage().exists(relativePath);
    }

    @Override
    public <T> Optional<T> read(String relativePath, InputReader<T> reader) throws IOException {
        return storage().read(relativePath, reader);
    }

    @Override
    public List<String> readLines(String relativePath) throws IOException {
        return storage().readLines(relativePath);
    }

    @Override
    public List<String> list(String relativeDirectory, String suffix) throws IOException {
        return storage().list(relativeDirectory, suffix);
    }

    @Override
    public boolean delete(String relativePath) throws IOException {
        return storage().delete(relativePath);
    }

    @Override
    public void appendLine(String relativePath, String line) throws IOException {
        storage().appendLine(relativePath, line);
    }

    @Override
    public void writeAtomically(String relativePath, OutputWriter writer) throws IOException {
        storage().writeAtomically(relativePath, writer);
    }

    @Override
    public void writeAtomically(String relativePath, OutputWriter writer, ReadbackValidator validator)
        throws IOException {
        storage().writeAtomically(relativePath, writer, validator);
    }

    @Override
    public Optional<Path> backup(String relativePath) throws IOException {
        return storage().backup(relativePath);
    }

    /**
     * Writes are completed synchronously by {@link AtomicFileStorage}, including file-descriptor
     * sync, so there is no pending BetterQuesting queue to flush. If asynchronous IO is introduced,
     * this method must join its queue or server stop can lose data.
     */
    @Override
    public void flush() throws IOException {
        requireAvailable();
    }

    private DirectoryWorldStorage storage() throws IOException {
        requireAvailable();
        return storage;
    }

    private void requireAvailable() throws IOException {
        if (!isAvailable()) {
            throw new IOException("BetterQuesting world storage is disabled: " + disabledReason);
        }
    }
}
