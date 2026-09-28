// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.ConfirmationSweeper;
import cn.com.keelbase.runtime.web.ChatController;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The core assembles in an application that never heard of it (ADR-0017 D3, = the seam record's F2).
 *
 * <p>Every bean asserted here is registered by the core's own auto-configuration, reached from an
 * application whose component scan root is somewhere else entirely. That is the property a host needs,
 * and it is not something the runtime's own tests can show: they boot {@code KeelBase4JApplication},
 * whose scan covers the runtime's package, so they would keep passing even if the auto-configuration
 * were deleted.
 *
 * <p>Nothing here configures a database either, beyond one setting. The core is handed a datasource by
 * whoever deploys it; with H2 on the test classpath Spring Boot's own embedded-database path provides
 * one, which is the shape a host replaces with its own (ADR-0017 D4). What <em>is</em> set is
 * {@code ddl-auto=validate} — the host's posture, and the reason the core's migrations have to be right:
 * Hibernate is told to check the entities against the schema and refuse to start if they disagree. With
 * Hibernate's embedded default instead, Hibernate would build the schema itself and the migrations would
 * never be read, so a broken one would pass here and fail only in a host.
 *
 * <p>The properties supplied are the three settings the core requires and deliberately does not default:
 * the audit chain's HMAC key, the delegation secret and the audience it answers for. Supplying them is the
 * point rather than an inconvenience — a secret with a built-in default is a secret the deployment did not
 * choose. What the core refuses to carry is <em>deployment opinion</em> — a port, a context path, a
 * datasource — not the requirement that whoever deploys it provides these (ADR-0017 D5).
 */
@SpringBootTest(classes = CoreTestApplication.class,
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class CoreAssemblyTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theTrustLoopIsInTheContextWithoutBeingScanned() {
        assertEquals(1, context.getBeansOfType(GovernedExecutionEngine.class).size(),
                "the trust loop's engine comes from the core's auto-configuration");
    }

    @Test
    void theWebSurfaceCameWithIt() {
        assertTrue(context.getBeansOfType(ChatController.class).size() == 1,
                "and so do the endpoints — a host adds a jar, not a scan");
    }

    @Test
    void theSecurityChainIsTheCoresOwn() {
        // By name, and not merely by type: Spring Security's own auto-configuration contributes a
        // default chain whether or not this core is assembled, so asserting on the type would pass in
        // exactly the state this test exists to catch.
        assertTrue(context.getBeansOfType(SecurityFilterChain.class).containsKey("governedEndpoints"),
                "the chain the runtime authenticates with is the core's, and it is here: "
                        + context.getBeansOfType(SecurityFilterChain.class).keySet());
    }

    /**
     * The offline window's sweep is core behaviour, not a deployment's favour: an embedded core that
     * quietly lost it would leave confirmations decidable forever while looking installed (D3).
     */
    @Test
    void theSweepIsEnabledHere() {
        assertEquals(1, context.getBeansOfType(ConfirmationSweeper.class).size());
    }

    /**
     * The core's tables exist in the deployed schema, and the core's migrations are what put them there.
     *
     * <p>Asked of the database rather than of a bean: this is the fact a host depends on, and it is not
     * observable from the context alone. The migration names are asserted as well as the tables, because
     * only the second half distinguishes the two ways the tables could have appeared — {@code validate}
     * fails loudly if they are missing, but it passes just as happily over tables Hibernate created for
     * itself, which is the state this test exists to keep out (ADR-0017 D4).
     */
    @Test
    void theSchemaIsTheCoresOwnMigrationAndNotHibernatesGuess() {
        for (String table : List.of("conversation_messages", "confirmation_requests", "side_effects",
                "ai_audit_logs", "customers", "follow_ups")) {
            assertEquals(0, rowsIn(table), table + " is created by the core's migration and starts empty");
        }
        // The chain head is the exception, and by design: it is the row every appender locks, so exactly
        // one row is what a correct schema looks like here. The runtime inserts it at startup rather than
        // the migration seeding it (AuditChainHeadInitializer) — one place decides when the chain exists.
        assertEquals(1, rowsIn("audit_chain_head"), "the chain's lock row is the one row this table has");

        List<String> applied = Arrays.stream(context.getBean(Flyway.class).info().applied())
                .map(MigrationInfo::getScript)
                .toList();
        assertTrue(applied.contains("V1__governance_state.sql"),
                "the governance schema came from the core's migration: " + applied);
        assertTrue(applied.contains("V2__sample_module_state.sql"),
                "and so did the sample module's: " + applied);
    }

    private int rowsIn(String table) {
        Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        assertNotNull(rows, table + " is reachable");
        return rows;
    }
}
