package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ModuleCommandDeclarationTest {

    private static final String HOST = "HostPlugin";

    private Plugin plugin;
    private ModuleLoader loader;
    private Commands commands;
    private CommandSourceStack source;
    private final List<String> unavailableFor = new ArrayList<>();
    private final ModuleUnavailableHandler unavailable = (src, name) -> this.unavailableFor.add(name);

    /** A module that declares commands through a callback and can fail to initialize. */
    private static final class CommandModule implements PluginModule<Plugin>, CommandDeclaringModule {
        private final BiConsumer<ModuleCommandRoot, ModuleCommandRegistrar> declare;
        private RuntimeException initFailure;
        int declareCalls;
        boolean initialized;

        CommandModule(BiConsumer<ModuleCommandRoot, ModuleCommandRegistrar> declare) {
            this.declare = declare;
        }

        @Override
        public void declareCommands(@NotNull ModuleCommandRoot root, @NotNull ModuleCommandRegistrar registrar) {
            this.declareCalls++;
            this.declare.accept(root, registrar);
        }

        @Override
        public void initialize(@NotNull Plugin plugin, @NotNull Path dataFolder) {
            if (this.initFailure != null) {
                throw this.initFailure;
            }
            this.initialized = true;
        }

        @Override
        public void shutdown(@NotNull Plugin plugin) {
        }
    }

    @BeforeEach
    void setUp() {
        this.plugin = mock(Plugin.class);
        this.loader = mock(ModuleLoader.class);
        this.commands = mock(Commands.class);
        this.source = mock(CommandSourceStack.class);
    }

    private ModuleLifecycleManager<Plugin> manager(CommandModule... modules) throws Exception {
        List<LoadedModule<Plugin>> loaded = new ArrayList<>();
        for (int i = 0; i < modules.length; i++) {
            String name = "m" + i;
            ModuleManifest manifest = new ModuleManifest(name, "Entry", "author", HOST, false);
            loaded.add(new LoadedModule<>(manifest, modules[i], Path.of(name), new URLClassLoader(new URL[0])));
        }
        doReturn(loaded).when(this.loader).loadModulesFromDisk(anyString(), any(Logger.class));
        return new ModuleLifecycleManager<>(this.plugin, this.loader, HOST, Logger.getLogger("mcd-test"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ping(List<String> ran) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("ping").executes(ctx -> {
            ran.add("ping");
            return 1;
        });
    }

    /** Runs one commands event and returns a dispatcher holding the host root as "host". */
    private CommandDispatcher<CommandSourceStack> declare(ModuleLifecycleManager<Plugin> manager) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.literal("host");
        manager.declareCommands(this.commands, root, this.unavailable);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(root.build());
        return dispatcher;
    }

    @Test
    @DisplayName("a subcommand runs module code once the module is active")
    void subcommandRunsWhenActive() throws Exception {
        List<String> ran = new ArrayList<>();
        ModuleLifecycleManager<Plugin> manager = manager(new CommandModule((root, reg) -> root.then(ping(ran))));
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);
        manager.initializeLoaded();

        assertEquals(1, dispatcher.execute("host ping", this.source));
        assertEquals(List.of("ping"), ran);
        assertTrue(this.unavailableFor.isEmpty());
    }

    @Test
    @DisplayName("commands are declared before initialize and reply unavailable until then")
    void unavailableBeforeInitialize() throws Exception {
        List<String> ran = new ArrayList<>();
        CommandModule module = new CommandModule((root, reg) -> root.then(ping(ran)));
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);

        assertFalse(module.initialized);
        assertEquals(0, dispatcher.execute("host ping", this.source));
        assertTrue(ran.isEmpty());
        assertEquals(List.of("m0"), this.unavailableFor);
    }

    @Test
    @DisplayName("a module that fails to initialize keeps its commands, which reply unavailable")
    void failedInitializeIsUnavailable() throws Exception {
        List<String> ran = new ArrayList<>();
        CommandModule module = new CommandModule((root, reg) -> root.then(ping(ran)));
        module.initFailure = new RuntimeException("boom");
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);
        manager.initializeLoaded();

        assertEquals(0, dispatcher.execute("host ping", this.source));
        assertTrue(ran.isEmpty());
        assertEquals(List.of("m0"), this.unavailableFor);
    }

    @Test
    @DisplayName("commands reply unavailable after stop()")
    void unavailableAfterStop() throws Exception {
        List<String> ran = new ArrayList<>();
        ModuleLifecycleManager<Plugin> manager = manager(new CommandModule((root, reg) -> root.then(ping(ran))));
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);
        manager.initializeLoaded();
        manager.stop();

        assertEquals(0, dispatcher.execute("host ping", this.source));
        assertTrue(ran.isEmpty());
    }

    @Test
    @DisplayName("nested executors are guarded too")
    void nestedExecutorsGuarded() throws Exception {
        List<String> ran = new ArrayList<>();
        CommandModule module = new CommandModule((root, reg) -> root.then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("home").then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    ran.add(StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                }))));
        module.initFailure = new RuntimeException("boom");
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);
        manager.initializeLoaded();

        assertEquals(0, dispatcher.execute("host home base", this.source));
        assertTrue(ran.isEmpty());
    }

    @Test
    @DisplayName("suggestions come from the module only while it is active")
    void suggestionsGuarded() throws Exception {
        CommandModule module = new CommandModule((root, reg) -> root.then(
                LiteralArgumentBuilder.<CommandSourceStack>literal("home").then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> builder.suggest("base").buildFuture())
                                .executes(ctx -> 1))));
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);

        Suggestions before = dispatcher.getCompletionSuggestions(dispatcher.parse("host home ", this.source)).get();
        manager.initializeLoaded();
        Suggestions after = dispatcher.getCompletionSuggestions(dispatcher.parse("host home ", this.source)).get();

        assertTrue(before.isEmpty());
        assertEquals("base", after.getList().getFirst().getText());
    }

    @Test
    @DisplayName("top-level commands go to Paper's registrar guarded")
    @SuppressWarnings("unchecked")
    void topLevelRegisteredGuarded() throws Exception {
        List<String> ran = new ArrayList<>();
        CommandModule module = new CommandModule((root, reg) -> reg.register(ping(ran), "Pings", List.of("p")));
        module.initFailure = new RuntimeException("boom");
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        declare(manager);
        manager.initializeLoaded();

        ArgumentCaptor<LiteralCommandNode<CommandSourceStack>> node = ArgumentCaptor.forClass(LiteralCommandNode.class);
        verify(this.commands).register(node.capture(), eq("Pings"), eq(List.of("p")));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(node.getValue());

        assertEquals(0, dispatcher.execute("ping", this.source));
        assertTrue(ran.isEmpty());
    }

    @Test
    @DisplayName("a module whose declareCommands throws registers nothing and is not initialized")
    void declareFailureUnloads() throws Exception {
        List<String> ran = new ArrayList<>();
        CommandModule module = new CommandModule((root, reg) -> {
            root.then(ping(ran));
            throw new IllegalStateException("bad declaration");
        });
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        CommandDispatcher<CommandSourceStack> dispatcher = declare(manager);
        manager.initializeLoaded();

        assertFalse(module.initialized);
        assertTrue(manager.getActiveModules().isEmpty());
        assertTrue(dispatcher.getRoot().getChild("host").getChildren().isEmpty());
    }

    @Test
    @DisplayName("a redirect in a module command is rejected as a failed declaration")
    void redirectRejected() throws Exception {
        CommandModule module = new CommandModule((root, reg) -> {
            LiteralCommandNode<CommandSourceStack> target = LiteralArgumentBuilder.<CommandSourceStack>literal("t").build();
            root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("alias").redirect(target));
        });
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        declare(manager);
        manager.initializeLoaded();

        assertFalse(module.initialized);
    }

    @Test
    @DisplayName("a later commands event re-registers the same commands without asking the module again")
    @SuppressWarnings("unchecked")
    void laterEventReplays() throws Exception {
        List<String> ran = new ArrayList<>();
        CommandModule module = new CommandModule((root, reg) -> {
            root.then(ping(ran));
            reg.register(ping(ran), null);
        });
        ModuleLifecycleManager<Plugin> manager = manager(module);
        manager.load();
        declare(manager);
        manager.initializeLoaded();
        CommandDispatcher<CommandSourceStack> second = declare(manager);

        assertEquals(1, module.declareCalls);
        assertEquals(1, second.execute("host ping", this.source));
        verify(this.commands, times(2)).register(any(LiteralCommandNode.class), eq(null), any(Collection.class));
    }
}
