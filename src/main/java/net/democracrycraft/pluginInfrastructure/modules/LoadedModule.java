package net.democracrycraft.pluginInfrastructure.modules;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.net.URLClassLoader;

/**
 * A module that has been loaded from disk, paired with the {@link URLClassLoader} that owns its
 * classes. The class loader is retained so it can be {@link #close() closed} on unload, releasing
 * the underlying jar and allowing reloadable modules to be replaced.
 *
 * @param manifest    the module's parsed manifest
 * @param module      the instantiated module
 * @param classLoader the class loader that owns the module's classes
 */
public record LoadedModule<T extends Plugin>(
        @NotNull ModuleManifest manifest,
        @NotNull PluginModule<T> module,
        @NotNull URLClassLoader classLoader
) implements Closeable {

    @Override
    public void close() throws IOException {
        this.classLoader.close();
    }
}
