package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/**
 * Rebuilds a module's command tree so that every executor and suggestion provider first checks
 * whether the module is active. Brigadier nodes are immutable once built, so the guard is applied
 * by rebuilding each node from {@link CommandNode#createBuilder()}.
 */
final class CommandGuard {

    private CommandGuard() {
    }

    // Rebuilding a literal yields a literal, and an argument node's builder is a RequiredArgumentBuilder.
    @SuppressWarnings("unchecked")
    static @NotNull LiteralCommandNode<CommandSourceStack> guard(
            @NotNull LiteralArgumentBuilder<CommandSourceStack> command,
            @NotNull String moduleName,
            @NotNull BooleanSupplier active,
            @NotNull ModuleUnavailableHandler unavailable
    ) {
        return (LiteralCommandNode<CommandSourceStack>) rebuild(command.build(), moduleName, active, unavailable);
    }

    @SuppressWarnings("unchecked")
    private static @NotNull CommandNode<CommandSourceStack> rebuild(
            @NotNull CommandNode<CommandSourceStack> node,
            @NotNull String moduleName,
            @NotNull BooleanSupplier active,
            @NotNull ModuleUnavailableHandler unavailable
    ) {
        // A redirect points at a node outside this rebuild, which would bypass the guard.
        if (node.getRedirect() != null) {
            throw new IllegalArgumentException("Module '" + moduleName + "' declared a redirect at '"
                    + node.getName() + "'; module commands must not use redirects");
        }
        ArgumentBuilder<CommandSourceStack, ?> builder = node.createBuilder();
        Command<CommandSourceStack> command = node.getCommand();
        if (command != null) {
            builder.executes(ctx -> {
                if (active.getAsBoolean()) {
                    return command.run(ctx);
                }
                unavailable.handle(ctx.getSource(), moduleName);
                return 0;
            });
        }
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argumentNode
                && argumentNode.getCustomSuggestions() != null) {
            SuggestionProvider<CommandSourceStack> suggestions = argumentNode.getCustomSuggestions();
            RequiredArgumentBuilder<CommandSourceStack, ?> argument = (RequiredArgumentBuilder<CommandSourceStack, ?>) builder;
            argument.suggests((ctx, suggestionsBuilder) -> active.getAsBoolean()
                    ? suggestions.getSuggestions(ctx, suggestionsBuilder)
                    : suggestionsBuilder.buildFuture());
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            builder.then(rebuild(child, moduleName, active, unavailable));
        }
        return builder.build();
    }
}
