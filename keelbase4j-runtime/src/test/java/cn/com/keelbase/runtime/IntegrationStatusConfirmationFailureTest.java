// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditService;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.TestPropertySource;

/**
 * The confirmation dimension red, and only it: an R3 tool whose gate the policy relaxes to auto.
 *
 * <p>This is the "configured but not in force" failure the dimension exists to catch. The policy is a
 * legitimate mechanism — a deployment may deliberately widen a gate — so the surface does not call
 * the policy wrong. It reports that a tool which declares R3 now reaches the engine with no human
 * decision, names the tool, and quotes the override that did it. The gate reading comes from the same
 * {@code GovernanceService.decide} call the engine makes, which is why the report can be trusted to
 * describe what will happen rather than what was declared.
 *
 * 确认维度红、且只有它：一道被策略放宽到 `auto` 的 R3 工具。
 *
 * <p>这就是该维度生来要抓的「配了、却没生效」。策略是正当机制 —— 部署方可以有意放宽一道闸 —— 故这个面
 * **不**说策略错了。它报告：一个声明 R3 的工具现在**没有任何人的决定**就到达引擎，点名那个工具，并引用
 * 做出这件事的覆盖。门控读数来自引擎做的**同一次** `GovernanceService.decide` 调用，故这份报告可以被信任为
 * 「将会发生什么」、而不是「声明了什么」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:status-confirmation;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class IntegrationStatusConfirmationFailureTest {

    private static final String ADMIN = "carol";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Test
    void anR3ToolRelaxedToAutoTurnsOnlyTheConfirmationDimensionRed() {
        IntegrationStatusProbe.seedAudit(audit);
        relaxCreateFollowUpToAuto();

        Map<String, Object> report = IntegrationStatusProbe.data(
                IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret));

        Map<String, Object> confirmation = IntegrationStatusProbe.block(report, "confirmation");
        assertEquals("error", confirmation.get("status"));
        assertEquals(2, IntegrationStatusProbe.number(confirmation, "declaredRequired"),
                "create_followup (R3) and escalate_customer (R4) both declare a human decision; "
                        + "the override relaxes the gate, not the declaration");
        assertEquals(2, IntegrationStatusProbe.number(confirmation, "effectiveAuto"),
                "the reader (R1) plus the relaxed R3 tool are the two auto tools");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ungated = (List<Map<String, Object>>) confirmation.get("ungated");
        assertEquals(1, ungated.size());
        assertEquals("create_followup", ungated.get(0).get("tool"));
        assertEquals("R3", ungated.get(0).get("riskLevel"));
        assertTrue(String.valueOf(ungated.get(0).get("reason")).contains("policy mode=auto"),
                "the report must quote the override that relaxed the gate");

        assertEquals("ok", IntegrationStatusProbe.block(report, "tools").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "audit").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "delegation").get("status"));
        assertEquals("error", IntegrationStatusProbe.block(report, "health").get("status"));
    }

    private void relaxCreateFollowUpToAuto() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(ADMIN, secret));
        String body = "{\"tools\":{\"create_followup\":{\"mode\":\"auto\"}},"
                + "\"audit\":{\"granularity\":\"all\"}}";
        ResponseEntity<String> response = rest.exchange(URI.create("/ai/governance/policy"),
                HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), "the policy write must land: " + response);
    }
}
