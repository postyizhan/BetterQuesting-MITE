package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.postyizhan.betterquesting.storage.QuestLootPersistence;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiteWorldStoragePathTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void productionMappingsKeepQuestLootAtWorldRootAndDatabasesInBetterQuesting() {
        Path suppliedWorld = temporaryDirectory.resolve("saves/../saves/World One");
        Path worldRoot = suppliedWorld.toAbsolutePath().normalize();

        Path questLootRoot = MiteWorldStorage.questLootRoot(suppliedWorld);
        Path dataDirectory = MiteWorldStorage.betterQuestingDirectory(suppliedWorld);

        assertEquals(worldRoot, questLootRoot);
        assertEquals(worldRoot.resolve("betterquesting"), dataDirectory);
        assertEquals(worldRoot.resolve(QuestLootPersistence.PATH),
            questLootRoot.resolve(QuestLootPersistence.PATH));
        assertEquals(worldRoot.resolve("betterquesting/QuestDatabase.json"),
            dataDirectory.resolve("QuestDatabase.json"));
    }

    @Test
    void productionAdapterTraversesWorldServersAndMappedSaveHandlerMembers() throws IOException {
        Path suppliedWorld = temporaryDirectory.resolve("saves/../saves/World One");
        FakeSaveHandler saveHandler = new FakeSaveHandler(suppliedWorld.toFile());
        FakeServer server = new FakeServer(new FakeWorld(saveHandler));
        List<String> calls = new ArrayList<>();
        MiteWorldStorage.ProductionWorldDirectoryAccess<FakeServer, FakeWorld, FakeSaveHandler>
            access = new MiteWorldStorage.ProductionWorldDirectoryAccess<>(
                current -> {
                    calls.add("worldServers");
                    return current.worldServers;
                },
                world -> {
                    calls.add("getSaveHandler");
                    return world.getSaveHandler();
                },
                handler -> {
                    calls.add("isSaveHandler");
                    return true;
                },
                handler -> handler.getClass().getName(),
                handler -> {
                    calls.add("getWorldDirectory");
                    return handler.getWorldDirectory();
                });

        Path resolved = MiteWorldStorage.resolveQuestLootRoot(server, access);

        assertEquals(suppliedWorld.toAbsolutePath().normalize(), resolved);
        assertEquals(List.of("worldServers", "getSaveHandler", "isSaveHandler",
            "getWorldDirectory"), calls);
    }

    @Test
    void runtimeOverloadExecutesMappedMinecraftMembersWithoutGameInitialization() throws Exception {
        Path classes = compileMinecraftTypeDoubles();
        URL productionClasses = MiteWorldStorage.class.getProtectionDomain().getCodeSource()
            .getLocation();
        try (ProductionResolverClassLoader loader = new ProductionResolverClassLoader(
            new URL[] {classes.toUri().toURL(), productionClasses}, getClass().getClassLoader())) {
            Class<?> handlerType = loader.loadClass("net.minecraft.ISaveHandler");
            Class<?> saveHandlerType = loader.loadClass("net.minecraft.SaveHandler");
            Class<?> worldType = loader.loadClass("net.minecraft.WorldServer");
            Class<?> serverType = loader.loadClass("net.minecraft.server.MinecraftServer");
            Class<?> storageType = loader.loadClass(MiteWorldStorage.class.getName());
            File worldDirectory = temporaryDirectory.resolve("runtime-world").toFile();
            Object handler = saveHandlerType.getConstructor(File.class).newInstance(worldDirectory);
            Object world = worldType.getConstructor(handlerType).newInstance(handler);
            Object worlds = Array.newInstance(worldType, 1);
            Array.set(worlds, 0, world);
            Object server = serverType.getConstructor(worlds.getClass()).newInstance(worlds);
            Method resolve = storageType.getDeclaredMethod("resolveQuestLootRoot", serverType);
            resolve.setAccessible(true);

            Path resolved = (Path) resolve.invoke(null, server);

            assertEquals(worldDirectory.toPath().toAbsolutePath().normalize(), resolved);
        }
    }

    private Path compileMinecraftTypeDoubles() throws IOException {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("resolver-sources"));
        Path classes = Files.createDirectories(temporaryDirectory.resolve("resolver-classes"));
        List<Path> sourceFiles = List.of(
            writeSource(sources, "net/minecraft/ISaveHandler.java", """
                package net.minecraft;
                public interface ISaveHandler {}
                """),
            writeSource(sources, "net/minecraft/SaveHandler.java", """
                package net.minecraft;
                import java.io.File;
                public final class SaveHandler implements ISaveHandler {
                    private final File worldDirectory;
                    public SaveHandler(File worldDirectory) { this.worldDirectory = worldDirectory; }
                    public File getWorldDirectory() { return worldDirectory; }
                }
                """),
            writeSource(sources, "net/minecraft/World.java", """
                package net.minecraft;
                public class World {
                    private final ISaveHandler saveHandler;
                    public World(ISaveHandler saveHandler) { this.saveHandler = saveHandler; }
                    public ISaveHandler getSaveHandler() { return saveHandler; }
                }
                """),
            writeSource(sources, "net/minecraft/WorldServer.java", """
                package net.minecraft;
                public final class WorldServer extends World {
                    public WorldServer(ISaveHandler saveHandler) { super(saveHandler); }
                }
                """),
            writeSource(sources, "net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                import net.minecraft.WorldServer;
                public final class MinecraftServer {
                    public WorldServer[] worldServers;
                    public MinecraftServer(WorldServer[] worldServers) {
                        this.worldServers = worldServers;
                    }
                    public static MinecraftServer getServer() { return null; }
                }
                """));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            var units = files.getJavaFileObjectsFromPaths(sourceFiles);
            boolean compiled = compiler.getTask(null, files, null,
                List.of("--release", "17", "-proc:none", "-d", classes.toString()),
                null, units).call();
            assertEquals(true, compiled);
        }
        return classes;
    }

    private static Path writeSource(Path root, String relativePath, String source)
        throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source);
    }

    private static final class ProductionResolverClassLoader extends URLClassLoader {
        private ProductionResolverClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
            if (name.startsWith("net.minecraft.")
                || name.equals(MiteWorldStorage.class.getName())
                || name.startsWith(MiteWorldStorage.class.getName() + "$")) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class FakeServer {
        private final FakeWorld[] worldServers;

        private FakeServer(FakeWorld overworld) {
            worldServers = new FakeWorld[] {overworld};
        }
    }

    private record FakeWorld(FakeSaveHandler saveHandler) {
        FakeSaveHandler getSaveHandler() {
            return saveHandler;
        }
    }

    private record FakeSaveHandler(File worldDirectory) {
        File getWorldDirectory() {
            return worldDirectory;
        }
    }
}
