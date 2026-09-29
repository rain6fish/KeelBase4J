// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused call is an event, so it is audited.
 *
 * <p>What this pins is the half of the trust story that reads as inoffensive until it is missing: the
 * audit must report **what was attempted and refused**, not only what succeeded. The refusal arrives
 * as an exception — a row outside the caller's scope, rejected by the row gate — and the engine's
 * audit line is composed from the outcome, so before this test's subject was fixed a refusal left
 * **no row at all**. Measured on a real host before it was reasoned about: a non-administrator's
 * out-of-scope read produced a 403, wrote no side effect (correct), and left the audit holding only
 * the administrator's earlier successes.
 *
 * <p>Both assertions matter and they check opposite things: a line must now exist, and the thing that
 * must not exist still must not. A fix that audited by writing something would pass the first and
 * fail the second.
 *
 * 一次被拒绝的调用是一个事件，所以它被审计。
 *
 * <p>这条钉住的是信任故事里那一半——在缺失之前读起来无害的那一半：审计必须报告**尝试了什么、被拒了
 * 什么**，而不只是报告做成了什么。拒绝以异常到来——一行落在调用方范围之外、被行闸驳回——而引擎的审计
 * 行是由结果拼出来的，所以在这个测试的对象被修好之前，**一次拒绝不留任何行**。**先在真实宿主上测到、
 * 后推理**：一个非管理员的越界读产生了 403、没写副作用（正确），而审计里只有管理员先前那几次成功。
 *
 * <p>两条断言都重要，而且它们检查的是相反的事：现在**必须有**一行，而**不该存在的**仍然不存在。一个
 * 靠「写点什么」来交差的修法会通过第一条、挂掉第二条。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefusedToolCallIsAuditedTest {

    private static final String CALLER = "alice";

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    SideEffectRepository sideEffects;

    @TestConfiguration
    static class Probe {

        /** Stands in for a tool refusing: the row gate throwing, as a tool over host data does. */
        @Bean
        AiTool probeRefusingTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_refusing";
                }

                @Override
                public String description() {
                    return "test double: a read whose row is outside the caller's scope";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "该行不在你的数据范围内");
                }
            };
        }
    }

    @Test
    void aRefusalIsAuditedAndWritesNothing() {
        Principal caller = new Principal(CALLER, Principal.ROLE_USER);
        int effectsBefore = sideEffects.findByUserIdOrderByIdDesc(CALLER).size();

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.execute("probe_refusing", Map.of(), caller),
                "the refusal must still be a refusal — it is recorded, not swallowed");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());

        AuditLog last = auditLogs.findTopByOrderByIdDesc().orElseThrow(
                () -> new AssertionError("a refused call left no audit row at all"));
        assertEquals("tool_call", last.getAction());
        assertEquals(CALLER, last.getUserId());
        assertTrue(last.getDetail().contains("probe_refusing refused:"),
                "the audit must name the refusal, not only the outcomes: " + last.getDetail());
        assertTrue(last.getDetail().contains("该行不在你的数据范围内"),
                "and the reason, which is what an operator acts on: " + last.getDetail());

        assertEquals(effectsBefore, sideEffects.findByUserIdOrderByIdDesc(CALLER).size(),
                "a refused call writes nothing — audited is not executed");
    }
}
