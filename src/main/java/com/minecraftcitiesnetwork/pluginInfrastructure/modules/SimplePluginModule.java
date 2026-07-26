package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;

public abstract class SimplePluginModule<T extends Plugin> implements PluginModule<T> {

    private final Collection<Listener> listeners = new ArrayList<>();
    private T plugin;

    protected T plugin() {
        if (this.plugin == null) {
            throw new IllegalStateException("Module not yet initialized!");
        }
        return this.plugin;
    }

    @Override
    public void initialize(@NotNull T plugin) {
        this.plugin = plugin;
    }

    @Override
    public void shutdown(@NotNull T plugin) {
        this.plugin = null;
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
