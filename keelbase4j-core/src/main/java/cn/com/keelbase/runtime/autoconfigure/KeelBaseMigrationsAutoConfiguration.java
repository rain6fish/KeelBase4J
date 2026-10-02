// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
 * than at review. Both dialects are present — {@code h2/} for a standalone deployment, {@code mysql/}
 * for a host — and a dialect without a directory finds no migrations, which is a schema Hibernate then
 * reports as missing.
 *
 * <p>给部署本核心的人**核心自己的 schema**（ADR-0017 D4）。
 *
 * <p>核心映着实体——会话记录、确认队列、副作用台账、审计链与它那把锁行——而自己不持有数据库。独立部署交给它
 * 一个嵌入式库，嵌入部署交给它宿主的库。无论哪种，那些表都得在 Hibernate 看它们之前存在，而一个会校验 schema
 * 的部署**宁可拒绝启动**、也不会自己把它们造出来。所以迁移文件随 jar 走，由这里应用它们。
 *
 * <p>**迁移位置是「追加」，不是「指派」。** 为自身 schema 跑 Flyway 的宿主已经选好了它自己的迁移住哪儿；
 * 一个覆写 `spring.flyway.locations` 的核心，会把那个选择夺走、并让宿主自己的迁移不被应用。这里只加一个位置，
 * Flyway 的其它决定一律不碰——与整个嵌入的形状一致：我们带来我们那份，部署方保留它自己的。
 *
 * <p>**方言是路径的一部分，不是文件的一部分。** 迁移住在 `db/keelbase-migration/<vendor>/` 下，vendor 按
 * Boot 为 `spring.flyway.locations` 提供的 `{vendor}` 占位符同样的方式解析。这是唯一能保证「H2 的迁移不会被
 * 跑到宿主的 MySQL 上」的办法：两种方言在自增列与时间类型上不一致，所以一个文件不可能同时是两者，而跑错的那份
 * 会在**启动时**失败、不是在评审时。**两种方言现在都在**——`h2/` 给独立部署，`mysql/` 给宿主；某个方言没有
 * 对应目录时找不到任何迁移，那就是 Hibernate 随后报告「表不存在」的那种 schema。
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
            // Kept as strings on purpose: Flyway's own Location(String) constructor is deprecated, and
            // locations(String...) is what the configuration is set through anyway — building objects only
            // to hand them back as their descriptors is a detour through the API's deprecated door.
            List<String> locations = new ArrayList<>();
            for (Location existing : configuration.getLocations()) {
                locations.add(existing.getDescriptor());
            }
            locations.add(LOCATION_PREFIX + vendor);
            configuration.locations(locations.toArray(String[]::new));
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
