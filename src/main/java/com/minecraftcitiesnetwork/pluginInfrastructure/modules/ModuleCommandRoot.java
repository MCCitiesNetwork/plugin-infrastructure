package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

/** The host's root command, as offered to a {@link CommandDeclaringModule}. */
public interface ModuleCommandRoot {

    /**
     * Adds a subcommand under the host's root command. The tree is guarded by the module's
     * availability; it must not contain redirects.
     */
    void then(@NotNull LiteralArgumentBuilder<CommandSourceStack> subcommand);
}
