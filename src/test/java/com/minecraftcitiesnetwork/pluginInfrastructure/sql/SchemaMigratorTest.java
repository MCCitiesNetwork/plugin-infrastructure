package com.minecraftcitiesnetwork.pluginInfrastructure.sql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {

    /** Distinct in-memory database name per test so migrations never bleed across cases. */
    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private static final Path BASE_DIR = Path.of("migrations");

    private static final MigrationStep V1 = new MigrationStep(1, "create users", "V1__create_users.sql");
    private static final MigrationStep V2 = new MigrationStep(2, "add email", "V2__add_email.sql");
    private static final MigrationStep V3 = new MigrationStep(3, "create orders", "V3__create_orders.sql");
    private static final MigrationStep V4 = new MigrationStep(4, "multi statement", "V4__multi_statement.sql");
    private static final MigrationStep V5 = new MigrationStep(5, "literal semicolon", "V5__literal_semicolon.sql");
    private static final MigrationStep V6_EMPTY = new MigrationStep(6, "comments only", "V6__comments_only.sql");
    private static final MigrationStep V7_FAILING = new MigrationStep(7, "duplicate table", "V7__duplicate_table.sql");

    private DataSource dataSource;
    private CountingLogger logger;
    private SchemaMigrator migrator;

    /** A minimal concrete migrator supplying H2-compatible bookkeeping SQL. */
    private static class H2Migrator extends SchemaMigrator {
        @Override
        protected String bootstrapDdl() {
            return "CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INT PRIMARY KEY, description VARCHAR(255), "
                    + "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
        }

        @Override
        protected String selectVersion() {
            return "SELECT COALESCE(MAX(version), 0) FROM schema_version";
        }

        @Override
        protected String insertVersion() {
            return "INSERT INTO schema_version (version, description) VALUES (?, ?)";
        }
    }

    /**
     * A {@link DataSource} that behaves like a pool of one: every {@code getConnection()} returns
     * the same underlying connection, and {@code close()} on it is a no-op rather than a real close.
     * That is what makes state left behind by a failed migration observable to the next borrower —
     * which is the whole point of the rollback and auto-commit restore in {@code migrate}.
     *
     * <p>Only {@code getConnection()} is meaningful; the rest of the interface is unsupported.
     */
    private static final class SingleConnectionDataSource implements DataSource {
        private final Connection real;
        private final Connection nonClosing;

        SingleConnectionDataSource(Connection real) {
            this.real = real;
            this.nonClosing = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }

        /** Closes the connection for real, once the test is done with it. */
        void reallyClose() throws SQLException {
            this.real.close();
        }

        @Override public Connection getConnection() {
            return this.nonClosing;
        }

        @Override public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException();
        }

        @Override public int getLoginTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override public Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }

        @Override public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /** Captures "Applied migration" info lines so tests can assert exactly what was applied. */
    private static final class CountingLogger {
        private final Logger logger;
        private final List<String> appliedMessages = new ArrayList<>();

        CountingLogger(String name) {
            this.logger = Logger.getLogger(name);
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(Level.ALL);
            this.logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null && record.getMessage().startsWith("Applied migration")) {
                        appliedMessages.add(record.getMessage());
                    }
                }

                @Override public void flush() { }

                @Override public void close() { }
            });
        }

        Logger logger() {
            return this.logger;
        }

        int appliedCount() {
            return this.appliedMessages.size();
        }

        void reset() {
            this.appliedMessages.clear();
        }
    }

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1 keeps the in-memory DB alive between the connection open/close cycles
        // that migrate() performs, for the lifetime of the JVM.
        ds.setURL("jdbc:h2:mem:migtest_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        this.dataSource = ds;
        this.logger = new CountingLogger("migtest-" + DB_COUNTER.get());
        this.migrator = new H2Migrator();
    }

    private void migrate(List<MigrationStep> steps) throws SQLException {
        this.migrator.migrate(this.dataSource, BASE_DIR, steps,
                getClass().getClassLoader(), this.logger.logger());
    }

    private int currentVersion() throws SQLException {
        try (Connection c = this.dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection c = this.dataSource.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return rs.next();
        }
    }

    @Test
    @DisplayName("a fresh database applies every step in order and records each version")
    void freshDatabaseAppliesAllSteps() throws Exception {
        migrate(List.of(V1, V2, V3));

        assertEquals(3, currentVersion());
        assertEquals(3, logger.appliedCount());
        assertTrue(tableExists("app_users"));
        assertTrue(tableExists("app_orders"));

        // V2's ALTER must have run: inserting a row that supplies the email column succeeds.
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO app_users (id, name, email) VALUES (1, 'a', 'a@example.com')");
        }
    }

    @Test
    @DisplayName("re-running against an up-to-date database applies nothing")
    void reRunIsIdempotent() throws Exception {
        migrate(List.of(V1, V2, V3));
        assertEquals(3, logger.appliedCount());

        logger.reset();
        migrate(List.of(V1, V2, V3));

        assertEquals(0, logger.appliedCount(), "no migrations should be re-applied");
        assertEquals(3, currentVersion());
    }

    @Test
    @DisplayName("only steps newer than the recorded version are applied")
    void appliesOnlyNewerSteps() throws Exception {
        migrate(List.of(V1));
        assertEquals(1, currentVersion());

        logger.reset();
        migrate(List.of(V1, V2, V3));

        assertEquals(2, logger.appliedCount(), "only V2 and V3 are newer than the recorded V1");
        assertEquals(3, currentVersion());
    }

    @Test
    @DisplayName("a database newer than any supported step is rejected")
    void databaseNewerThanSupportedThrows() throws Exception {
        migrate(List.of(V1, V2, V3));
        assertEquals(3, currentVersion());

        // Now offer a migrator that only knows up to V2 — the DB is ahead of us.
        SQLException ex = assertThrows(SQLException.class, () -> migrate(List.of(V1, V2)));
        assertTrue(ex.getMessage().toLowerCase().contains("newer"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("a missing migration resource surfaces as a SQLException, the one checked type")
    void missingResourceThrows() {
        // Resource failures are wrapped rather than thrown as IOException so a plugin disabling
        // itself on a failed migration has a single exception type to catch.
        MigrationStep missing = new MigrationStep(1, "does not exist", "V9__missing.sql");
        SQLException ex = assertThrows(SQLException.class, () -> migrate(List.of(missing)));
        assertTrue(ex.getMessage().contains("V9__missing.sql"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("a file with several statements applies them all, with comments stripped")
    void multiStatementFileApplies() throws Exception {
        migrate(List.of(V1, V2, V3, V4));

        assertEquals(4, currentVersion());
        // Both statements in V4 ran, and neither the -- nor the # nor the /* */ comment (each
        // containing a semicolon) was mistaken for a statement of its own.
        assertTrue(tableExists("app_tags"));
        assertTrue(tableExists("app_notes"));
    }

    @Test
    @DisplayName("a semicolon inside a string literal is not a statement delimiter")
    void semicolonInsideLiteralIsNotSplit() throws Exception {
        migrate(List.of(V1, V2, V3, V4, V5));

        assertEquals(5, currentVersion());
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT body FROM app_notes WHERE id = 1")) {
            assertTrue(rs.next(), "the INSERT should have applied");
            // Naive splitting on ';' would have cut this literal in half and failed the statement.
            assertEquals("semi; colon -- not a comment", rs.getString(1));
        }
    }

    @Test
    @DisplayName("a migration containing nothing but comments is rejected, not silently recorded")
    void commentOnlyMigrationThrows() {
        // Recording a version for a file that ran nothing would leave the schema permanently
        // missing whatever the file was meant to carry, with no way to notice.
        SQLException ex = assertThrows(SQLException.class, () -> migrate(List.of(V6_EMPTY)));
        assertTrue(ex.getMessage().contains("no statements"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("a failing statement aborts the step and leaves its version unrecorded")
    void failingStatementDoesNotRecordVersion() throws Exception {
        migrate(List.of(V1));
        assertEquals(1, currentVersion());

        // V7's second statement recreates a table its first statement just made.
        assertThrows(SQLException.class, () -> migrate(List.of(V1, V7_FAILING)));

        // The version row must not have been written, so the step replays on the next start.
        assertEquals(1, currentVersion());
    }

    @Test
    @DisplayName("a failed migration hands back a rolled-back, auto-commit connection")
    void failedMigrationLeavesConnectionClean() throws Exception {
        // Deliberately NOT the plain JdbcDataSource used elsewhere: that opens a fresh connection
        // per getConnection(), so the second borrow could never observe what the first left behind
        // and this test would pass with the rollback deleted. A pool hands the same connection back.
        SingleConnectionDataSource pool = new SingleConnectionDataSource(this.dataSource.getConnection());
        try {
            assertThrows(SQLException.class, () -> this.migrator.migrate(
                    pool, BASE_DIR, List.of(V7_FAILING), getClass().getClassLoader(), logger.logger()));

            Connection reborrowed = pool.getConnection();
            assertTrue(reborrowed.getAutoCommit(),
                    "auto-commit should be restored before the connection goes back to the pool");
            // Nothing uncommitted may ride along to the next borrower.
            try (Statement st = reborrowed.createStatement()) {
                st.execute("CREATE TABLE app_after_failure (id INT PRIMARY KEY)");
            }
        } finally {
            pool.reallyClose();
        }
        assertTrue(tableExists("app_after_failure"));
    }

    @Test
    @DisplayName("a subclass can replace the statement splitter")
    void splitterIsOverridable() throws Exception {
        // A dialect where the default '#'-comment handling is wrong can send each file to the
        // server whole instead. H2 accepts multi-statement execute, so this must still apply.
        SchemaMigrator unsplit = new H2Migrator() {
            @Override
            protected List<String> splitStatements(String sql) {
                return List.of(sql);
            }
        };
        unsplit.migrate(dataSource, BASE_DIR, List.of(V1), getClass().getClassLoader(), logger.logger());

        assertEquals(1, currentVersion());
        assertTrue(tableExists("app_users"));
    }

    @Test
    @DisplayName("an empty step list still bootstraps the version table at version 0")
    void emptyStepsBootstrapsOnly() throws Exception {
        migrate(List.of());

        assertTrue(tableExists("schema_version"));
        assertEquals(0, currentVersion());
        assertEquals(0, logger.appliedCount());
        assertFalse(tableExists("app_users"));
    }
}
