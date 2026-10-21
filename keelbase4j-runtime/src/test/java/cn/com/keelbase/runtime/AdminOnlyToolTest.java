// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * A tool that declares itself administrator-only refuses an ordinary caller at the gate — the
 * alignment the M7 dry-run asked for, where a REST endpoint answered 403 to the same caller.
 *
 * <p>The two directions are asserted together on purpose: a refusal that also refused the
 * administrator would satisfy "the ordinary user is blocked" while breaking the tool, so the admin
 * path is what says the declaration narrowed the boundary instead of closing it.
 *
 * 声明「仅限管理员」的工具在闸门就拒绝普通调用方 —— 正是 M7 dry-run 要的那条对齐：同一个调用方在 REST 端点
 * 拿到的是 403。
 *
 * <p>两个方向**故意一起断言**：一个连管理员也拒的拒绝，能满足「普通用户被挡住」却把工具弄坏，故管理员那条
 * 路径才说明这声明确实**收窄**了边界、而不是把它关死。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminOnlyToolTest {

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @TestConfiguration
    static class Probe {

        /** R1 read, declared administrator-only. / R1 读工具，声明仅限管理员。 */
        @Bean
        AiTool probeAdminOnlyTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_admin_only";
                }

                @Override
                public String description() {
                    return "test double: a read the deployment reserves for administrators";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public boolean adminOnly() {
                    return true;
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of("ok", true));
                }
            };
        }
    }

    @Test
    void anOrdinaryCallerIsRefusedWithTheFrozenCheck() {
        Principal caller = new Principal("frank", Principal.ROLE_USER);

        ExecutionOutcome outcome = engine.execute("probe_admin_only", Map.of(), caller);
        assertEquals("blocked", outcome.status(), "an administrator-only tool is refused before it runs");

        AuditLog last = auditLogs.findTopByOrderByIdDesc().orElseThrow(
                () -> new AssertionError("the refused call left no audit row at all"));
        assertEquals("blocked", last.getOutcome());
        String basis = last.getAuthorization();
        assertNotNull(basis, "the refusal must be structured, not only a cause word");
        assertTrue(basis.contains("\"tool\":\"probe_admin_only\""), "the basis names the tool: " + basis);
        assertTrue(basis.contains("\"name\":\"admin_only\""), "the frozen check name: " + basis);
        assertTrue(basis.contains("\"ok\":false"), "and that it is the one that failed: " + basis);
    }

    @Test
    void anAdministratorReachesTheTool() {
        Principal caller = new Principal("grace", Principal.ROLE_ADMIN);

        ExecutionOutcome outcome = engine.execute("probe_admin_only", Map.of(), caller);
        assertEquals("executed", outcome.status(), "the declaration narrows the boundary, it does not close it");

        AuditLog last = auditLogs.findTopByOrderByIdDesc().orElseThrow(
                () -> new AssertionError("the executed call left no audit row at all"));
        assertEquals("executed", last.getOutcome());
        String basis = last.getAuthorization();
        assertNotNull(basis, "the allow snapshot is recorded too");
        assertTrue(basis.contains("\"name\":\"admin_only\""), "the check is on the row: " + basis);
        assertTrue(basis.contains("\"ok\":true"), "and it passed for the administrator: " + basis);
    }
}
