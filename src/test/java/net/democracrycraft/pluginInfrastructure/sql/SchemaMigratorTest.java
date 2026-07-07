package net.democracrycraft.pluginInfrastructure.sql;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
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

    private DataSource dataSource;
    private CountingLogger logger;
    private SchemaMigrator migrator;

    /** A minimal concrete migrator supplying H2-compatible bookkeeping SQL. */
    private static final class H2Migrator extends SchemaMigrator {
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

    private void migrate(List<MigrationStep> steps) throws IOException, SQLException {
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
    @DisplayName("a missing migration resource surfaces as an IOException")
    void missingResourceThrows() {
        MigrationStep missing = new MigrationStep(1, "does not exist", "V9__missing.sql");
        IOException ex = assertThrows(IOException.class, () -> migrate(List.of(missing)));
        assertTrue(ex.getMessage().contains("V9__missing.sql"),
                () -> "unexpected message: " + ex.getMessage());
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
