// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.protocol.WireSchemas;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.GateDecision;
import cn.com.keelbase.runtime.governance.GovernancePolicyHistoryRepository;
import cn.com.keelbase.runtime.governance.GovernancePolicyRepository;
import cn.com.keelbase.runtime.governance.GovernanceService;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The governance policy: the frozen object on the wire, and what a written policy does.
 *
 * <p>Two halves, held together because a policy that is served but not enforced is the failure this
 * surface exists to avoid. The first half holds the endpoint to the frozen {@code governance-policy}
 * object (as every other wire object here is held to its own), including that {@code revision} is a
 * content fingerprint rather than a counter. The second half holds the enforcement: a disabled tool, a
 * gate mode and a role allowlist each change the gate the engine runs every call through, and a
 * submission naming something this deployment cannot apply comes back as a refusal rather than being
 * stored and ignored.
 *
 * <p>Everything written here is deleted afterwards ({@link #clearPolicy}), because the test database is
 * shared and another class asserts the inventory's defaults.
 *
 * 治理策略：线缆上的冻结对象，以及一份写下的策略做了什么。
 *
 * <p>两半合在一处，是因为「被服务、却不被施加」的策略正是这条面生来要避免的失败。前半把端点钉在冻结的
 * `governance-policy` 对象上（如同这里的每一个 wire 对象都被钉在自己的那份上），其中一项是 `revision` 是
 * **内容指纹**、不是计数。后半钉住**施加**：被禁用的工具、门控档位与角色白名单各自改变引擎每次调用都要过的
 * 那道闸，而一份点名了本部署施加不了的东西的提交，会得到一个**拒绝**，不是被存下、再被忽略。
 *
 * <p>这里写下的每一样都在事后删除（{@link #clearPolicy}），因为测试库是**共用**的、而另一个类断言的是
 * 工具清单的**默认值**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class GovernancePolicyTest {

    private static final Principal ALICE = new Principal("alice", "user");
    private static final Principal CAROL = new Principal("carol", "admin");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    GovernanceService governance;

    @Autowired
    ToolRegistry registry;

    @Autowired
    AuditService audit;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    GovernancePolicyRepository policies;

    @Autowired
    GovernancePolicyHistoryRepository history;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /** The database is shared, so a policy left behind would follow this class into the next one. */
    @AfterEach
    void clearPolicy() {
        policies.deleteAll();
        history.deleteAll();
    }

    // ---- the wire ----

    @Test
    void thePolicyAnswersTheFrozenObjectAndNothingWasDeclared() {
        Map<?, ?> policy = Vectors.map(getPolicy("carol"));

        WireSchemas.assertConformsTo(policy, "governance-policy");
        assertEquals(Map.of(), policy.get("tools"), "nothing is declared about any tool yet: " + policy);
        assertEquals(Map.of("granularity", "all"), policy.get("audit"),
                "the frozen default granularity, not an absence: " + policy);
        assertEquals(12, String.valueOf(policy.get("revision")).length(),
                "the revision is the frozen object's first-twelve-hex fingerprint: " + policy);
    }

    @Test
    void aWrittenPolicyComesBackAndItsRevisionIsContentNotACounter() {
        Map<String, Object> written = Vectors.map(putPolicy("carol", Map.of(
                "tools", Map.of("create_followup", Map.of("mode", "auto")),
                "audit", Map.of("granularity", "write"))));

        WireSchemas.assertConformsTo(written, "governance-policy");
        assertEquals(Map.of("mode", "auto"),
                Vectors.map(Vectors.map(written.get("tools")).get("create_followup")),
                "what was written is what comes back: " + written);

        Map<?, ?> read = Vectors.map(getPolicy("carol"));
        assertEquals(written.get("revision"), read.get("revision"), "a read is the same policy: " + read);
        assertEquals(written.get("tools"), read.get("tools"), "and the same overrides: " + read);

        String same = revisionOf(putPolicy("carol", Map.of(
                "tools", Map.of("create_followup", Map.of("mode", "auto")),
                "audit", Map.of("granularity", "write"))));
        assertEquals(written.get("revision"), same,
                "the same content fingerprints the same — a revision is not a counter");

        String changed = revisionOf(putPolicy("carol", Map.of(
                "tools", Map.of("create_followup", Map.of("mode", "confirm")),
                "audit", Map.of("granularity", "write"))));
        assertNotEquals(written.get("revision"), changed, "a change is a new fingerprint");
    }

    // ---- the enforcement ----

    @Test
    void aToolThePolicyDisablesIsRefusedAndTheInventorySaysSo() {
        putPolicy("carol", Map.of(
                "tools", Map.of("analyze_customer_risk", Map.of("enabled", false)),
                "audit", Map.of("granularity", "all")));

        ExecutionOutcome outcome = engine.execute("analyze_customer_risk", Map.of(), ALICE);
        assertEquals("blocked", outcome.status(), "a disabled tool does not run: " + outcome);

        // The refusal says which check refused it, rather than naming the risk policy by hand.
        List<?> checks = (List<?>) Vectors.map(outcome.data()).get("checks");
        Map<?, ?> disabled = checks.stream().map(Vectors::map)
                .filter(check -> "tool_enabled".equals(check.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("no tool_enabled check: " + checks));
        assertEquals(Boolean.FALSE, disabled.get("ok"), "the policy disabled it: " + disabled);

        // The list the console reads agrees with the gate, because both read the same policy.
        Map<?, ?> row = inventoryRow("analyze_customer_risk");
        assertEquals(Boolean.FALSE, row.get("enabled"), "the inventory shows the switch off: " + row);
    }

    @Test
    void aPolicyModeMovesTheGateInBothDirections() {
        AiTool read = registry.require("analyze_customer_risk");
        AiTool write = registry.require("create_followup");
        AiTool escalate = registry.require("escalate_customer");

        // Declared: R1 auto, R3 confirm, R4 approval. With no policy the gate is just the risk binding.
        assertEquals(GateDecision.ALLOW, governance.decide(read, ALICE));
        assertEquals(GateDecision.CONFIRM, governance.decide(write, ALICE));
        assertEquals(GateDecision.REQUIRE_APPROVAL, governance.decide(escalate, ALICE));

        putPolicy("carol", Map.of(
                "tools", Map.of(
                        "create_followup", Map.of("mode", "auto"),
                        "escalate_customer", Map.of("mode", "confirm")),
                "audit", Map.of("granularity", "all")));

        assertEquals(GateDecision.ALLOW, governance.decide(write, ALICE),
                "the policy relaxed R3 to auto");
        assertEquals(GateDecision.CONFIRM, governance.decide(escalate, ALICE),
                "and brought R4 down to a single confirmation");
    }

    @Test
    void anAllowedRolesListExcludesTheCallersItLeavesOut() {
        putPolicy("carol", Map.of(
                "tools", Map.of("analyze_customer_risk", Map.of("allowedRoles", List.of("admin"))),
                "audit", Map.of("granularity", "all")));

        AiTool tool = registry.require("analyze_customer_risk");
        assertEquals(GateDecision.BLOCK, governance.decide(tool, ALICE),
                "a role outside the list is refused");
        assertEquals(GateDecision.ALLOW, governance.decide(tool, CAROL),
                "a role inside it is not");
    }

    @Test
    void aPolicyNamingWhatThisDeploymentCannotApplyIsRefused() {
        // Each is refused with a reason and nothing is stored — an accepted-and-ignored key would put a
        // setting on a console that nothing enforces, which is the one answer this surface does not give.
        assertRefused(Map.of("tools", Map.of("no_such_tool", Map.of()), "audit", Map.of("granularity", "all")));
        assertRefused(Map.of("tools", Map.of("create_followup", Map.of("mode", "banana")),
                "audit", Map.of("granularity", "all")));
        assertRefused(Map.of("tools", Map.of("create_followup", Map.of("allowedRoles", List.of("root"))),
                "audit", Map.of("granularity", "all")));
        assertRefused(Map.of("tools", Map.of("create_followup", Map.of("unknownFlag", true)),
                "audit", Map.of("granularity", "all")));
        assertRefused(Map.of("tools", Map.of(), "audit", Map.of("granularity", "sometimes")));
        assertRefused(Map.of("tools", Map.of(), "audit", Map.of("granularity", "all"), "extra", 1));

        // Refused in whole: the store still holds what it held — nothing.
        assertEquals(Map.of(), Vectors.map(getPolicy("carol")).get("tools"),
                "a refused submission does not half-apply");
    }

    @Test
    void thePolicyIsRefusedToAPlainCaller() {
        assertEquals(403, getPolicyResponse("alice").getStatusCode().value(),
                "the policy says what governance is doing to every tool");
        assertEquals(403, putPolicyResponse("alice", Map.of("tools", Map.of(),
                "audit", Map.of("granularity", "all"))).getStatusCode().value(),
                "and writing it is a wider act than reading it");
    }

    @Test
    void aGranularityOfOffStopsTheAuditChain() {
        // The one frozen granularity that changes anything here: this runtime writes only tool-scoped
        // rows, so `write` and `all` come to the same chain — `off` is the one that writes none.
        putPolicy("carol", Map.of("tools", Map.of(), "audit", Map.of("granularity", "off")));

        long before = auditLogs.count();
        assertNull(audit.append("tool_call", "alice", "attempted", false, "attempted", "analyze_customer_risk"),
                "off writes nothing, so there is no row to return");
        assertEquals(before, auditLogs.count(), "and the chain did not grow");
    }

    // ---- helpers ----

    private Object getPolicy(String user) {
        ResponseEntity<String> response = getPolicyResponse(user);
        assertEquals(200, response.getStatusCode().value(), "the probe must be served: " + response);
        return Envelopes.data(Json.parse(response.getBody()));
    }

    private ResponseEntity<String> getPolicyResponse(String user) {
        return rest.exchange(URI.create("/ai/governance/policy"), HttpMethod.GET,
                new HttpEntity<>(asCaller(user)), String.class);
    }

    private String revisionOf(Map<String, Object> written) {
        return String.valueOf(written.get("revision"));
    }

    private Map<String, Object> putPolicy(String user, Map<String, Object> body) {
        ResponseEntity<String> response = putPolicyResponse(user, body);
        assertEquals(200, response.getStatusCode().value(), "the write must be served: " + response);
        return Envelopes.data(Json.parse(response.getBody()));
    }

    private ResponseEntity<String> putPolicyResponse(String user, Map<String, Object> body) {
        HttpHeaders headers = asCaller(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(URI.create("/ai/governance/policy"), HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private void assertRefused(Map<String, Object> body) {
        ResponseEntity<String> response = putPolicyResponse("carol", body);
        assertEquals(400, response.getStatusCode().value(),
                "a submission this deployment cannot apply is refused: " + response);
        WireSchemas.assertConformsTo(Vectors.map(Json.parse(response.getBody())), "error-body");
    }

    private Map<?, ?> inventoryRow(String toolName) {
        Object data = Envelopes.data(Json.parse(rest.exchange(URI.create("/ai/tools"), HttpMethod.GET,
                new HttpEntity<>(asCaller("carol")), String.class).getBody()));
        return ((List<?>) data).stream().map(Vectors::map)
                .filter(row -> toolName.equals(row.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("no inventory row for " + toolName));
    }

    private HttpHeaders asCaller(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return headers;
    }
}
