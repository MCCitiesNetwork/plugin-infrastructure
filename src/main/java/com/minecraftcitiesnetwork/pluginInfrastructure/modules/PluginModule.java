package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public interface PluginModule<T extends Plugin> {

    void initialize(@NotNull T plugin) throws ModuleInitializationException;

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
