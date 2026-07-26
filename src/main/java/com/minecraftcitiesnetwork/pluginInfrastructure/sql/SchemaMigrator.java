package com.minecraftcitiesnetwork.pluginInfrastructure.sql;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Applies ordered SQL schema migrations against a JDBC {@link DataSource}, tracking the applied
 * version in a {@code schema_version} table.
 *
 * <p>Migrations are supplied as an ordered {@link MigrationStep} list. Each step's SQL is loaded as a
 * classpath resource (relative to a base directory) from a caller-supplied {@link ClassLoader}, so
 * the migration files can live in the consuming plugin's jar rather than this infrastructure module.
 *
 * <p>This class supplies no bookkeeping SQL of its own. A subclass must provide the three
 * dialect-specific statements by implementing {@link #bootstrapDdl()}, {@link #selectVersion()}, and
 * {@link #insertVersion()} — the {@code schema_version} bootstrap DDL, the current-version query, and
 * the version-recording insert respectively.
 *
 * <h2>Give each plugin its own version table</h2>
 * The bookkeeping table name comes entirely from the subclass, and it should be <strong>namespaced
 * per plugin</strong> ({@code myplugin_schema_version}, not {@code schema_version}). Two plugins
 * sharing a database and a table name will read each other's version: the one that is behind trips
 * the downgrade guard below and refuses to start, and the one that is ahead re-applies migrations
 * that were never its own.
 *
 * <h2>Multi-statement files</h2>
 * A migration resource may contain several statements separated by {@code ;}. They are split
 * <em>client-side</em> by {@link #splitStatements(String)} and executed one at a time, so no
 * server-side multi-statement flag ({@code allowMultiQueries=true} on MariaDB/MySQL) needs to be
 * present in the operator's JDBC arguments, and any failure is reported against the statement that
 * actually caused it rather than the file as a whole. The default splitter is quote- and
 * comment-aware; see {@link #splitStatements(String)} for its dialect assumptions and how to replace
 * it.
 *
 * <h2>Transactions and re-runnability</h2>
 * Each step's statements and its version row share one transaction and one commit, so a crash
 * cannot record a migration that did not fully apply. Note that MySQL and MariaDB have no
 * transactional DDL — an {@code ALTER TABLE} implicitly commits — so on those engines that
 * guarantee really only covers the version row. <strong>Write every migration to be re-runnable</strong>:
 * guard structural changes on {@code information_schema} lookups and make data fixes idempotent. A
 * crash mid-step then leaves the recorded version unchanged and the step safely replays on the next
 * start.
 */
public abstract class SchemaMigrator {

    /**
     * @return DDL that idempotently creates the bookkeeping table with {@code version},
     *         {@code description}, and {@code applied_at} columns. Name the table per plugin rather
     *         than {@code schema_version} — see the class documentation.
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
     * currently recorded in the bookkeeping table. Each applied step is committed individually.
     *
     * @param dataSource      the target database
     * @param baseResourceDir the directory, relative to the class loader's resource root, holding the
     *                        migration SQL files named by {@link MigrationStep#resourcePath()}.
     *                        A classpath location, <em>not</em> a filesystem path
     * @param steps           the ordered migrations; must be sorted by ascending version
     * @param resourceLoader  the class loader used to load the migration SQL resources — typically the
     *                        consuming plugin's class loader
     * @param logger          receives one info line per applied migration
     * @throws SQLException if a migration fails, if a migration resource is missing, unreadable or
     *                      empty, or if the database is at a version newer than any step. Resource
     *                      failures are wrapped rather than thrown as {@link IOException} so that a
     *                      caller — typically a plugin disabling itself on a failed migration — has
     *                      a single exception type to handle
     */
    public void migrate(
            @NotNull DataSource dataSource,
            @NotNull Path baseResourceDir,
            @NotNull List<MigrationStep> steps,
            @NotNull ClassLoader resourceLoader,
            @NotNull Logger logger
    ) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(baseResourceDir, "baseResourceDir");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(resourceLoader, "resourceLoader");
        Objects.requireNonNull(logger, "logger");

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(bootstrapDdl());
                }
                connection.commit();

                int currentVersion = readVersion(connection);

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
                    applyStep(connection, baseResourceDir, step, resourceLoader);
                    logger.info("Applied migration V" + step.version() + ": " + step.description());
                }
            } catch (SQLException | RuntimeException ex) {
                // Without this, a step that fails part-way leaves its uncommitted work on the
                // connection, which the pool then hands to the next borrower.
                rollbackQuietly(connection, logger);
                throw ex;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // The connection is being returned to the pool, which resets auto-commit anyway.
                }
            }
        }
    }

    /**
     * Splits a migration resource into individual statements, stripping {@code --} and {@code #} line
     * comments and {@code /*}&#42;{@code /} block comments. Quoted literals and backtick-quoted
     * identifiers are tracked so a {@code ;} or {@code --} inside them is never treated as a
     * delimiter — guarded-DDL migrations commonly build {@code ALTER TABLE} text inside a string
     * literal, and would otherwise be split mid-literal.
     *
     * <p><strong>The default implementation lexes MySQL/MariaDB, not standard SQL.</strong> Three of
     * its rules are dialect-specific: {@code #} starts a line comment, backticks quote identifiers,
     * and a backslash escapes the next character inside a string literal. In ANSI SQL — and so in
     * PostgreSQL, SQLite and H2 — a backslash is an ordinary character and a quote is escaped by
     * doubling it. A consumer on one of those dialects must override this method; the three abstract
     * methods above are the only dialect concerns the compiler will remind you about, so this one is
     * easy to miss.
     *
     * <p>Overriding with {@code List.of(sql)} restores whole-file execution, which requires the
     * connection to permit multi-statement execution.
     *
     * @param sql the raw resource text
     * @return the non-blank statements, in order, without their trailing semicolons
     */
    protected @NotNull List<String> splitStatements(@NotNull String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(sql.length());
        char quote = 0;
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char c = sql.charAt(index);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && quote != '`' && index + 1 < length) {
                    // Escaped character inside a quoted literal; consume it verbatim.
                    current.append(sql.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                index++;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                index++;
                continue;
            }
            if (c == '#' || (c == '-' && index + 1 < length && sql.charAt(index + 1) == '-')) {
                // Both line-comment forms: skip to the newline.
                while (index < length && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (c == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                index = end < 0 ? length : end + 2;
                continue;
            }
            if (c == ';') {
                addIfNotBlank(statements, current);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private int readVersion(@NotNull Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(selectVersion())) {
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getInt(1);
        }
    }

    private void applyStep(
            @NotNull Connection connection,
            @NotNull Path baseResourceDir,
            @NotNull MigrationStep step,
            @NotNull ClassLoader resourceLoader
    ) throws SQLException {
        // Resolved as a classpath location, so '/' always, even when running on Windows.
        String resourcePath = baseResourceDir.resolve(step.resourcePath()).toString().replace('\\', '/');
        List<String> statements = splitStatements(loadResource(resourceLoader, resourcePath));
        if (statements.isEmpty()) {
            // Otherwise the version row is recorded for a migration that did nothing at all, and the
            // schema silently never gets the change the file was supposed to carry.
            throw new SQLException("Migration V" + step.version() + " ("
                    + step.resourcePath() + ") contains no statements");
        }
        try (Statement statement = connection.createStatement()) {
            for (String single : statements) {
                // One statement per execute, so every failure is reported against the statement that
                // caused it and nothing hides behind an earlier success.
                statement.execute(single);
                // Drain any further results this statement produced. execute() only exposes the
                // first, so without this loop an error raised by anything after it is never seen.
                while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                    // Iterate until exhausted.
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(insertVersion())) {
            ps.setInt(1, step.version());
            ps.setString(2, step.description());
            ps.executeUpdate();
        }
        connection.commit();
    }

    private static void rollbackQuietly(@NotNull Connection connection, @NotNull Logger logger) {
        try {
            connection.rollback();
        } catch (SQLException ex) {
            logger.severe("Failed to roll back a failed migration: " + ex.getMessage());
        }
    }

    private static void addIfNotBlank(@NotNull List<String> statements, @NotNull StringBuilder current) {
        String candidate = current.toString().trim();
        current.setLength(0);
        if (!candidate.isEmpty()) {
            statements.add(candidate);
        }
    }

    private static @NotNull String loadResource(
            @NotNull ClassLoader resourceLoader,
            @NotNull String resourcePath
    ) throws SQLException {
        try (InputStream is = resourceLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new SQLException("Migration resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // Converted rather than declared: migrate() promises callers a single checked exception
            // type so a plugin has one catch block to disable itself from.
            throw new SQLException("Failed to read migration resource: " + resourcePath, ex);
        }
    }
}
