package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.storage.AtomicFileStorage;
import com.github.postyizhan.betterquesting.core.storage.WorldDataStorage;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.ISaveHandler;
import net.minecraft.SaveHandler;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;

/**
 * World-bound storage adapter. Instances must be created after world load and discarded on world
 * unload; caching one in a static field would retain the previous integrated-server save path.
 * Resolving before {@code worldServers} is assigned permanently produces a disabled instance, so
 * lifecycle wiring must resolve later than world loading.
 */
public final class MiteWorldStorage implements WorldStorage {
    private static final String DATA_DIRECTORY_NAME = "betterquesting";

    private final WorldDataStorage storage;
    private final Path dataDirectory;
    private final String disabledReason;

    private MiteWorldStorage(AtomicFileStorage files, Path dataDirectory, String disabledReason) {
        this.dataDirectory = dataDirectory;
        this.disabledReason = disabledReason;
        this.storage = dataDirectory == null ? null : new WorldDataStorage(dataDirectory, files);
    }

    public static MiteWorldStorage resolve() {
        return resolve(MinecraftServer.getServer());
    }

    public static MiteWorldStorage resolve(MinecraftServer server) {
        AtomicFileStorage files = new AtomicFileStorage();
        if (server == null) {
            return disabled(files, "MinecraftServer instance is unavailable");
        }
        WorldServer[] worlds = server.worldServers;
        if (worlds == null || worlds.length == 0 || worlds[0] == null) {
            return disabled(files, "overworld is unavailable because worldServers[0] is missing");
        }

        ISaveHandler handler = worlds[0].getSaveHandler();
        if (handler == null) {
            return disabled(files, "overworld save handler is null");
        }
        if (!(handler instanceof SaveHandler)) {
            return disabled(files, "unsupported save handler implementation: " + handler.getClass().getName());
        }

        SaveHandler concreteHandler = (SaveHandler) handler;
        File worldDirectoryFile = concreteHandler.getWorldDirectory();
        if (worldDirectoryFile == null) {
            return disabled(files, "save handler returned a null world directory");
        }
        Path worldDirectory = worldDirectoryFile.toPath().toAbsolutePath().normalize();
        return new MiteWorldStorage(files, worldDirectory.resolve(DATA_DIRECTORY_NAME), null);
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
        return storage().read(relativePath, reader::read);
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
        storage().writeAtomically(relativePath, writer::write);
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

    private WorldDataStorage storage() throws IOException {
        requireAvailable();
        return storage;
    }

    private void requireAvailable() throws IOException {
        if (!isAvailable()) {
            throw new IOException("BetterQuesting world storage is disabled: " + disabledReason);
        }
    }
}
