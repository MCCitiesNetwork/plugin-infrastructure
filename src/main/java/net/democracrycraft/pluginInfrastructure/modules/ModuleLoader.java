package net.democracrycraft.pluginInfrastructure.modules;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ModuleLoader {

    private final Path moduleDir;
    private final Predicate<Path> filePredicate;

    public ModuleLoader(@NotNull Path moduleDir) {
        this(moduleDir, path -> path.getFileName().toString().endsWith(".jar"));
    }

    public ModuleLoader(@NotNull Path moduleDir, @NotNull Predicate<Path> filePredicate) {
        this.moduleDir = moduleDir;
        this.filePredicate = filePredicate;
    }

    public <T extends Plugin> List<LoadedModule<T>> loadModulesFromDisk(
            @NotNull String expectedPluginClass,
            @NotNull Logger logger
    ) throws IOException {
        List<Path> moduleCandidates;
        try (var paths = Files.walk(moduleDir)) {
            moduleCandidates = paths
                    .filter(this.filePredicate)
                    .toList();
        }

        List<LoadedModule<T>> modules = new ArrayList<>();
        for (Path path : moduleCandidates) {
            Optional<ModuleManifest> maybeManifest = extractManifest(path, logger);
            if (maybeManifest.isEmpty()) {
                continue;
            }

            ModuleManifest manifest = maybeManifest.get();
            if (!manifest.expectedPluginClass().equals(expectedPluginClass)) {
                logger.warning("Skipping module " + manifest.moduleName() + ": expects host plugin "
                        + manifest.expectedPluginClass() + " but found " + expectedPluginClass);
                continue;
            }

            try {
                modules.add(loadModule(path, manifest));
                logger.info("Loaded module " + manifest.moduleName() + " by " + manifest.author());
            } catch (ModuleLoadException e) {
                logger.log(Level.WARNING, "Failed to load module " + manifest.moduleName(), e);
            }
        }
        return modules;
    }

    private <T extends Plugin> LoadedModule<T> loadModule(@NotNull Path path, @NotNull ModuleManifest manifest)
            throws ModuleLoadException {
        URLClassLoader classLoader;
        try {
            classLoader = new URLClassLoader(
                    new URL[]{path.toUri().toURL()},
                    getClass().getClassLoader()
            );
        } catch (MalformedURLException e) {
            throw new ModuleLoadException("Invalid module path: " + path, e);
        }

        boolean loaded = false;
        try {
            Class<?> entryClass = Class.forName(manifest.entryClass(), true, classLoader);
            if (!PluginModule.class.isAssignableFrom(entryClass)) {
                throw new ModuleLoadException("Entry class " + manifest.entryClass()
                        + " in module " + manifest.moduleName() + " does not implement PluginModule");
            }

            Object instance = entryClass.getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            PluginModule<T> module = (PluginModule<T>) instance;
            loaded = true;
            return new LoadedModule<>(manifest, module, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ModuleLoadException("Could not find entry class " + manifest.entryClass()
                    + " in module " + manifest.moduleName(), e);
        } catch (NoSuchMethodException e) {
            throw new ModuleLoadException("Entry class " + manifest.entryClass()
                    + " in module " + manifest.moduleName() + " must have a public no-arg constructor", e);
        } catch (ReflectiveOperationException e) {
            throw new ModuleLoadException("Failed to instantiate entry class " + manifest.entryClass()
                    + " in module " + manifest.moduleName(), e);
        } finally {
            if (!loaded) {
                closeQuietly(classLoader);
            }
        }
    }

    private void closeQuietly(@NotNull URLClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (IOException e) {
            // Best-effort cleanup after a failed load; nothing actionable to do here.
        }
    }

    private Optional<ModuleManifest> extractManifest(@NotNull Path path, @NotNull Logger logger) {
        try (JarFile jarFile = new JarFile(path.toFile())) {
            JarEntry manifestEntry = jarFile.getJarEntry("module-manifest.yml");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                logger.warning("No module-manifest.yml found in " + path.getFileName());
                return Optional.empty();
            }

            String contents;
            try (InputStream in = jarFile.getInputStream(manifestEntry)) {
                contents = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            ModuleManifest manifest = YamlConfigurationLoader.builder()
                    .buildAndLoadString(contents)
                    .get(ModuleManifest.class);
            return Optional.ofNullable(manifest);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to read module-manifest.yml from " + path.getFileName(), e);
            return Optional.empty();
        }
    }
}
