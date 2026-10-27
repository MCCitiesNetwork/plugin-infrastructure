package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the runtime lifecycle of a host plugin's modules: loading them from disk once, initializing
 * them against the host plugin, shutting them down, and refreshing the config of the ones that opt
 * in via {@link ModuleManifest#reloadable()}. Swapping module code (jars) at runtime is not
 * supported; a changed jar is only picked up by restarting the host plugin.
 *
 * <p><strong>This class is not thread-safe.</strong> Its methods and the state they mutate must only
 * be touched from a single thread — on Bukkit/Paper, the server main thread. This includes calling
 * {@link #reloadAsync(String)}; that method merely delegates to the module's own
 * {@link PluginModule#reload(Plugin)}, and any off-main-thread work is the module's responsibility.
 *
 * @param <T> the host plugin type passed to each module
 */
public class ModuleLifecycleManager<T extends Plugin> {

    private final T plugin;
    private final ModuleLoader moduleLoader;
    private final String expectedPluginClass;
    private final Logger logger;

    /**
     * Active modules keyed by {@link ModuleManifest#moduleName()}, in initialization order.
     */
    private final Map<String, LoadedModule<T>> activeModules = new LinkedHashMap<>();

    /**
     * Modules returned by {@link #load()} and not yet passed to {@link #initializeLoaded()}, or
     * {@code null} when nothing is waiting.
     */
    private List<LoadedModule<T>> loadedModules;

    /**
     * Creates a manager that expects modules targeting the host plugin's own class name and logs
     * through the plugin's logger.
     */
    public ModuleLifecycleManager(@NotNull T plugin, @NotNull ModuleLoader moduleLoader) {
        this(plugin, moduleLoader, plugin.getClass().getName(), plugin.getLogger());
    }

    public ModuleLifecycleManager(
            @NotNull T plugin,
            @NotNull ModuleLoader moduleLoader,
            @NotNull String expectedPluginClass,
            @NotNull Logger logger
    ) {
        this.plugin = plugin;
        this.moduleLoader = moduleLoader;
        this.expectedPluginClass = expectedPluginClass;
        this.logger = logger;
    }

    /**
     * Loads every module from disk and initializes it against the host plugin. Equivalent to
     * {@link #load()} followed by {@link #initializeLoaded()}.
     *
     * @throws IllegalStateException if modules are already loaded or active; call {@link #stop()} first
     * @throws IOException           if the module directory cannot be scanned
     */
    public void start() throws IOException {
        load();
        initializeLoaded();
    }

    /**
     * Loads every module from disk without initializing any of them. The host can then inspect the
     * returned modules, for example to let them declare commands while the server still accepts
     * registrations, before calling {@link #initializeLoaded()}.
     *
     * <p>A loaded module's classes are live but {@link PluginModule#initialize} has not run, so the
     * host must not expect any module state yet.
     *
     * @return the loaded modules, in load order
     * @throws IllegalStateException if modules are already loaded or active; call {@link #stop()} first
     * @throws IOException           if the module directory cannot be scanned
     */
    public @NotNull List<LoadedModule<T>> load() throws IOException {
        if (this.loadedModules != null || !this.activeModules.isEmpty()) {
            throw new IllegalStateException("Modules already loaded; call stop() first");
        }
        List<LoadedModule<T>> loaded = List.copyOf(
                this.moduleLoader.loadModulesFromDisk(this.expectedPluginClass, this.logger));
        this.loadedModules = loaded;
        return loaded;
    }

    /**
     * Initializes the modules returned by the last {@link #load()}. A module whose
     * {@code initialize} throws is unloaded and is not active afterwards; see
     * {@link PluginModule#initialize} for its cleanup contract.
     *
     * @throws IllegalStateException if nothing is loaded
     */
    public void initializeLoaded() {
        List<LoadedModule<T>> loaded = this.loadedModules;
        if (loaded == null) {
            throw new IllegalStateException("No modules loaded; call load() first");
        }
        this.loadedModules = null;
        for (LoadedModule<T> module : loaded) {
            initialize(module);
        }
    }

    /**
     * Shuts down every active module in reverse initialization order and releases their class
     * loaders. Modules that were loaded but never initialized are closed without
     * {@link PluginModule#shutdown}. Safe to call when nothing is loaded or active.
     */
    public void stop() {
        List<LoadedModule<T>> pending = this.loadedModules;
        if (pending != null) {
            this.loadedModules = null;
            pending.forEach(this::closeQuietly);
        }
        List<LoadedModule<T>> ordered = new ArrayList<>(this.activeModules.values());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            shutdown(ordered.get(i));
        }
        this.activeModules.clear();
    }

    /**
     * Asks a single active module to refresh itself in place via {@link PluginModule#reload(Plugin)}.
     * The jar and class loader are left untouched and the module keeps running; this is intended for
     * re-reading configuration files. Swapping module code at runtime is not supported — restart the
     * host plugin to pick up a changed jar.
     *
     * <p>Must be called from the main thread. Any background IO is the module's own concern, so this
     * returns immediately with the module's future.
     *
     * @param moduleName the {@link ModuleManifest#moduleName()} of the module to reload
     * @return the module's reload future, or a future completed exceptionally with a
     *         {@link ModuleLoadException} if no such module is active or it is not reloadable
     */
    public @NotNull CompletableFuture<Void> reloadAsync(@NotNull String moduleName) {
        LoadedModule<T> existing = this.activeModules.get(moduleName);
        if (existing == null) {
            return CompletableFuture.failedFuture(
                    new ModuleLoadException("No active module named '" + moduleName + "'"));
        }
        if (!existing.manifest().reloadable()) {
            return CompletableFuture.failedFuture(
                    new ModuleLoadException("Module '" + moduleName + "' is not reloadable"));
        }

        CompletableFuture<Void> future = existing.module().reload(this.plugin);
        return future != null ? future : CompletableFuture.completedFuture(null);
    }

    /**
     * An immutable snapshot of the currently active modules keyed by name, in initialization order.
     */
    public @NotNull Map<String, LoadedModule<T>> getActiveModules() {
        return Map.copyOf(this.activeModules);
    }

    private void initialize(@NotNull LoadedModule<T> loaded) {
        String name = loaded.manifest().moduleName();
        if (this.activeModules.containsKey(name)) {
            this.logger.warning("Duplicate module name '" + name + "'; ignoring the additional copy");
            closeQuietly(loaded);
            return;
        }
        try {
            loaded.module().initialize(this.plugin, loaded.dataFolder());
            this.activeModules.put(name, loaded);
            this.logger.info("Initialized module " + name);
        } catch (ModuleInitializationException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Failed to initialize module " + name + "; unloading", e);
            closeQuietly(loaded);
        }
    }

    private void shutdown(@NotNull LoadedModule<T> loaded) {
        String name = loaded.manifest().moduleName();
        try {
            loaded.module().shutdown(this.plugin);
            this.logger.info("Shut down module " + name);
        } catch (ModuleShutdownException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Error shutting down module " + name, e);
        } finally {
            closeQuietly(loaded);
        }
    }

    private void closeQuietly(@NotNull LoadedModule<T> loaded) {
        try {
            loaded.close();
        } catch (IOException e) {
            this.logger.log(Level.WARNING,
                    "Failed to close class loader for module " + loaded.manifest().moduleName(), e);
        }
    }
}
