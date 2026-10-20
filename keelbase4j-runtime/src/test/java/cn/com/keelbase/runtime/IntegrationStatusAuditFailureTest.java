// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The audit dimension red, and only it: a row whose content no longer matches its hash.
 *
 * <p>The chain is verified by the runtime's own verifier, walking the rows the repository holds. A
 * row tampered with behind the writer's back is exactly what that walk exists to catch, and the
 * report names the row id rather than only saying "invalid" — a broken chain an operator cannot
 * locate is a diagnosis half done. The other dimensions are asserted green because an audit fault
 * says nothing about the tools, the gate or the delegation settings.
 *
 * 审计维度红、且只有它：一行的内容与它的哈希不再相符。
 *
 * <p>链由运行时自己的校验器走一遍，读的是仓库持有的行。在写者背后被改过的一行，正是这次走查要抓的东西，
 * 而报告**点名行 id**、不只说「invalid」—— 一条操作者定位不了的断链，诊断只做了一半。另外三个维度断言为绿，
 * 因为审计故障并不说明工具、闸或委托设置有什么问题。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:status-audit;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class IntegrationStatusAuditFailureTest {

    private static final String ADMIN = "carol";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Test
    void aTamperedRowTurnsOnlyTheAuditDimensionRedAndNamesTheRow() {
        AuditLog row = audit.append("tool_call", "tamper-check", "before", false, "executed",
                "create_followup");
        // Change the payload the hash was taken over, without touching the hash. This is the failure
        // the verifier is for; the writer never does it.
        //
        // 改动被取过哈希的载荷、却不碰哈希。这正是校验器存在的意义；写者从不会这么干。
        jdbc.update("update ai_audit_logs set detail = ? where id = ?", "after", row.getId());

        Map<String, Object> report = IntegrationStatusProbe.data(
                IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret));

        Map<String, Object> auditBlock = IntegrationStatusProbe.block(report, "audit");
        assertEquals("error", auditBlock.get("status"));
        assertEquals(Boolean.FALSE, auditBlock.get("chainValid"));
        assertEquals(row.getId().longValue(),
                ((Number) auditBlock.get("brokenRowId")).longValue(),
                "the report must locate the broken row, not merely say the chain is invalid");
        assertEquals(1, IntegrationStatusProbe.number(auditBlock, "rows"));

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) IntegrationStatusProbe.block(report, "health").get("errors");
        assertTrue(errors.stream().anyMatch(error -> error.contains("行 id " + row.getId())
                        && error.contains("解决：")),
                "the error must name the row and carry the fix: " + errors);

        IntegrationStatusProbe.assertDiagnostic(auditBlock, "error", "AUDIT_CHAIN_BROKEN",
                "row " + row.getId(), "行 id " + row.getId());

        assertEquals("ok", IntegrationStatusProbe.block(report, "tools").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "confirmation").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "delegation").get("status"));
        assertEquals("error", IntegrationStatusProbe.block(report, "health").get("status"));
    }
}
