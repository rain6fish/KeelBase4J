// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The core's third dialect, applied to a real PostgreSQL rather than to its h2 twin.
 *
 * <p>{@code postgresql/V*.sql} is a byte-identical copy of {@code h2/V*.sql}, and that claim is the
 * whole reason this test exists: two dialects agreeing on the same DDL is exactly the kind of thing
 * that holds until it does not, and reading the two files side by side cannot tell you which you have.
 * Here the same migrations run against a real PostgreSQL with Hibernate told to validate the entities
 * against the schema they produced — the posture {@code CoreAssemblyTest} takes on H2, taken on the
 * database a host actually runs. If the copy were wrong, this fails at startup rather than at review.
 *
 * <p>Guarded, because CI has no PostgreSQL: without {@code KEELBASE_PG_URL} this is skipped. The
 * CI-side guard for the third dialect stays {@code MigrationDialectsTest}, which pins the directory
 * name to what Boot resolves — that one runs everywhere, this one is what turns "the DDL is the same"
 * from an assertion into a measurement.
 *
 * <p>Expects a database it can create the schema in, and does not clean up after itself: the migrations
 * are versioned, so a second run against the same database finds them already applied and still passes.
 *
 * <p>本核心的**第三种方言**，跑在真的 PostgreSQL 上，而不是它的 h2 双胞胎上。
 *
 * <p>`postgresql/V*.sql` 是 `h2/V*.sql` 的**逐字节副本**，而这条主张正是本测试存在的全部理由：两个方言在
 * 同一份 DDL 上一致，恰恰是那种「在它不成立之前一直成立」的事，而并排读两份文件**看不出**手里是哪一种。
 * 这里同一批迁移跑在真的 PostgreSQL 上，并让 Hibernate 拿实体去校验它建出的 schema —— 与
 * `CoreAssemblyTest` 在 H2 上取的同一种姿态，只是换到了宿主真正跑的那个库上。副本若是错的，这里**启动即失败**，
 * 而不是等评审。
 *
 * <p>带守卫，因为 CI 没有 PostgreSQL：没有 `KEELBASE_PG_URL` 就跳过。CI 侧对第三方言的闸仍是
 * `MigrationDialectsTest`（把目录名钉在 Boot 的解析结果上，哪儿都跑）；而这一条，是把「DDL 一样」从
 * **断言**变成**实测**的那条。
 *
 * <p>它假定自己能在目标库里建 schema，且**用完不清理**：迁移是版本化的，故对同一个库再跑一次只会发现
 * 它们已应用、仍旧通过。
 */
@SpringBootTest(classes = CoreTestApplication.class,
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
@EnabledIfEnvironmentVariable(named = "KEELBASE_PG_URL", matches = ".+")
class PostgresMigrationTest {

    @DynamicPropertySource
    static void pointAtTheRealPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("KEELBASE_PG_URL"));
        registry.add("spring.datasource.username", () -> envOrDefault("KEELBASE_PG_USER", "keelbase"));
        registry.add("spring.datasource.password", () -> envOrDefault("KEELBASE_PG_PASSWORD", "keelbase"));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * The context starting at all is most of this test: migrations plus {@code validate} means the
     * entities were checked against a schema PostgreSQL built from the copied DDL. What is asserted
     * below is that the schema came from those migrations and not from Hibernate's own opinion.
     */
    @Test
    void theThirdDialectAppliesAndValidatesOnRealPostgres() {
        for (String table : List.of("conversation_messages", "confirmation_requests", "side_effects",
                "ai_audit_logs", "customers", "follow_ups", "write_claims")) {
            Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
            assertTrue(rows != null && rows >= 0, table + " is reachable on PostgreSQL");
        }

        List<String> applied = Arrays.stream(context.getBean(Flyway.class).info().applied())
                .map(MigrationInfo::getScript)
                .toList();
        for (String script : List.of("V1__governance_state.sql", "V2__sample_module_state.sql",
                "V3__approval_mode.sql", "V4__write_claims.sql")) {
            assertTrue(applied.contains(script),
                    script + " was applied to PostgreSQL by the core's own migration: " + applied);
        }

        // The one row that is not the migration's to seed: the chain head every appender locks.
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_chain_head", Integer.class),
                "the chain's lock row is the one row this table has");
    }
}
