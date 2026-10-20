// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * A tool that declares a human-confirmation gate but no result type.
 *
 * <p>This is the declaration gap the runtime used to hide: {@code resultType} doubles as the
 * read/write switch, so a tool declaring R3 with no result type was counted as a read, never claimed,
 * and never recorded. A human could approve the confirmation and the write landed with no effect row
 * to audit or revoke. Registration now refuses the tool; the health surface must still name it as a
 * declaration error rather than let the refusal live only in the startup log.
 *
 * <p>The defect belongs to the tools dimension only: the gate itself is intact (R3 still becomes
 * {@code confirm}), so the confirmation, audit and delegation dimensions stay green. That is what
 * makes the report surgical instead of a general alarm.
 *
 * 一个声明了人工确认闸、却没有声明结果类型的工具。
 *
 * <p>这就是运行时此前藏起来的声明缺口：`resultType` 同时是读写开关，故一个声明 R3、却没有结果类型的工具
 * 会被算作只读、从不占位、也从不记账。人可以批准确认，写落地了，却没有可供审计或撤销的副作用行。现在注册
 * 会拒绝它；健康面仍必须把它报成声明错误，而不是让这次拒绝只留在启动日志里。
 *
 * <p>缺陷只属于工具维度：闸本身完好（R3 仍然变成 `confirm`），故确认、审计与委托三维保持绿。这正是报告
 * **外科式**、而不是一律拉响警报的原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@Import(IntegrationStatusResultTypeFailureTest.MissingResultTypeDeployment.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:status-result-type;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class IntegrationStatusResultTypeFailureTest {

    private static final String ADMIN = "carol";
    private static final String TOOL = "gated_write_without_result";

    @TestConfiguration(proxyBeanMethods = false)
    static class MissingResultTypeDeployment {

        /**
         * A write by its gate (R3), but it never declares what it produced. The registry refuses it;
         * the status surface must keep naming it so the refusal is visible after startup.
         *
         * 按闸算是写（R3），却从不说它产出了什么。注册表拒绝它；状态面仍必须点名它，好让这次拒绝在启动
         * 之后依然可见。
         */
        @Bean
        AiTool gatedWriteWithoutResult() {
            return new AiTool() {
                @Override
                public String name() {
                    return TOOL;
                }

                @Override
                public String description() {
                    return "declares R3 but no result type";
                }

                @Override
                public String riskLevel() {
                    return "R3";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(null);
                }
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    ConfirmationRequestRepository confirmations;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Test
    void aGatedWriteWithoutAResultTypeTurnsOnlyTheToolsDimensionRed() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> report = IntegrationStatusProbe.data(
                IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret));

        Map<String, Object> tools = IntegrationStatusProbe.block(report, "tools");
        assertEquals("error", tools.get("status"));
        assertTrue(((List<?>) tools.get("missingResultTypes")).contains(TOOL),
                "the reading must name the tool whose declaration is incomplete");
        assertEquals(List.of(), tools.get("invalidRiskLevels"),
                "this is not an invalid level; it is a missing result type");
        assertEquals(7, IntegrationStatusProbe.number(tools, "total"),
                "registration refused the tool, so the registry holds the seven valid tools");
        assertFalse(((List<?>) tools.get("names")).contains(TOOL),
                "a refused tool is not registered and is not offered to a model");

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) IntegrationStatusProbe.block(report, "health").get("errors");
        assertTrue(errors.stream().anyMatch(error -> error.contains(TOOL)
                        && error.contains("resultType") && error.contains("解决：")),
                "the error must name the tool, the missing field and the fix: " + errors);

        IntegrationStatusProbe.assertDiagnostic(tools, "error", "TOOLS_MISSING_RESULT_TYPE", TOOL,
                "没有声明 `resultType`");
        assertTrue(String.valueOf(IntegrationStatusProbe.diagnostic(tools,
                        "TOOLS_MISSING_RESULT_TYPE").get("nextStep")).contains("resultType"),
                "the next step names the field to declare");

        assertEquals("ok", IntegrationStatusProbe.block(report, "confirmation").get("status"),
                "the R3 gate itself is intact; the gap is the missing result type");
        assertEquals("ok", IntegrationStatusProbe.block(report, "audit").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "delegation").get("status"));
        assertEquals("error", IntegrationStatusProbe.block(report, "health").get("status"));
    }

    @Test
    void theRefusedToolCannotReachTheGateOrCreateAConfirmationRow() {
        long rowsBefore = confirmations.count();

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.execute(TOOL, Map.of(), new Principal(ADMIN, "admin")),
                "an unregistered tool must be refused before the gate, not proposed to a person");

        assertEquals(500, refused.getStatusCode().value(), "a refused declaration is a deployment error");
        assertTrue(refused.getReason() != null && refused.getReason().contains(TOOL)
                        && refused.getReason().contains("resultType"),
                "the refusal names the tool and the missing declaration: " + refused.getReason());
        assertEquals(rowsBefore, confirmations.count(),
                "no confirmation row exists for a tool registration refused");
    }
}
