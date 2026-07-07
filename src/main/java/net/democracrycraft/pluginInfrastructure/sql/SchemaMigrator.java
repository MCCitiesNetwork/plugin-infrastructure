package net.democracrycraft.pluginInfrastructure.sql;

import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Applies ordered SQL schema migrations against a JDBC {@link DataSource}, tracking the applied
 * version in a {@code schema_version} table.
 *
 * <p>Migrations are supplied as an ordered {@link MigrationStep} list. Each step's SQL is loaded as a
 * classpath resource (relative to a base directory) from a caller-supplied {@link ClassLoader}, so
 * the migration files can live in the consuming plugin's jar rather than this infrastructure module.
 *
 * <p>The multi-statement handling targets MySQL/MariaDB. If a migration file contains multiple
 * statements, the data source must permit multi-statement execution (for the MariaDB driver, the
 * {@code allowMultiQueries=true} connection property).
 *
 * <p>This class supplies no bookkeeping SQL of its own. A subclass must provide the three
 * dialect-specific statements by implementing {@link #bootstrapDdl()}, {@link #selectVersion()}, and
 * {@link #insertVersion()} — the {@code schema_version} bootstrap DDL, the current-version query, and
 * the version-recording insert respectively.
 */
public abstract class SchemaMigrator {

    /**
     * @return DDL that idempotently creates the {@code schema_version} bookkeeping table with
     *         {@code version}, {@code description}, and {@code applied_at} columns
     */
    protected abstract @NotNull String bootstrapDdl();

    /**
     * @return a query returning the highest applied schema version, or {@code 0} when none exist
     */
    protected abstract @NotNull String selectVersion();

    /**
     * @return an insert taking two parameters — the {@code int} version and {@code String}
     *         description, in that order — that records an applied migration
     */
    protected abstract @NotNull String insertVersion();

    /**
     * Brings the schema up to date by applying every migration whose version exceeds the version
     * currently recorded in {@code schema_version}. Each applied step is committed individually.
     *
     * @param dataSource      the target database
     * @param baseResourceDir the directory, relative to the class loader's resource root, holding the
     *                        migration SQL files named by {@link MigrationStep#resourcePath()}
     * @param steps           the ordered migrations; must be sorted by ascending version
     * @param resourceLoader  the class loader used to load the migration SQL resources — typically the
     *                        consuming plugin's class loader
     * @param logger          receives one info line per applied migration
     * @throws IOException  if a migration resource cannot be found or read
     * @throws SQLException if a migration fails, or the database is at a version newer than any step
     */
    public void migrate(
            @NotNull DataSource dataSource,
            @NotNull Path baseResourceDir,
            @NotNull List<MigrationStep> steps,
            @NotNull ClassLoader resourceLoader,
            @NotNull Logger logger
    ) throws IOException, SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(bootstrapDdl());
            }
            connection.commit();

            int currentVersion;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(selectVersion())) {
                rs.next();
                currentVersion = rs.getInt(1);
            }

            int maxSupportedVersion = steps.stream()
                    .mapToInt(MigrationStep::version)
                    .max()
                    .orElse(0);
            if (currentVersion > maxSupportedVersion) {
                throw new SQLException(
                        "Database schema version " + currentVersion
                                + " is newer than the maximum supported version "
                                + maxSupportedVersion
                                + ". Please update the plugin."
                );
            }

            for (MigrationStep step : steps) {
                if (step.version() <= currentVersion) {
                    continue;
                }
                String resourcePath = baseResourceDir.resolve(step.resourcePath()).toString().replace('\\', '/');
                String sql = loadResource(resourceLoader, resourcePath);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                    // Drain all results to surface errors from multi-statement batches.
                    // With allowMultiQueries=true, execute() only returns the first
                    // statement's result; errors from subsequent statements are hidden
                    // unless we iterate through getMoreResults().
                    while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                        // iterate until exhausted
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(insertVersion())) {
                    ps.setInt(1, step.version());
                    ps.setString(2, step.description());
                    ps.executeUpdate();
                }
                connection.commit();
                logger.info("Applied migration V" + step.version() + ": " + step.description());
            }
        }
    }

    private static @NotNull String loadResource(
            @NotNull ClassLoader resourceLoader,
            @NotNull String resourcePath
    ) throws IOException {
        try (InputStream is = resourceLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Migration resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
