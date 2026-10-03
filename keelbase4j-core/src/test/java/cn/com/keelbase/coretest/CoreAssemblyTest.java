// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.ConfirmationSweeper;
import cn.com.keelbase.runtime.web.ChatController;
import cn.com.keelbase.runtime.web.WireErrorController;
import org.springframework.boot.webmvc.error.ErrorController;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 *
 * <p>核心能在一个**从未听说过它**的应用里装配起来（ADR-0017 D3，即缝记录的 F2）。
 *
 * <p>这里断言的每一个 bean，都由核心自己的自动配置注册，而它来自一个组件扫描根**完全在别处**的应用。那正是
 * 宿主需要的那条性质，也是运行时自己的测试**证明不了**的：它们启动的是 `KeelBase4JApplication`，其扫描覆盖了
 * 运行时的包，所以**即使把那条自动配置删掉**，它们照旧会绿。
 *
 * <p>除了**一项设置**，这里同样不配置数据库。核心的 datasource 由部署方交给它；H2 在测试 classpath 上，
 * 于是 Boot 自己的嵌入式数据库那条路提供了它，而宿主就是用自己那个替换掉这个形状（ADR-0017 D4）。**真正
 * 被设上的**是 `ddl-auto=validate`——宿主的姿态，也是「核心的迁移必须是对的」的理由：Hibernate 被告知去
 * 比对实体与 schema，不一致就**拒绝启动**。若用 Hibernate 的嵌入式默认值，Hibernate 会自己建 schema，
 * 迁移**根本不会被读到**，于是一条坏迁移会在这里通过、只在宿主里失败。
 *
 * <p>这里提供的属性，是核心**要求、且有意不给默认**的三项：审计链的 HMAC 密钥、委托密钥、以及它应答的受众。
 * 供给它们是重点而不是麻烦——**带内置默认值的秘密，不是部署方选的秘密**。核心拒绝携带的是**部署意见**：
 * 端口、上下文路径、数据源；而不是「部署方必须提供这三项」这个要求（ADR-0017 D5）。
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
     * Every route this runtime serves is inside the chain's scope, and the scope is not empty
     * (JV-35 F1).
     *
     * <p>The scope is derived from the controllers ({@code OwnedRoutes}), so this is not the guard
     * against a list drifting — there is no list. It is the guard against the derivation quietly
     * failing: if it resolved nothing, the chain would govern nothing, every route below would be
     * outside, and — without this — the suite would read green over a runtime that authenticates
     * nobody. That failure mode is why the derived routes are pinned first and compared second.
     *
     * <p>本运行时提供的每条路由都在链的范围之内，且范围**非空**（JV-35 F1）。
     *
     * <p>范围是从控制器推导的（{@code OwnedRoutes}），所以这不是「防清单漂移」的闸 —— 根本没有清单。它防
     * 的是**推导悄悄失效**：万一它解析出空集，链就会什么都不治理，下面每条路由都会落在范围之外 —— 而没有
     * 这条测试，套件会在「一个谁都不认证的运行时」上读起来是绿的。故先钉住推导出的路由、再比对。
     */
    @Test
    void everyRouteThisRuntimeServesIsInsideTheChainsScope() {
        RequestMatcher scope =
                ((DefaultSecurityFilterChain) context.getBean("governedEndpoints", SecurityFilterChain.class))
                        .getRequestMatcher();

        Set<String> served = new LinkedHashSet<>();
        for (RequestMappingHandlerMapping mapping :
                context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                if (!entry.getValue().getBeanType().getPackageName().startsWith(RUNTIME_PACKAGE)) {
                    continue;
                }
                served.addAll(patternsOf(entry.getKey()));
            }
        }

        // A vacuous pass is the failure mode this guard exists to prevent, so the derivation is pinned
        // before its result is trusted: if the handler lookup ever stops finding routes, this goes red
        // rather than the comparison below going quietly green over an empty set.
        assertTrue(served.containsAll(Set.of("/auth/me", "/ai/tool-effects/{id}", "/customers", "/error")),
                "the derivation found the routes this runtime serves: " + served);

        Set<String> outside = new LinkedHashSet<>();
        for (String pattern : served) {
            if (!scope.matches(new MockHttpServletRequest("GET", concrete(pattern)))) {
                outside.add(pattern);
            }
        }

        assertEquals(Set.of(), outside,
                "these routes are served but not governed — the owned-route scope will not cover them: " + outside);

        // And the other direction — the one a scope that is merely *wrong* fails in. These are routes
        // the host serves; if this chain claimed them, the host's own login page and screens would be
        // refused by a runtime with no business refusing them. Expressed as paths, not as a comparison
        // against the derivation, so it still holds if the scope is ever computed another way.
        for (String hostsRoute : List.of("/login", "/captchaImage", "/system/user/list", "/monitor/online")) {
            assertFalse(scope.matches(new MockHttpServletRequest("GET", hostsRoute)),
                    hostsRoute + " belongs to the host, and this runtime's chain must not claim it");
        }
    }

    private static final String RUNTIME_PACKAGE = "cn.com.keelbase.runtime";

    private static List<String> patternsOf(RequestMappingInfo info) {
        PathPatternsRequestCondition condition = info.getPathPatternsCondition();
        return condition == null ? List.of() : List.copyOf(condition.getPatternValues());
    }

    /** A pattern with its variables filled, so the matcher can be asked about a concrete request. */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^/}]*}", "x");
    }

    /**
     * Container failures are answered by this runtime's controller, not by Boot's — in a host too.
     *
     * <p>The prediction this pins is the seam record's "F1 附带发现": the core declares a bean
     * implementing {@link ErrorController}, Boot's own carries
     * {@code @ConditionalOnMissingBean(ErrorController.class)}, so wherever this core is assembled the
     * runtime's error shape answers 404s and container errors. In a host that is a real consequence and
     * not a detail: a client of the host reads {@code message} / {@code reason} / {@code nextStep} off
     * an error it used to get in the host's own shape. Asserted by name because that is the whole
     * claim — which controller is the one registered.
     *
     * 容器的失败由**本运行时**的控制器作答，而不是 Boot 的——在宿主里也一样。
     *
     * <p>这里钉住的就是缝记录那条「F1 附带发现」：核心声明了一个实现 `ErrorController` 的 bean，而 Boot 自己那个带
     * `@ConditionalOnMissingBean(ErrorController.class)`，故**凡装配了本核心处**，404 与容器错误都由运行时的错误形状作答。
     * 在宿主里这是一条**真后果**、不是细节：宿主的客户端会从它过去按宿主自己形状收到的错误里，读到 `message` / `reason` /
     * `nextStep`。按**名字**断言，因为全部主张就是「注册的是哪一个」。
     */
    @Test
    void containerFailuresAreAnsweredByThisRuntimesErrorController() {
        Map<String, ErrorController> registered = context.getBeansOfType(ErrorController.class);

        assertEquals(1, registered.size(),
                "one ErrorController is registered, and Boot's own stood down: " + registered.keySet());
        assertTrue(registered.values().iterator().next() instanceof WireErrorController,
                "and it is this runtime's, so the error body a caller reads is the wire contract");
    }

    /**
     * The core's tables exist in the deployed schema, and the core's migrations are what put them there.
     *
     * <p>Asked of the database rather than of a bean: this is the fact a host depends on, and it is not
     * observable from the context alone. The migration names are asserted as well as the tables, because
     * only the second half distinguishes the two ways the tables could have appeared — {@code validate}
     * fails loudly if they are missing, but it passes just as happily over tables Hibernate created for
     * itself, which is the state this test exists to keep out (ADR-0017 D4).
     *
     * <p>核心的表存在于被部署的 schema 里，而把它们建出来的是**核心自己的迁移**。
     *
     * <p>问的是数据库、不是某个 bean：这是宿主依赖的事实，而且**光看上下文看不出来**。表名与迁移名都断言，
     * 因为只有后半句能区分「这些表可能出现的两种方式」——表缺失时 `validate` 会响亮地失败，但它同样会
     * 欣然放过 Hibernate 给**自己**建的表，而后者正是这条测试要挡在外面的状态（ADR-0017 D4）。
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
