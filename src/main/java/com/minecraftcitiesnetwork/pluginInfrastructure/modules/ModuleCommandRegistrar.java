package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Registers top-level commands for a {@link CommandDeclaringModule}. Each tree is guarded by the
 * module's availability before it reaches Paper's registrar, so a module cannot register an
 * unguarded command.
 */
public interface ModuleCommandRegistrar {

    /**
     * Registers a top-level command. The tree must not contain redirects.
     *
     * @param command     the command tree
     * @param description the help description, or {@code null}
     * @param aliases     extra labels
     */
    void register(@NotNull LiteralArgumentBuilder<CommandSourceStack> command,
                  @Nullable String description, @NotNull Collection<String> aliases);

    default void register(@NotNull LiteralArgumentBuilder<CommandSourceStack> command,
                          @Nullable String description) {
        register(command, description, List.of());
    }
}
