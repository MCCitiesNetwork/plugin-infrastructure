package net.democracrycraft.pluginInfrastructure.sql;

import org.jetbrains.annotations.NotNull;

/**
 * A single ordered schema migration.
 *
 * @param version      the monotonically increasing version this step brings the schema to; must be
 *                     unique and greater than zero
 * @param description  a short human-readable summary, recorded in the {@code schema_version} table
 * @param resourcePath the migration's SQL file, resolved relative to the base resource directory
 *                     against the class loader passed to {@link SchemaMigrator#migrate}
 */
public record MigrationStep(int version, @NotNull String description, @NotNull String resourcePath) {

}
