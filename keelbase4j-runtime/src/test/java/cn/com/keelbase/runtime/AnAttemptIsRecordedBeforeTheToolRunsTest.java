// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * The attempt is on the chain before the tool runs, and the result after it.
 *
 * <p>The row that says "someone tried" is only worth its name if it is written before the thing it
 * records. That is the whole of the guarantee: the result row is written after the tool's own write
 * and is best-effort, so a host write that lands and a bookkeeping step that does not would leave the
 * attempt unrecorded — the half an operator asks about first. A row written after the tool would look
 * identical on the wire and give nothing: the window it exists to cover would already have closed.
 *
 * <p>So this does not merely count rows. It asks the tool, from inside its own execution, what the
 * chain already held — which is the only place the ordering can be observed, and the reason a
 * count of two rows would pass a fix that wrote both after the fact.
 *
 * 尝试在工具跑之前就上了链，结果在它之后。
 *
 * <p>那一行「有人试过」，只有当它写在**它所记录的那件事之前**时才名副其实。这正是这条保证的全部：结果行写在
 * 工具自己那一笔**之后**、且是 best-effort，于是一次落了地的宿主写、加上一步没落地的记账，会让这次尝试**无人
 * 记录** —— 而那恰恰是运维第一个会问的那一半。一行写在工具**之后**的行，在线缆上看起来一模一样，却什么都没
 * 给：它存在的那个窗口**早已关闭**。
 *
 * <p>所以这里**不只是数行**。它**在工具自己的执行里**问链上此刻已有什么 —— 那是**顺序唯一能被观察到的地方**，
 * 也正是「数出两行」会放过一个「两行都写在事后」的修法的原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AnAttemptIsRecordedBeforeTheToolRunsTest {

    private static final String TOOL = "probe_attempt_recorded";
    private static final String CALLER = "attempt-recorder";

    /** What the chain held at the instant the tool ran — read from inside the tool, not after it. */
    private static final AtomicReference<Boolean> INTENT_ON_CHAIN_WHEN_TOOL_RAN = new AtomicReference<>();
    private static final AtomicReference<Boolean> RESULT_ON_CHAIN_WHEN_TOOL_RAN = new AtomicReference<>();

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @TestConfiguration
    static class Probe {

        @Autowired
        AuditLogRepository auditLogs;

        /** Records the chain's state at the moment it runs — the observation the ordering needs. */
        @Bean
        AiTool probeAttemptRecordedTool() {
            AuditLogRepository audit = auditLogs;
            return new AiTool() {
                @Override
                public String name() {
                    return TOOL;
                }

                @Override
                public String description() {
                    return "test double: reports what the chain held when it ran";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public String resultType() {
                    return "probe_thing";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    INTENT_ON_CHAIN_WHEN_TOOL_RAN.set(hasOutcome(audit, "attempted"));
                    RESULT_ON_CHAIN_WHEN_TOOL_RAN.set(hasOutcome(audit, "executed"));
                    return ToolResult.ok(Map.of("id", 7L));
                }
            };
        }
    }

    private static boolean hasOutcome(AuditLogRepository audit, String outcome) {
        return audit.findAllByOrderByIdAsc().stream()
                .anyMatch(row -> TOOL.equals(row.getTool()) && outcome.equals(row.getOutcome()));
    }

    @Test
    void theAttemptIsOnTheChainBeforeTheToolRunsAndTheResultAfterIt() {
        Principal caller = new Principal(CALLER, Principal.ROLE_USER);
        engine.execute(TOOL, Map.of(), caller);

        assertTrue(INTENT_ON_CHAIN_WHEN_TOOL_RAN.get(),
                "the intent row must already be on the chain when the tool runs — that is what makes "
                        + "\"who tried\" independent of the result line, which is written after it");
        assertFalse(RESULT_ON_CHAIN_WHEN_TOOL_RAN.get(),
                "the result row is written after the tool, so it cannot be there while the tool runs");

        List<AuditLog> rows = auditLogs.findAllByOrderByIdAsc().stream()
                .filter(row -> TOOL.equals(row.getTool()))
                .toList();
        assertEquals(2, rows.size(),
                "a successful execution leaves two rows — attempted, then executed");
        assertEquals("attempted", rows.get(0).getOutcome(),
                "the attempt comes first, so \"who tried\" is recorded before \"what it did\"");
        assertEquals("executed", rows.get(1).getOutcome(),
                "the result follows it, and is the row the attempt's outcome is read from");
    }
}
