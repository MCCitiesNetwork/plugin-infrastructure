package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface PluginModule<T extends Plugin> {

    /**
     * Starts this module: reads its configuration and registers its listeners and tasks with the
     * host. Commands are not registered here; see {@link #declareCommands}.
     *
     * <p><strong>Failure is the module's to clean up.</strong> If this method throws, the
     * {@link ModuleLifecycleManager} logs the failure and closes the module's class loader, but it
     * does <em>not</em> call {@link #shutdown}. Anything registered before the failure stays live:
     * a listener keeps firing with classes from a closed class loader, a task keeps running. An
     * implementation must therefore either do all fallible work (reading config, resolving
     * services) before registering anything, or undo what it registered before rethrowing.
     * Commands declared through {@link #declareCommands} are the exception: they stay
     * registered and reply as unavailable, so they need no cleanup.
     *
     * @param plugin     the host plugin
     * @param dataFolder this module's own data folder
     * @throws ModuleInitializationException if the module cannot start; it must have released
     *                                       everything it registered before throwing
     */
    void initialize(@NotNull T plugin, @NotNull Path dataFolder) throws ModuleInitializationException;

    /**
     * Declares this module's Brigadier commands. Override it to add commands; the default declares
     * none.
     *
     * <p>Paper only accepts commands during its lifecycle commands event, which fires before modules
     * initialize, so the {@link ModuleLifecycleManager} calls this from
     * {@link ModuleLifecycleManager#declareCommands}, between {@code load()} and
     * {@code initializeLoaded()}, at most once per load. It must only build command trees: no IO,
     * listeners or tasks, and no state that {@link #initialize} sets up. Executors and suggestion
     * providers may use that state, because they only run while the module is active; otherwise
     * the host's unavailable handler replies. Requirements ({@code requires}) run regardless and must
     * not depend on it. Commands are never unregistered, so they need no cleanup.
     *
     * <p>If this method throws, nothing it declared is registered and the module is unloaded.
     *
     * @param root      subcommands under the host's root command
     * @param registrar top-level commands
     */
    default void declareCommands(@NotNull ModuleCommandRoot root, @NotNull ModuleCommandRegistrar registrar) {
    }

    /**
     * Stops this module and releases everything {@link #initialize} registered.
     *
     * <p>Called once, by {@link ModuleLifecycleManager#stop()}, and only for a module whose
     * {@code initialize} returned normally. It is never called after a failed {@code initialize},
     * so it may assume a fully started module.
     *
     * @param plugin the host plugin
     * @throws ModuleShutdownException if releasing fails; the class loader is closed regardless
     */
    void shutdown(@NotNull T plugin) throws ModuleShutdownException;

    /**
     * Refreshes this module in place — typically re-reading its configuration files — without being
     * unloaded or having its jar reloaded. The module keeps running throughout.
     *
     * <p>The returned future lets the module push any blocking IO (e.g. reading config off disk) onto
     * a background thread and complete once the refresh is applied. Implementations that touch the
     * server API must marshal that part back onto the main thread themselves. The future should
     * complete exceptionally if the refresh fails.
     *
     * <p>The default implementation does nothing and completes immediately.
     *
     * @param plugin the host plugin
     * @return a future completing when the reload has finished
     */
    default @NotNull CompletableFuture<Void> reload(@NotNull T plugin) {
        return CompletableFuture.completedFuture(null);
    }

}
