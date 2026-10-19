// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The integration-health surface: four readings, each with its own status, on a healthy deployment.
 *
 * <p>This is the "normal" half of the pair. A deployment whose tools, gate, audit chain and
 * delegation settings are intact reports each dimension {@code ok}; the report names the source of
 * every reading so a reader can check the verdict rather than trust it. The access assertions are
 * part of the same test on purpose: a health surface that anyone can read is not a diagnostic, it is
 * a disclosure.
 *
 * <p>The failure half lives in one test class per dimension
 * ({@code IntegrationStatusToolsFailureTest}, {@code IntegrationStatusConfirmationFailureTest},
 * {@code IntegrationStatusAuditFailureTest}, {@code IntegrationStatusDelegationFailureTest}), each on
 * its own in-memory database, so "one dimension red, the others untouched" is measured rather than
 * asserted in prose.
 *
 * 接入健康度面：在一个健康部署上，四个读数各带自己的状态。
 *
 * <p>这是**成对**里「正常」的那一半。工具、闸、审计链与委托设置完好的部署，每个维度都报 `ok`；报告写明
 * 每个读数的来源，故读的人可以核对结论、而不是相信它。访问断言故意放在同一个测试里：一个谁都能读的健康面
 * 不是诊断，是**泄露**。
 *
 * <p>「失败」那半按维度各一个测试类（`IntegrationStatusToolsFailureTest`、
 * `IntegrationStatusConfirmationFailureTest`、`IntegrationStatusAuditFailureTest`、
 * `IntegrationStatusDelegationFailureTest`），各自用一个独立的内存库，于是「一个维度红、其余不受影响」
 * 是被**量出来**的，不是被散文断言出来的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:status-normal;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class IntegrationStatusTest {

    private static final String ADMIN = "carol";
    private static final String CALLER = "alice";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Test
    void theReportCarriesExactlyTheSixBlocks() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> report = IntegrationStatusProbe.data(
                IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret));

        assertEquals(Set.of("provenance", "tools", "confirmation", "audit", "delegation", "health"),
                report.keySet(),
                "the report is the four dimensions plus where it came from and the verdict");
    }

    @Test
    void toolsAreCountedByReadWriteAndRiskAtTheirDeclaredLevels() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> tools = IntegrationStatusProbe.block(
                IntegrationStatusProbe.data(
                        IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret)),
                "tools");

        assertEquals("ok", tools.get("status"));
        assertEquals(7, IntegrationStatusProbe.number(tools, "total"));
        assertEquals(2, IntegrationStatusProbe.number(tools, "read"),
                "analyze_customer_risk and analyze_project_risk are the readers");
        assertEquals(5, IntegrationStatusProbe.number(tools, "write"),
                "the two CRM writes, the two PM writes and the irreversibly blocked PM delete");
        assertEquals(List.of(), tools.get("invalidRiskLevels"));
        assertEquals(List.of(), tools.get("writeWithoutRevoke"),
                "the R5 delete declares no local compensation, and a write that can never run is not a warning");

        @SuppressWarnings("unchecked")
        Map<String, Object> distribution = (Map<String, Object>) tools.get("riskDistribution");
        assertEquals(Set.of("R1", "R3", "R4", "R5"), distribution.keySet());
        assertEquals(2, ((Number) distribution.get("R1")).intValue());
        assertEquals(2, ((Number) distribution.get("R3")).intValue());
        assertEquals(2, ((Number) distribution.get("R4")).intValue());
        assertEquals(1, ((Number) distribution.get("R5")).intValue());
    }

    @Test
    void theGateIsReadFromTheSameDecisionFunctionTheEngineUses() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> confirmation = IntegrationStatusProbe.block(
                IntegrationStatusProbe.data(
                        IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret)),
                "confirmation");

        assertEquals("ok", confirmation.get("status"));
        assertEquals("GovernanceService.decide (the execution engine's own gate)",
                confirmation.get("evidence"));
        assertEquals(4, IntegrationStatusProbe.number(confirmation, "declaredRequired"),
                "the two R3 writes and the two R4 writes declare a human decision");
        assertEquals(2, IntegrationStatusProbe.number(confirmation, "effectiveConfirm"));
        assertEquals(2, IntegrationStatusProbe.number(confirmation, "effectiveApproval"));
        assertEquals(1, IntegrationStatusProbe.number(confirmation, "effectiveBlocked"),
                "delete_project declares R5, which the gate refuses on the risk level alone");
        assertEquals(List.of(), confirmation.get("ungated"),
                "no R3/R4 tool may reach the engine ungated on a clean deployment");
        assertEquals(Boolean.TRUE, confirmation.get("storeReachable"));
        assertNotNull(confirmation.get("pending"));
    }

    @Test
    void auditReportsRowsAndTimesRatherThanWhatShouldHappen() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> auditBlock = IntegrationStatusProbe.block(
                IntegrationStatusProbe.data(
                        IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret)),
                "audit");

        assertEquals("ok", auditBlock.get("status"));
        assertTrue(IntegrationStatusProbe.number(auditBlock, "rows") >= 1,
                "the seed row must be visible as a row");
        assertTrue(IntegrationStatusProbe.number(auditBlock, "rowsLast24h") >= 1);
        assertNotNull(auditBlock.get("latestAt"), "the latest row's time is the reading, not a promise");
        assertNotNull(auditBlock.get("latestId"));
        assertEquals(Boolean.TRUE, auditBlock.get("chainValid"));
        assertEquals("checked", auditBlock.get("chainCheck"));
    }

    @Test
    void delegationSeparatesTheLocalProbeFromTheInboundToken() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> delegation = IntegrationStatusProbe.block(
                IntegrationStatusProbe.data(
                        IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret)),
                "delegation");

        assertEquals("ok", delegation.get("status"));
        assertEquals("DelegationTokenAuthenticator", delegation.get("provider"));
        assertEquals(Boolean.TRUE, delegation.get("secretConfigured"));
        assertEquals(Boolean.TRUE, delegation.get("audienceConfigured"));
        assertEquals("keelbase4j", delegation.get("audience"));
        assertEquals(Boolean.TRUE, delegation.get("selfTest"),
                "the configured secret must sign and verify a locally minted token");
        assertEquals(Boolean.TRUE, delegation.get("inboundVerified"),
                "the report is only reachable when this caller's own token verified");
    }

    @Test
    void aHealthyDeploymentIsGreenInAllFourDimensions() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> report = IntegrationStatusProbe.data(
                IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret));
        Map<String, Object> health = IntegrationStatusProbe.block(report, "health");

        assertEquals("healthy", health.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dimensions = (Map<String, Object>) health.get("dimensions");
        assertEquals(Map.of("tools", "ok", "confirmation", "ok", "audit", "ok", "delegation", "ok"),
                dimensions);
        assertEquals(List.of(), health.get("errors"));
        assertEquals(List.of(), health.get("warnings"));
    }

    @Test
    void anOrdinaryCallerIsRefused() {
        ResponseEntity<String> response = IntegrationStatusProbe.get(
                rest, IntegrationStatusProbe.PATH, CALLER, secret);
        assertEquals(403, response.getStatusCode().value(),
                "tool names, gate modes and queue depth are operational facts, not a public advertisement");
    }

    @Test
    void aCallerWithoutATokenIsRefused() {
        ResponseEntity<String> response = rest.exchange(URI.create(IntegrationStatusProbe.PATH),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(401, response.getStatusCode().value(), "the surface is authenticated at the entry");
    }
}
