// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.GovernancePolicyHistoryRepository;
import cn.com.keelbase.runtime.governance.GovernancePolicyRepository;
import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * JV-63 B: a read-only audit reader reaches the governance read faces through the capability seam,
 * reaches nothing operational, and cannot approve or execute.
 *
 * <p>The deployment here is the one B describes: a tier-B rule source that answers by <em>caller</em>
 * and grants the auditor nothing but {@code read} on the governance subjects. The switch
 * ({@link #GRANTED}) is the reverse proof — with the grant removed the same principal is refused the
 * same routes, and the grant put back restores them. Nothing in the runtime knows the name "auditor";
 * that is the point of not designing a role model.
 *
 * <p>Two assertions are deliberately shaped differently from the rest. The side-effect list is
 * <b>narrowed</b> rather than refused when the grant is removed ({@code 200} with only the caller's own
 * rows): every authenticated caller could list their own effects before JV-63, and taking that away
 * would be a regression, not the reverse of the grant. And the operator's own decide entry answers a
 * no-op body rather than {@code 403} for somebody else's token — that is the existing anti-probing
 * behaviour, not a hole: it moves nothing.
 *
 * <p>JV-63 B：一个只读审计读者经能力缝够到治理读面，够不到运营面，也不能审批或执行。
 *
 * <p>这里的部署正是 B 描述的那个：一个**按调用者**作答的档 B 规则源，除了治理 subject 上的
 * {@code read} 之外什么都不给审计读者。开关（{@link #GRANTED}）就是**反向证明**——撤掉授予后，同一个
 * principal 在同样的路线上被拒；放回去就恢复。运行时**不知道**「auditor」这个名字——这正是「不设计角色模型」
 * 的意义。
 *
 * <p>有两条断言刻意是别的形状。撤掉授予时，副作用列表是**收窄**而不是拒绝（{@code 200}、只剩调用方自己的
 * 行）：JV-63 之前每个已认证调用方都能列自己的副作用，拿走它是**回归**、不是撤销授予的逆。而操作者自己那条
 * 裁决入口对别人的 token 答的是**空操作**体、不是 {@code 403}——那是既有的防探测行为，不是洞：它什么都不移动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class AuditReaderSeamTest {

    /** The audit reader this deployment declares. Not a contract role — a principal the rules name. */
    private static final String AUDITOR = "auditor";

    /** The reverse proof's switch: with it off, the auditor's every grant disappears. */
    private static final AtomicBoolean GRANTED = new AtomicBoolean(true);

    /** The tier-B answer for the audit reader: read, and only read, on the governance read faces. */
    private static final List<AuthorizationRules.Rule> AUDIT_READS = GovernanceReadSubjects.ALL.stream()
            .map(subject -> new AuthorizationRules.Rule(subject, "read", null))
            .toList();

    @TestConfiguration
    static class AuditReaderDeployment {

        /**
         * A deployment's own rule source, answering by caller — the tier-B shape JV-63 relies on.
         * Administrators and ordinary users keep the tier-A rules; the auditor gets the reads above.
         *
         * 部署方自己的规则源，按调用者作答——JV-63 依赖的档 B 形状。管理员与普通用户照旧拿档 A 的规则；
         * 审计读者拿上面那些读。
         */
        @Bean
        AuthorizationRules auditReaderRules() {
            return new AuthorizationRules() {
                @Override
                public List<Rule> rulesFor(Principal principal) {
                    if (AUDITOR.equals(principal.userId())) {
                        return GRANTED.get() ? AUDIT_READS : List.of();
                    }
                    return super.rulesFor(principal);
                }
            };
        }

        /** The deployment's directory, so the auditor is a principal this deployment knows. */
        @Bean
        IdentityResolver auditReaderIdentities() {
            return evidence -> {
                String subject = evidence.attribute(IdentityEvidence.SUBJECT);
                if (("local:" + AUDITOR).equals(subject)) {
                    return new Principal(AUDITOR, Principal.ROLE_USER);
                }
                if ("local:alice".equals(subject)) {
                    return new Principal("alice", Principal.ROLE_USER);
                }
                if ("local:carol".equals(subject)) {
                    return new Principal("carol", Principal.ROLE_ADMIN);
                }
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "this deployment does not know that subject");
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    ConfirmationStore confirmations;

    @Autowired
    ConfirmationRequestRepository confirmationRows;

    @Autowired
    GovernancePolicyRepository policies;

    @Autowired
    GovernancePolicyHistoryRepository policyHistory;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /** The rows this run put there, so the assertions are about them and not about a shared table. */
    private String effectOwnerA;
    private String effectOwnerB;
    private Long effectAId;
    private Long effectBId;
    private String confirmOwnerA;
    private String confirmOwnerB;
    private String confirmationToken;

    @BeforeEach
    void aQueueTwoCallersLeftBehind() {
        GRANTED.set(true);
        String run = UUID.randomUUID().toString();
        effectOwnerA = "effect-a-" + run;
        effectOwnerB = "effect-b-" + run;
        effectAId = sideEffects.record(new Principal(effectOwnerA, Principal.ROLE_USER), "create_followup",
                "follow_up", 1L, "{\"run\":\"" + run + "-a\"}", "local_compensate").getId();
        effectBId = sideEffects.record(new Principal(effectOwnerB, Principal.ROLE_USER), "create_followup",
                "follow_up", 2L, "{\"run\":\"" + run + "-b\"}", "local_compensate").getId();

        confirmOwnerA = "confirm-a-" + run;
        confirmOwnerB = "confirm-b-" + run;
        confirmationToken = confirmations.create(new Principal(confirmOwnerA, Principal.ROLE_USER),
                "create_followup", "{}", "R3", ConfirmationMode.IMMEDIATE).getToken();
        confirmations.create(new Principal(confirmOwnerB, Principal.ROLE_USER),
                "create_followup", "{}", "R3", ConfirmationMode.IMMEDIATE);

        audit.append("tool_call", effectOwnerA, "jv63 reader row", false, "executed", "create_followup");
    }

    /**
     * The policy database is shared and another class asserts the inventory's defaults (the same
     * reason {@code GovernancePolicyTest} clears after itself).
     *
     * 策略库是共用的，而另一个类断言工具清单的**默认值**（与 {@code GovernancePolicyTest} 事后清理的
     * 理由相同）。
     */
    @AfterEach
    void clearPolicy() {
        policies.deleteAll();
        policyHistory.deleteAll();
    }

    // ── positive: the read faces answer the audit reader ──────────────────────────────────────────

    @Test
    void theAuditReaderReachesEveryGovernanceReadFace() {
        for (String path : List.of("/audit/verify", "/audit/logs?limit=5", "/audit/coverage",
                "/audit/operations/logs", "/ai/tools", "/ai/governance/policy",
                "/ai/tool-effects?limit=100", "/ai/confirmations?limit=100")) {
            assertEquals(200, get(path, AUDITOR).getStatusCode().value(),
                    "an audit reader must reach " + path);
        }
    }

    /**
     * AW-3 on the host side: the statement is a data object, not prose — it names the registry it
     * was judged against, the scope it was read over, and the gaps that apply.
     *
     * <p>宿主侧 AW-3：声明是**数据对象**、不是散文 —— 它点名判断所依据的登记表、读过的口径、以及适用的缺口。
     */
    @Test
    void theCoverageStatementDeclaresItsRegistryScopeAndGaps() {
        Map<String, Object> statement = Envelopes.data(body(get("/audit/coverage", AUDITOR)));

        assertEquals("period", ((Map<?, ?>) statement.get("scope")).get("kind"),
                "a period read is declared as one: " + statement);
        assertTrue(((String) statement.get("registryRevision")).length() == 12,
                "the statement is pinned to a registry revision: " + statement);
        assertTrue(((List<?>) statement.get("gaps")).stream()
                        .anyMatch(gap -> "chain-tail-deletion".equals(((Map<?, ?>) gap).get("id"))),
                "a chain without an external anchor must say so: " + statement);
    }

    @Test
    void theAuditReaderSeesEveryCallersEffectsAndConfirmations() {
        Map<String, Object> effects = Envelopes.data(body(get("/ai/tool-effects?limit=100", AUDITOR)));
        // ⚠ B5 ②: the auditor no longer receives `userId`, so the cross-caller assertion moves to `id` —
        // the two rows were written by two different callers, and both being on this page proves "global".
        //
        // ⚠ B5 ②：审计读者**不再**收到 `userId`，故跨调用方的断言改按 `id` 做 —— 两行各由不同的
        // 调用方写下，两行都在这一页里就证明了「全局」。
        List<Long> pageEffectIds = effectIds((List<?>) effects.get("items"));
        assertTrue(pageEffectIds.contains(effectAId) && pageEffectIds.contains(effectBId),
                "the global read covers every caller's effects: " + pageEffectIds);

        Map<String, Object> queue = Envelopes.data(body(get("/ai/confirmations?limit=100", AUDITOR)));
        List<?> tokens = ((List<?>) queue.get("items")).stream()
                .map(item -> ((Map<?, ?>) item).get("token"))
                .toList();
        assertTrue(tokens.contains(confirmationToken),
                "the queue is read across callers, not narrowed to the reader: " + tokens);
    }

    /**
     * JV-63 B5 ② (decided 2026-10-20): a read-only reader's rows omit the caller linkage, the
     * administrator's keep every one of the 19 fields, and nothing else moves.
     *
     * <p>JV-63 B5 ②（2026-10-20 裁）：只读读者的每一行**不收**调用方关联；管理员的 19 个字段一个不少；
     * 其余一律不动。
     */
    @Test
    void theReadOnlyReaderGetsNoCallerLinkageAndTheAdministratorKeepsEveryField() {
        Map<String, Object> readerPage = Envelopes.data(body(get("/ai/tool-effects?limit=100", AUDITOR)));
        Map<String, Object> adminPage = Envelopes.data(body(get("/ai/tool-effects?limit=100", "carol")));

        Map<?, ?> readerItem = itemWithId((List<?>) readerPage.get("items"), effectAId);
        Map<?, ?> adminItem = itemWithId((List<?>) adminPage.get("items"), effectAId);

        assertFalse(readerItem.containsKey("userId"),
                "the read-only reader gets no caller identity: " + readerItem);
        assertFalse(readerItem.containsKey("conversationId"),
                "nor the conversation the write belongs to: " + readerItem);

        assertEquals(effectOwnerA, adminItem.get("userId"),
                "the administrator still sees who the write was for");
        assertTrue(adminItem.containsKey("conversationId"),
                "and the conversation key is still there: " + adminItem);

        Set<Object> expectedReaderKeys = new LinkedHashSet<>(adminItem.keySet());
        expectedReaderKeys.remove("userId");
        expectedReaderKeys.remove("conversationId");
        assertEquals(expectedReaderKeys, new LinkedHashSet<>(readerItem.keySet()),
                "only the caller linkage is removed — no other field moves");

        assertEquals(19, adminItem.size(),
                "the administrator's projection is still the 19 fields of the frozen traceItem");
        assertEquals(17, readerItem.size(), "and the reader's is the same 17");
    }

    /**
     * B9: the capability list no longer calls a read-only identity "a normal user who may manage their
     * own resources" — a description whose second half is false.
     *
     * B9：能力清单不再把一个只读身份说成「可管理本人拥有资源的普通用户」——那句话的后半是假的。
     */
    @Test
    void theBasisNamesWhatAnAuditReaderActuallyIs() {
        Map<String, Object> described = Envelopes.data(body(get("/auth/me/permissions", AUDITOR)));

        assertEquals(PermissionCapabilityList.BASIS_AUDITOR, described.get("basis"),
                "a read-only identity gets its own basis, not the one for a user who may manage");
        assertEquals(PermissionCapabilityList.ROLE_USER, described.get("role"),
                "and the role vocabulary is untouched — there is no auditor role (JV-63 chose B)");
    }

    // ── negative: no operational surface ──────────────────────────────────────────────────────────

    @Test
    void theAuditReaderCannotReadOperationalData() {
        assertEquals(403, get("/customers", AUDITOR).getStatusCode().value(),
                "the operational surface is not one of the granted subjects");
    }

    // ── reverse proof: remove the grant, the same principal is refused ────────────────────────────

    @Test
    void removingTheReadGrantTurnsTheSamePrincipalAwayAndPuttingItBackRestores() {
        assertEquals(200, get("/audit/logs", AUDITOR).getStatusCode().value(), "granted first");

        GRANTED.set(false);
        for (String path : List.of("/audit/verify", "/audit/logs", "/audit/coverage",
                "/audit/operations/logs", "/ai/tools", "/ai/governance/policy", "/ai/confirmations")) {
            assertEquals(403, get(path, AUDITOR).getStatusCode().value(),
                    "without the grant `read` is not held, so " + path + " must refuse");
        }
        // The side-effect list cannot go 403 without regressing every caller's own view: it narrows.
        //
        // 副作用列表不能变 403，否则会回归每个调用方自己的视图：它收窄。
        Map<String, Object> ownOnly = Envelopes.data(body(get("/ai/tool-effects?limit=100", AUDITOR)));
        assertFalse(userIds((List<?>) ownOnly.get("items")).contains(effectOwnerA),
                "without the grant the auditor no longer sees other callers' effects: " + ownOnly);

        GRANTED.set(true);
        assertEquals(200, get("/audit/logs", AUDITOR).getStatusCode().value(),
                "putting the grant back restores the read");
    }

    // ── B8(a): the audit reader cannot approve or execute ─────────────────────────────────────────

    @Test
    void theAuditReaderCannotApproveOrExecute() {
        assertEquals(403, post("/ai/confirmations/" + confirmationToken + "/approve-by", AUDITOR,
                "{\"decision\":\"approve\"}").getStatusCode().value(),
                "answering an approval stays an administrator's act");
        assertEquals(403, post("/ai/confirmations/" + confirmationToken + "/retry-execution", AUDITOR,
                "").getStatusCode().value(),
                "and so does re-running an execution");
        assertEquals(403, post("/ai/confirmations/" + confirmationToken, AUDITOR,
                "{\"decision\":\"approve\"}").getStatusCode().value(),
                "approving somebody else's confirmation is refused by the engine's own operator check");

        assertEquals("pending", confirmationRows.findByToken(confirmationToken).orElseThrow().getStatus(),
                "and none of the refusals moved the row");
    }

    // ── no regression: administrators and ordinary callers behave as before ──────────────────────

    @Test
    void administratorsStillReadEverythingAndOrdinaryCallersAreStillRefusedTheAudit() {
        for (String path : List.of("/audit/verify", "/audit/logs", "/audit/operations/logs",
                "/ai/tools", "/ai/governance/policy", "/ai/tool-effects", "/ai/confirmations")) {
            assertEquals(200, get(path, "carol").getStatusCode().value(),
                    "the administrator reaches " + path + " as before the seam");
        }

        for (String path : List.of("/audit/verify", "/audit/logs", "/audit/coverage",
                "/audit/operations/logs", "/ai/tools", "/ai/governance/policy",
                "/ai/confirmations")) {
            assertEquals(403, get(path, "alice").getStatusCode().value(),
                    "an ordinary caller is still refused " + path);
        }
        assertEquals(200, get("/customers", "alice").getStatusCode().value(),
                "and their own operational read is unchanged");
        assertEquals(200, get("/ai/tool-effects", "alice").getStatusCode().value(),
                "their own effects list is unchanged");
    }

    @Test
    void theWriteFaceStaysAdministratorsOnly() {
        String policy = "{\"tools\":{},\"audit\":{\"granularity\":\"write\"}}";

        assertEquals(403, put("/ai/governance/policy", AUDITOR, policy).getStatusCode().value(),
                "read on the policy is not write on the policy (B4)");
        assertEquals(200, put("/ai/governance/policy", "carol", policy).getStatusCode().value(),
                "the administrator still writes it");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static List<Long> effectIds(List<?> rows) {
        List<Long> ids = new ArrayList<>();
        for (Object row : rows) {
            ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
        }
        return ids;
    }

    /** One item by effect id; absent is a failure, not a null the caller could mistake for a reading. */
    private static Map<?, ?> itemWithId(List<?> rows, Long id) {
        for (Object row : rows) {
            Map<?, ?> item = (Map<?, ?>) row;
            if (id.equals(((Number) item.get("id")).longValue())) {
                return item;
            }
        }
        throw new AssertionError("no effect item with id " + id + " on the page: " + rows);
    }

    private static List<String> userIds(List<?> rows) {
        List<String> owners = new ArrayList<>();
        for (Object row : rows) {
            Object owner = ((Map<?, ?>) row).get("userId");
            if (owner != null) {
                owners.add(owner.toString());
            }
        }
        return owners;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> response) {
        assertEquals(200, response.getStatusCode().value(), "expected a served answer: " + response);
        return (Map<String, Object>) Json.parse(response.getBody());
    }

    private ResponseEntity<String> get(String path, String user) {
        return rest.exchange(URI.create(path), HttpMethod.GET,
                new HttpEntity<>(headers(user)), String.class);
    }

    private ResponseEntity<String> post(String path, String user, String body) {
        return rest.exchange(URI.create(path), HttpMethod.POST,
                new HttpEntity<>(body, headers(user)), String.class);
    }

    private ResponseEntity<String> put(String path, String user, String body) {
        return rest.exchange(URI.create(path), HttpMethod.PUT,
                new HttpEntity<>(body, headers(user)), String.class);
    }

    private HttpHeaders headers(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return headers;
    }
}
