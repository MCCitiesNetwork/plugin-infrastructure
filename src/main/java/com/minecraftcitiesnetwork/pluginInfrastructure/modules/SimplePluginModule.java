package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

/**
 * A {@link PluginModule} base that keeps the host plugin and data folder, and tracks listeners
 * registered through {@link #registerListener}.
 *
 * <p>Tracking is not cleanup: neither this class's {@link #initialize} nor its {@link #shutdown}
 * unregisters anything. Following {@link PluginModule#initialize}'s contract, a subclass whose
 * {@code initialize} fails after {@code registerListener} must call {@link #unregisterListeners()}
 * before rethrowing, and its {@code shutdown} must call {@code unregisterListeners()} before
 * {@code super.shutdown(plugin)}.
 *
 * @param <T> the host plugin type
 */
public abstract class SimplePluginModule<T extends Plugin> implements PluginModule<T> {

    private final Collection<Listener> listeners = new ArrayList<>();
    private T plugin;
    private Path dataFolder;

    protected T plugin() {
        if (this.plugin == null) {
            throw new IllegalStateException("Module not yet initialized!");
        }
        return this.plugin;
    }

    @Override
    public void initialize(@NotNull T plugin, @NotNull Path dataFolder) {
        this.plugin = plugin;
        this.dataFolder = dataFolder;
    }

    @Override
    public void shutdown(@NotNull T plugin) {
        this.plugin = null;
        this.dataFolder = null;
    }

    @NotNull
    protected Path dataFolder() {
        if (this.dataFolder == null) {
            throw new IllegalStateException("Module not initialized!");
        }
        return this.dataFolder;
    }

    protected void registerListener(@NotNull Listener listener) {
        this.listeners.add(listener);
        this.plugin.getServer().getPluginManager().registerEvents(listener, this.plugin);
    }

    protected void unregisterListeners(@NotNull Listener listener) {
        this.listeners.remove(listener);
        HandlerList.unregisterAll(listener);
    }

    protected void unregisterListeners() {
        this.listeners.forEach(HandlerList::unregisterAll);
        this.listeners.clear();
    }
}
