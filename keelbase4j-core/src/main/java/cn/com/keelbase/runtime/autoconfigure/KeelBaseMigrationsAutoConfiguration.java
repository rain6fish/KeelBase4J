// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.context.annotation.Bean;

/**
 * Gives whoever deploys the core the core's schema (ADR-0017 D4).
 *
 * <p>The core maps entities — the conversation transcript, the confirmation queue, the side-effect
 * ledger, the audit chain and its lock row — and owns no database. A standalone deployment hands it an
 * embedded one; an embedded deployment hands it the host's. Either way the tables have to exist before
 * Hibernate looks at them, and a deployment that validates its schema will refuse to start rather than
 * invent them. So the migration files travel with the jar and this is what applies them.
 *
 * <p><b>The migration location is appended, never assigned.</b> A host that runs Flyway for its own
 * schema has already chosen where its migrations live; a core that overwrote {@code spring.flyway.locations}
 * would take that choice away and leave the host's own migrations unapplied. This adds a location and
 * leaves every other decision about Flyway alone — the same shape as the rest of the embedding: we bring
 * our part, the deployment keeps its own.
 *
 * <p><b>The dialect is part of the path, not of the file.</b> The migrations sit under
 * {@code db/keelbase-migration/<vendor>/}, and the vendor is resolved the way Boot resolves the
 * {@code {vendor}} placeholder it offers for {@code spring.flyway.locations}. That is the only way to
 * keep an H2 migration from being run against a host's MySQL: the two dialects disagree about identity
 * columns and timestamp types, so a single file cannot be both, and a wrong one fails at startup rather
 * than at review. Today only {@code h2/} exists — the standalone deployment's — and a host on another
 * dialect finds no directory and no migrations, which is a schema Hibernate then reports as missing.
 */
@AutoConfiguration
@ConditionalOnClass(FlywayConfigurationCustomizer.class)
public class KeelBaseMigrationsAutoConfiguration {

    /** Where this module's migrations live, minus the vendor segment the deployment decides. */
    static final String LOCATION_PREFIX = "classpath:db/keelbase-migration/";

    @Bean
    FlywayConfigurationCustomizer keelBaseSchemaMigrations() {
        return configuration -> {
            String vendor = vendorOf(configuration);
            if (vendor == null) {
                return;
            }
            List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
            locations.add(new Location(LOCATION_PREFIX + vendor));
            configuration.locations(locations.toArray(Location[]::new));
        };
    }

    /**
     * The vendor segment for the database this Flyway is pointed at, or {@code null} when nothing in the
     * configuration says which it is — in which case the locations are left exactly as the deployment
     * set them, and a missing table is reported by the schema check rather than guessed at here.
     *
     * <p>This deliberately resolves through Spring Boot's own {@code DatabaseDriver} rather than a
     * directory naming convention of our own: the vendor segment has to mean the same thing as the
     * {@code {vendor}} placeholder a deployment may already be using, or two spellings of the same
     * dialect would look for two different directories.
     */
    private static String vendorOf(FluentConfiguration configuration) {
        String url = configuration.getUrl();
        if (url == null) {
            url = urlOf(configuration.getDataSource());
        }
        if (url == null) {
            return null;
        }
        return DatabaseDriver.fromJdbcUrl(url).getId();
    }

    private static String urlOf(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "cannot read the database url to resolve the core's migration location", e);
        }
    }
}
