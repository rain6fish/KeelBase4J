// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every dialect this core claims to run on has migrations, under the name Boot resolves for it.
 *
 * <p>The vendor segment of {@code db/keelbase-migration/<vendor>/} is not ours to choose: the
 * auto-configuration resolves it through Spring Boot's {@code DatabaseDriver}, so that it means the same
 * thing as the {@code {vendor}} placeholder a deployment may already be using. A directory spelled any
 * other way is not an error anybody sees — Flyway simply finds no migrations for that dialect, and
 * Hibernate then reports the schema as missing. That is a packaging-time cause with a deployment-time
 * symptom, so the name is pinned here against Boot's own resolution rather than against a convention of
 * ours: get it wrong and this goes red, instead of a host failing to start months later.
 *
 * <p>The version sets are compared with each other rather than against a list. A dialect that quietly
 * missed a later migration would start clean and then fail on a column the entities expect, and a list
 * here would have to be edited by whoever forgets to edit the dialect.
 *
 * <p>本核心声称支持的每个方言都有迁移，且目录名**就是 Boot 为它解析出的那个**。
 *
 * <p>`db/keelbase-migration/<vendor>/` 的方言段**不是我们选的**：自动配置经 Spring Boot 的
 * `DatabaseDriver` 解析它，为的是让它与部署方可能已在用的 `{vendor}` 占位符**同义**。拼成别的样子
 * 不会有人看见错误 —— Flyway 只是为该方言找不到任何迁移，随后 Hibernate 报告 schema 缺失。那是**打包期
 * 的因、部署期的果**，故在此把名字钉在 **Boot 自己的解析结果**上，而不是我们的命名约定上：写错这里就红，
 * 而不是几个月后某个宿主起不来。
 *
 * <p>版本集合是**互相比对**，不是比对一份清单。某个方言若悄悄漏了后来的一次迁移，它会干净启动、然后在实体
 * 预期的那一列上失败；而一份清单总得由「忘了改方言的那个人」来改。
 */
class MigrationDialectsTest {

    /**
     * The dialects this core ships migrations for, each with a URL that identifies it to Boot. The key is
     * the spelling the directory must have — asserted below against Boot rather than assumed.
     */
    private static final Map<String, String> DIALECTS = Map.of(
            "h2", "jdbc:h2:mem:keelbase",
            "mysql", "jdbc:mysql://localhost:3306/keelbase",
            "postgresql", "jdbc:postgresql://localhost:5432/keelbase");

    @Test
    void eachDialectHasMigrationsUnderTheNameBootResolves() throws IOException {
        Map<String, Set<String>> byDirectory = migrationVersionsByDirectory();

        for (Map.Entry<String, String> dialect : DIALECTS.entrySet()) {
            String resolved = DatabaseDriver.fromJdbcUrl(dialect.getValue()).getId();
            assertEquals(dialect.getKey(), resolved,
                    "the directory for " + dialect.getValue() + " has to be spelled the way Boot resolves it");
            assertTrue(byDirectory.containsKey(resolved),
                    "no migrations under db/keelbase-migration/" + resolved + "/; directories found: "
                            + byDirectory.keySet());
        }
    }

    @Test
    void theDialectsCarryTheSameVersions() throws IOException {
        Map<String, Set<String>> byDirectory = migrationVersionsByDirectory();

        Set<String> reference = byDirectory.get("h2");
        assertNotNull(reference, "h2 is the reference dialect; directories found: " + byDirectory.keySet());
        assertTrue(!reference.isEmpty(), "h2 has migrations: " + byDirectory);

        for (String dialect : DIALECTS.keySet()) {
            assertEquals(reference, byDirectory.get(dialect),
                    dialect + " offers the same migration versions as h2, so no dialect starts clean and"
                            + " then fails on a column the entities expect");
        }
    }

    /** Migration script names, grouped by the dialect directory they sit in. */
    private static Map<String, Set<String>> migrationVersionsByDirectory() throws IOException {
        Map<String, Set<String>> byDirectory = new LinkedHashMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/keelbase-migration/*/*.sql")) {
            String[] segments = resource.getURL().toString().split("/");
            String script = segments[segments.length - 1];
            String dialect = segments[segments.length - 2];
            byDirectory.computeIfAbsent(dialect, key -> new TreeSet<>()).add(script);
        }
        return byDirectory;
    }
}
