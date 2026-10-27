package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

/** Replies to a sender who ran a module command while that module is not active. */
@FunctionalInterface
public interface ModuleUnavailableHandler {

    /**
     * @param source     who ran the command
     * @param moduleName the {@link ModuleManifest#moduleName()} of the inactive module
     */
    void handle(@NotNull CommandSourceStack source, @NotNull String moduleName);
}
