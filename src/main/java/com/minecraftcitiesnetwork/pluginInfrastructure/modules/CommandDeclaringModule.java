package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.jetbrains.annotations.NotNull;

/**
 * Implemented by a {@link PluginModule} that contributes Brigadier commands.
 *
 * <p>Paper only accepts commands during its lifecycle commands event, which fires before modules
 * initialize. The {@link ModuleLifecycleManager} therefore asks each loaded module to declare its
 * commands from {@link ModuleLifecycleManager#declareCommands}, between {@code load()} and
 * {@code initializeLoaded()}. Every command declared here is guarded: while the module is not
 * active (it failed to initialize, or has been shut down) the command does not run module code and
 * the host's unavailable handler replies instead. Commands are never unregistered.
 */
public interface CommandDeclaringModule {

    /**
     * Declares this module's commands. Called before {@link PluginModule#initialize}, so it must
     * only build command trees: no IO, listeners or tasks, and no state that {@code initialize}
     * sets up. Executors and suggestion providers may use that state, because they only run while
     * the module is active. Requirements ({@code requires}) run regardless and must not depend on it.
     *
     * <p>If this method throws, nothing it declared is registered and the module is unloaded.
     *
     * @param root      subcommands under the host's root command
     * @param registrar top-level commands
     */
    void declareCommands(@NotNull ModuleCommandRoot root, @NotNull ModuleCommandRegistrar registrar);
}
