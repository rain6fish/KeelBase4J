// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.OrgMembershipScope;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.audit.OperationAuditLog;
import cn.com.keelbase.runtime.audit.OperationAuditLogRepository;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.authz.RuleDeniedException;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.web.AuditController;
import cn.com.keelbase.runtime.web.ConfirmationController;
import cn.com.keelbase.runtime.web.EffectController;
import cn.com.keelbase.runtime.web.MyConfirmationController;
import cn.com.keelbase.runtime.web.OperationAuditController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * Does the read face isolate one tenant's rows from another's? Four faces, one deployment, a real
 * PostgreSQL — and the answer recorded as what each attempt actually returned.
 *
 * <p>The deployment models two tenants as two users in two organizations. Neither the audit row, the
 * side-effect row, the confirmation row nor the operation-audit row carries a tenant or organization
 * column (the first test reads that off {@code information_schema}), so row-level tenant isolation
 * cannot be answered by a query: the only row fact these faces have is the caller's user id. What can
 * be measured is therefore the boundary the runtime does claim — the capability gate plus, where the
 * capability is owner-scoped, the row filter — and whether an ordinary caller, an owner-scoped
 * governance reader and a global reader each stay inside it.
 *
 * <p><b>Every face is asserted to agree with the capability it declares.</b> A non-global reader
 * sees its own rows on the audit chain list, object search, the confirmation queue, the operations
 * ledger and the side-effect ledger, whatever user the request names; an {@code AuditLog:read} /
 * {@code Confirmation:read} / {@code OperationAudit:read} / {@code ToolEffect:read} grant with an
 * owner field answers only the caller's rows. The global reader (administrator or read-only
 * auditor) is asserted to cross tenants as well — that is the roster the faces are designed to be,
 * not a defect.
 *
 * <p>Guarded on {@code KEELBASE_PG_URL}, like {@code PostgresMigrationTest}: without a PostgreSQL this
 * class is skipped rather than silently run on the H2 twin.
 *
 * <p>读面是否把一个租户的行与另一个租户隔开？四个面、一个部署、真的 PostgreSQL —— 答案记成每次尝试
 * **实际回出来的东西**。
 *
 * <p>这个部署把两个租户建模成两个组织里的两个用户。审计行、副作用行、确认行、运营审计行**都不带**租户列
 * 或组织列（第一条测试直接从 `information_schema` 读这件事），故行级的租户隔离**无法由查询回答**：这些面
 * 唯一有的行事实是调用者的 user id。于是能测的是运行时**确实声称**的那道边界 —— 能力闸，以及能力带主人
 * 条件时的行过滤 —— 以及普通调用方、带主人范围的治理读者、全局读者各自是否留在边界内。
 *
 * <p>**每个面都被断言与它声明的能力一致。** 非全局读者在审计链列表、对象检索、确认队列、操作审计与
 * 副作用台账上只看到本人的行，无论请求点名哪个用户；带 owner 字段的 `AuditLog:read` /
 * `Confirmation:read` / `OperationAudit:read` / `ToolEffect:read` 授予只答调用者本人的行。全局读者
 * （管理员或只读审计读者）跨租户读到，也被断言 —— 那是这些面**本来就是**的花名册，不是缺陷。
 *
 * <p>与 `PostgresMigrationTest` 一样由 `KEELBASE_PG_URL` 把守：没有 PostgreSQL 就跳过，而不是悄悄在 H2
 * 双胞胎上跑一遍。
 */
@SpringBootTest(classes = {CoreTestApplication.class, ReadFaceTenantIsolationTest.TwoTenantDeployment.class},
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
@EnabledIfEnvironmentVariable(named = "KEELBASE_PG_URL", matches = ".+")
class ReadFaceTenantIsolationTest {

    /** One run's suffix, so rows from a previous run in a shared database cannot answer for this one. */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private static final String TENANT_A = "tenant-a-" + RUN;
    private static final String TENANT_B = "tenant-b-" + RUN;
    private static final String TENANT_B_ORDINARY = "tenant-b-ordinary-" + RUN;
    private static final String ADMIN = "tenant-admin-" + RUN;
    private static final String AUDITOR = "tenant-auditor-" + RUN;

    private static final Principal ALICE = tenantPrincipal(TENANT_A, Principal.ROLE_USER, 1001L, 11L);
    private static final Principal BOB = tenantPrincipal(TENANT_B, Principal.ROLE_USER, 2002L, 21L);
    private static final Principal ORDINARY_B = tenantPrincipal(TENANT_B_ORDINARY,
            Principal.ROLE_USER, 2002L, 22L);
    private static final Principal ADMINISTRATOR = tenantPrincipal(ADMIN, Principal.ROLE_ADMIN, 9009L, 90L);
    private static final Principal AUDIT_READER = tenantPrincipal(AUDITOR, Principal.ROLE_USER, 9009L, 91L);

    private static final Map<String, Principal> DIRECTORY = Map.of(
            "local:" + TENANT_A, ALICE,
            "local:" + TENANT_B, BOB,
            "local:" + TENANT_B_ORDINARY, ORDINARY_B,
            "local:" + ADMIN, ADMINISTRATOR,
            "local:" + AUDITOR, AUDIT_READER);

    /**
     * Two tenants and the three kinds of reader this runtime can have: an ordinary caller with no
     * governance grant, an owner-scoped grant (the tier-B shape), and the global readers.
     *
     * <p>两租户与运行时可能有的三类读者：没有任何治理授予的普通调用方、带主人范围的授予（档 B 的形状），
     * 以及全局读者。
     */
    @TestConfiguration
    static class TwoTenantDeployment {

        @Bean
        IdentityResolver twoTenantDirectory() {
            return evidence -> {
                String subject = evidence.attribute(IdentityEvidence.SUBJECT);
                Principal principal = subject == null ? null : DIRECTORY.get(subject);
                if (principal == null) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "this deployment does not know that subject");
                }
                return principal;
            };
        }

        /**
         * The tier-B rule source. Bob's governance reads are deliberately owner-scoped: if the faces
         * honoured that scope, Bob would see only his own rows.
         *
         * <p>档 B 规则源。bob 的治理读**刻意**按主人收窄：若这些面兑现那个范围，bob 只会看到本人的行。
         */
        @Bean
        AuthorizationRules twoTenantRules() {
            return new AuthorizationRules() {
                @Override
                public List<Rule> rulesFor(Principal principal) {
                    if (AUDITOR.equals(principal.userId())) {
                        return GovernanceReadSubjects.ALL.stream()
                                .map(subject -> new Rule(subject, "read", null))
                                .toList();
                    }
                    if (TENANT_B.equals(principal.userId())) {
                        List<Rule> rules = new ArrayList<>(super.rulesFor(principal));
                        rules.add(new Rule(GovernanceReadSubjects.AUDIT_LOG, "read", "userId"));
                        rules.add(new Rule(GovernanceReadSubjects.CONFIRMATION, "read", "operatorId"));
                        rules.add(new Rule(GovernanceReadSubjects.OPERATION_AUDIT, "read", "userId"));
                        rules.add(new Rule(GovernanceReadSubjects.TOOL_EFFECT, "read", "userId"));
                        return rules;
                    }
                    return super.rulesFor(principal);
                }
            };
        }
    }

    @DynamicPropertySource
    static void pointAtTheRealPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("KEELBASE_PG_URL"));
        registry.add("spring.datasource.username", () -> envOrDefault("KEELBASE_PG_USER", "postgres"));
        registry.add("spring.datasource.password", () -> envOrDefault("KEELBASE_PG_PASSWORD", "postgres"));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Principal tenantPrincipal(String userId, String role, long orgId, long deptId) {
        return new Principal(userId, role, null, new OrgMembershipScope(
                new OrgMembershipScope.Org(orgId, "tenant-" + orgId, null), "member", deptId, List.of()));
    }

    @Autowired
    AuditController auditController;

    @Autowired
    ConfirmationController confirmationController;

    @Autowired
    MyConfirmationController myConfirmations;

    @Autowired
    EffectController effectController;

    @Autowired
    OperationAuditController operationAuditController;

    @Autowired
    OperationAuditLogRepository operationRows;

    @Autowired
    AuditService audit;

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    ConfirmationStore confirmations;

    @Autowired
    PermissionAuthorizer authorizer;

    @Autowired
    JdbcTemplate jdbc;

    private String objectKey;
    private Long aliceAuditId;
    private Long bobAuditId;
    private Long aliceEffectId;
    private Long bobEffectId;
    private String aliceToken;
    private String bobToken;
    private Long aliceOperationId;
    private Long bobOperationId;

    @BeforeEach
    void twoTenantsLeaveRows() {
        String marker = UUID.randomUUID().toString();
        objectKey = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        String execution = "exec-" + marker;
        String bobExecution = "exec-" + marker + "-b";
        String args = "{\"run\":\"" + marker + "\"}";
        String bobArgs = "{\"run\":\"" + marker + "-b\"}";

        SideEffect aliceEffect = sideEffects.record(ALICE, "create_followup", "crm_customer",
                objectKey, args, "local_compensate", null, "{\"ok\":true}", null, execution);
        aliceEffectId = aliceEffect.getId();
        bobEffectId = sideEffects.record(BOB, "create_followup", "crm_customer", objectKey,
                bobArgs, "local_compensate", null, "{\"ok\":true}", null, bobExecution).getId();

        aliceAuditId = audit.append("tool_call", TENANT_A, "tenant A row " + marker, false,
                "executed", "create_followup", null, null, execution).getId();
        bobAuditId = audit.append("tool_call", TENANT_B, "tenant B row " + marker, false,
                "executed", "create_followup", null, null, bobExecution).getId();

        aliceToken = confirmations.create(ALICE, "create_followup", args, "R3",
                ConfirmationMode.IMMEDIATE).getToken();
        bobToken = confirmations.create(BOB, "create_followup", bobArgs, "R3",
                ConfirmationMode.IMMEDIATE).getToken();

        aliceOperationId = operationRows.save(new OperationAuditLog(TENANT_A, "UPDATE", "PUT",
                "/crm/customer/1", "1", 200, "127.0.0.1", "isolation-test")).getId();
        bobOperationId = operationRows.save(new OperationAuditLog(TENANT_B, "UPDATE", "PUT",
                "/crm/customer/2", "2", 200, "127.0.0.1", "isolation-test")).getId();
    }

    @AfterEach
    void forgetTheCaller() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The premise, measured rather than assumed: no read-face row carries a tenant or organization
     * column, so a row-level tenant gate has nothing to read. This is why the rest of the class can
     * only measure the user-id boundary.
     *
     * <p>前提是**量出来**的、不是假定的：没有任何读面行带租户列或组织列，故行级租户闸没有可读的东西。
     * 这正是本类其余部分只能测 user-id 边界的原因。
     */
    @Test
    void noGovernanceRowCarriesATenantOrOrganisationColumn() {
        for (String table : List.of("ai_audit_logs", "side_effects", "confirmation_requests",
                "operation_audit_logs")) {
            List<String> columns = jdbc.queryForList(
                    "select column_name from information_schema.columns "
                            + "where table_schema = current_schema() and table_name = ?",
                    String.class, table);
            assertFalse(columns.contains("tenant") || columns.contains("tenant_id")
                            || columns.contains("org") || columns.contains("org_id"),
                    table + " carries a tenant/org column this class assumed absent: " + columns);
            assertTrue(columns.contains("user_id") || columns.contains("operator_id"),
                    table + " carries the caller identity, the only row fact these faces have: " + columns);
        }
    }

    /**
     * The baseline: a caller with no governance grant is refused the audit and confirmation faces, and
     * the two self-service faces narrow to their own rows. This is isolation by not being granted the
     * capability at all, not by a tenant filter.
     *
     * <p>基线：没有任何治理授予的调用方在审计面与确认面上被拒，而两个自助面收窄到本人的行。这是**没有
     * 被授予能力**造成的隔离，不是租户过滤造成的。
     */
    @Test
    void anOrdinaryCallerWithoutAGovernanceGrantIsRefusedOrNarrowedOnEveryFace() {
        as(ORDINARY_B);

        assertRefused(() -> auditController.logs(null, null, null, null, null, null, null, null, null,
                200, 0), "the audit chain list");
        assertRefused(() -> confirmationController.list(1, 100), "the confirmation queue");
        assertRefused(() -> operationAuditController.logs(1, 100), "the operations ledger");

        assertFalse(effectIds(null).contains(aliceEffectId),
                "the ledger narrows an ordinary caller to its own rows");
        assertFalse(tokens(myConfirmations.mine(null)).contains(aliceToken),
                "the operator's own confirmations are narrowed to the caller");
    }

    /**
     * An owner-scoped audit grant stays inside the caller's rows — on the list, on an unfiltered
     * page and on object search, which drives the ledger road. Naming another tenant's user does not
     * widen it: the capability says {@code own}, and the face agrees.
     *
     * <p>带主人范围的审计授予留在调用者本人的行内 —— 列表、不过滤的一页、以及走台账路的对象检索都一样。
     * 点名另一租户的用户放不宽它：能力说 {@code own}，而这个面同意了。
     */
    @Test
    void anOwnerScopedAuditGrantNarrowsToTheCallersOwnRows() {
        assertEquals(PermissionCapabilityList.SCOPE_OWN,
                authorizer.capabilityFor(BOB, GovernanceReadSubjects.AUDIT_LOG).scope(),
                "the capability list promises a row-level owner condition");

        as(BOB);

        List<Map<String, Object>> named = auditController.logs(TENANT_A, null, null, null, null, null,
                null, null, null, 200, 0);
        assertFalse(ids(named).contains(aliceAuditId),
                "an own-scoped reader cannot reach another tenant's rows by naming them");
        assertTrue(ids(named).contains(bobAuditId), "the caller's own rows are still there");
        assertTrue(named.stream().allMatch(row -> TENANT_B.equals(row.get("userId"))),
                "and every row is the caller's: " + named);

        List<Map<String, Object>> unfiltered = auditController.logs(null, null, null, null, null,
                null, null, null, null, 200, 0);
        assertFalse(ids(unfiltered).contains(aliceAuditId),
                "an unfiltered page is narrowed to the caller too");
        assertTrue(ids(unfiltered).contains(bobAuditId));

        List<Map<String, Object>> byObject = auditController.logs(null, null, null, "crm_customer",
                objectKey, null, null, null, null, 200, 0);
        assertFalse(ids(byObject).contains(aliceAuditId),
                "object search cannot reach the other tenant's write through the effect ledger");
        assertTrue(ids(byObject).contains(bobAuditId),
                "the caller's own write on the same object is still found");
    }

    /**
     * An owner-scoped confirmation grant answers only the caller's queue. The operator's own face
     * stays narrow as before; the queue now agrees with it.
     *
     * <p>带主人范围的确认授予只答调用者本人的队列。旁边的「本人面」照旧收窄；队列现在与它一致。
     */
    @Test
    void anOwnerScopedConfirmationGrantAnswersOnlyTheCallersOwnQueue() {
        assertEquals(PermissionCapabilityList.SCOPE_OWN,
                authorizer.capabilityFor(BOB, GovernanceReadSubjects.CONFIRMATION).scope(),
                "the capability list promises a row-level owner condition");

        as(BOB);

        List<String> queued = queueTokens();
        assertFalse(queued.contains(aliceToken),
                "the queue must not list another tenant's pending confirmation");
        assertTrue(queued.contains(bobToken), "the caller's own confirmation is still there");
        assertFalse(tokens(myConfirmations.mine(null)).contains(aliceToken),
                "the operator's own face stays narrow");
    }

    /**
     * The operations ledger follows the same rule as the AI chain: an owner-scoped grant sees only
     * the caller's rows.
     *
     * <p>操作审计账与 AI 链同一条规则：带主人范围的授予只看本人的行。
     */
    @Test
    void anOwnerScopedOperationAuditGrantNarrowsToTheCallersOwnRows() {
        assertEquals(PermissionCapabilityList.SCOPE_OWN,
                authorizer.capabilityFor(BOB, GovernanceReadSubjects.OPERATION_AUDIT).scope(),
                "the capability list promises a row-level owner condition");

        as(BOB);

        List<Long> visible = operationIds();
        assertFalse(visible.contains(aliceOperationId),
                "another tenant's operation row must not be visible");
        assertTrue(visible.contains(bobOperationId), "the caller's own row is there");
    }

    /**
     * The ledger applies the same rule: a non-global reader sees its own rows, and naming another
     * user does not lift the filter.
     *
     * <p>台账同一条规则：非全局读者看到本人的行，点名另一个用户也抬不掉过滤。
     */
    @Test
    void theSideEffectLedgerHonoursTheOwnerScopeToo() {
        assertEquals(PermissionCapabilityList.SCOPE_OWN,
                authorizer.capabilityFor(BOB, GovernanceReadSubjects.TOOL_EFFECT).scope());

        as(BOB);

        assertFalse(effectIds(null).contains(aliceEffectId),
                "the ledger narrows a non-global reader to its own rows");
        assertFalse(effectIds(TENANT_A).contains(aliceEffectId),
                "naming another tenant's user does not lift the row filter");
        assertTrue(effectIds(null).contains(bobEffectId), "and the reader's own row is there");
    }

    /**
     * The cross-tenant read that is intended: an administrator or a read-only auditor reads all three
     * faces across tenants. The capability gate is the whole boundary here, and it is a roster by
     * design — the test records it so a future narrowing is a deliberate decision rather than a
     * silent change.
     *
     * <p>**有意**的跨租户读：管理员或只读审计读者跨租户读到全部三个面。这里整道边界就是能力闸，而这些
     * 面按设计就是花名册 —— 测试把它记下来，好让将来任何收窄是一个**明确的决定**、而不是悄悄的变化。
     */
    @Test
    void aGlobalReaderReadsAcrossTenantsByDesign() {
        for (Principal reader : List.of(AUDIT_READER, ADMINISTRATOR)) {
            as(reader);

            assertTrue(ids(auditController.logs(TENANT_A, null, null, null, null, null, null, null,
                    null, 200, 0)).contains(aliceAuditId),
                    reader.userId() + " reads the chain across tenants (roster by design)");
            assertTrue(ids(auditController.logs(null, null, null, "crm_customer", objectKey, null,
                    null, null, null, 200, 0)).contains(aliceAuditId),
                    reader.userId() + " reaches the object across tenants (roster by design)");
            assertTrue(queueTokens().contains(aliceToken),
                    reader.userId() + " reads the queue across tenants (roster by design)");
            assertTrue(operationIds().contains(aliceOperationId),
                    reader.userId() + " reads the operations ledger across tenants (roster by design)");
            assertTrue(effectIds(null).contains(aliceEffectId),
                    reader.userId() + " reads the ledger across tenants (global read by design)");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** Put the given principal where the authentication filter would have put it. */
    private static void as(Principal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedSubject("local:" + principal.userId(), null), null, List.of()));
    }

    private static void assertRefused(Runnable call, String what) {
        RuleDeniedException refused = assertThrows(RuleDeniedException.class, call::run);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), what);
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ids.add(((Number) row.get("id")).longValue());
        }
        return ids;
    }

    private List<Long> effectIds(String requestedUser) {
        Map<String, Object> page = effectController.list(requestedUser, null, 1, 100);
        List<Long> ids = new ArrayList<>();
        for (Object item : (List<?>) page.get("items")) {
            ids.add(((Number) ((Map<?, ?>) item).get("id")).longValue());
        }
        return ids;
    }

    private List<Long> operationIds() {
        Map<String, Object> page = operationAuditController.logs(1, 100);
        List<Long> ids = new ArrayList<>();
        for (Object item : (List<?>) page.get("items")) {
            ids.add(((Number) ((Map<?, ?>) item).get("id")).longValue());
        }
        return ids;
    }

    private List<String> queueTokens() {
        Map<String, Object> page = confirmationController.list(1, 100);
        return tokens((List<?>) page.get("items"));
    }

    private static List<String> tokens(List<?> items) {
        List<String> tokens = new ArrayList<>();
        for (Object item : items) {
            tokens.add((String) ((Map<?, ?>) item).get("token"));
        }
        return tokens;
    }
}
