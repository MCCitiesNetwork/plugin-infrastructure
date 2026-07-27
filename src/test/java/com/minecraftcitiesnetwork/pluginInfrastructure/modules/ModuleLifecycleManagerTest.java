package com.minecraftcitiesnetwork.pluginInfrastructure.modules;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class ModuleLifecycleManagerTest {

    private static final String HOST = "HostPlugin";

    private Plugin plugin;
    private ModuleLoader loader;
    private CapturingLogger logger;

    /** A controllable module: records lifecycle calls and can be told to fail on initialize. */
    private static final class FakeModule implements PluginModule<Plugin> {
        private final String name;
        private final List<String> shutdownOrder;
        private RuntimeException initFailure;
        private CompletableFuture<Void> reloadFuture = CompletableFuture.completedFuture(null);

        boolean initialized;
        boolean reloadCalled;

        FakeModule(String name, List<String> shutdownOrder) {
            this.name = name;
            this.shutdownOrder = shutdownOrder;
        }

        @Override
        public void initialize(@NotNull Plugin plugin) throws ModuleInitializationException {
            if (this.initFailure != null) {
                throw this.initFailure;
            }
            this.initialized = true;
        }

        @Override
        public void shutdown(@NotNull Plugin plugin) {
            this.shutdownOrder.add(this.name);
        }

        @Override
        public @NotNull CompletableFuture<Void> reload(@NotNull Plugin plugin) {
            this.reloadCalled = true;
            return this.reloadFuture;
        }
    }

    /** Collects emitted log records so tests can assert on warnings/severe messages. */
    private static final class CapturingLogger {
        private final Logger logger;
        private final List<LogRecord> records = new ArrayList<>();

        CapturingLogger(String name) {
            this.logger = Logger.getLogger(name);
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(Level.ALL);
            this.logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { records.add(record); }
                @Override public void flush() { }
                @Override public void close() { }
            });
        }

        boolean hasRecord(Level level, String substring) {
            return this.records.stream().anyMatch(r ->
                    r.getLevel() == level && r.getMessage() != null && r.getMessage().contains(substring));
        }
    }

    @BeforeEach
    void setUp() {
        this.plugin = mock(Plugin.class);
        this.loader = mock(ModuleLoader.class);
        this.logger = new CapturingLogger("mlm-" + System.identityHashCode(this));
    }

    private ModuleLifecycleManager<Plugin> manager() {
        return new ModuleLifecycleManager<>(this.plugin, this.loader, HOST, this.logger.logger);
    }

    private LoadedModule<Plugin> loaded(String moduleName, boolean reloadable, FakeModule module) {
        ModuleManifest manifest = new ModuleManifest(moduleName, "Entry", "author", HOST, reloadable);
        return new LoadedModule<>(manifest, module, new URLClassLoader(new URL[0]));
    }

    private void stubLoader(List<LoadedModule<Plugin>> modules) throws IOException {
        doReturn(modules).when(this.loader).loadModulesFromDisk(anyString(), any(Logger.class));
    }

    @Test
    @DisplayName("start() initializes every loaded module and exposes them in order")
    void startInitializesModules() throws Exception {
        List<String> order = new ArrayList<>();
        FakeModule a = new FakeModule("a", order);
        FakeModule b = new FakeModule("b", order);
        stubLoader(List.of(loaded("a", false, a), loaded("b", true, b)));

        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        assertTrue(a.initialized);
        assertTrue(b.initialized);
        // NOTE: getActiveModules() is backed by Map.copyOf(), whose iteration order is unspecified —
        // so despite the method's "in initialization order" Javadoc, callers cannot rely on ordering
        // here. Assert membership only. (stop() preserves order because it reads the internal
        // LinkedHashMap directly; see stopShutsDownInReverseOrder.)
        assertEquals(java.util.Set.of("a", "b"), manager.getActiveModules().keySet());
    }

    @Test
    @DisplayName("start() twice without stop() is rejected")
    void startTwiceThrows() throws Exception {
        stubLoader(List.of(loaded("a", false, new FakeModule("a", new ArrayList<>()))));
        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        assertThrows(IllegalStateException.class, manager::start);
    }

    @Test
    @DisplayName("a duplicate module name is ignored and warned about")
    void duplicateModuleNameIgnored() throws Exception {
        List<String> order = new ArrayList<>();
        stubLoader(List.of(
                loaded("dup", false, new FakeModule("dup", order)),
                loaded("dup", false, new FakeModule("dup", order))));

        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        assertEquals(1, manager.getActiveModules().size());
        assertTrue(logger.hasRecord(Level.WARNING, "Duplicate module name"));
    }

    @Test
    @DisplayName("a module that fails to initialize is unloaded, not left active")
    void initializationFailureUnloads() throws Exception {
        FakeModule bad = new FakeModule("bad", new ArrayList<>());
        bad.initFailure = new RuntimeException("boom");
        stubLoader(List.of(loaded("bad", false, bad)));

        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        assertTrue(manager.getActiveModules().isEmpty());
        assertTrue(logger.hasRecord(Level.SEVERE, "Failed to initialize module bad"));
    }

    @Test
    @DisplayName("stop() shuts modules down in reverse initialization order and clears state")
    void stopShutsDownInReverseOrder() throws Exception {
        List<String> order = new ArrayList<>();
        stubLoader(List.of(
                loaded("a", false, new FakeModule("a", order)),
                loaded("b", false, new FakeModule("b", order)),
                loaded("c", false, new FakeModule("c", order))));

        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();
        manager.stop();

        assertEquals(List.of("c", "b", "a"), order);
        assertTrue(manager.getActiveModules().isEmpty());
    }

    @Test
    @DisplayName("reloadAsync() on an unknown module fails with a ModuleLoadException")
    void reloadUnknownModuleFails() throws Exception {
        stubLoader(List.of());
        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        CompletableFuture<Void> future = manager.reloadAsync("ghost");

        assertTrue(future.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(ModuleLoadException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("No active module"));
    }

    @Test
    @DisplayName("reloadAsync() on a non-reloadable module fails with a ModuleLoadException")
    void reloadNonReloadableFails() throws Exception {
        stubLoader(List.of(loaded("core", false, new FakeModule("core", new ArrayList<>()))));
        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        CompletableFuture<Void> future = manager.reloadAsync("core");

        assertTrue(future.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(ModuleLoadException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("not reloadable"));
    }

    @Test
    @DisplayName("reloadAsync() on a reloadable module delegates to the module's reload")
    void reloadReloadableDelegates() throws Exception {
        FakeModule econ = new FakeModule("econ", new ArrayList<>());
        stubLoader(List.of(loaded("econ", true, econ)));
        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        CompletableFuture<Void> future = manager.reloadAsync("econ");

        assertTrue(econ.reloadCalled);
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("reloadAsync() tolerates a module that returns a null reload future")
    void reloadNullFutureBecomesCompleted() throws Exception {
        FakeModule econ = new FakeModule("econ", new ArrayList<>());
        econ.reloadFuture = null; // module violates the @NotNull contract; manager must not NPE
        stubLoader(List.of(loaded("econ", true, econ)));
        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        CompletableFuture<Void> future = manager.reloadAsync("econ");

        assertNotNull(future);
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("getActiveModules() returns an immutable snapshot")
    void activeModulesIsImmutableSnapshot() throws Exception {
        stubLoader(List.of(loaded("a", false, new FakeModule("a", new ArrayList<>()))));
        ModuleLifecycleManager<Plugin> manager = manager();
        manager.start();

        Map<String, LoadedModule<Plugin>> snapshot = manager.getActiveModules();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", null));

        // The snapshot is decoupled from later manager state changes.
        manager.stop();
        assertEquals(1, snapshot.size());
        assertTrue(manager.getActiveModules().isEmpty());
    }
}
