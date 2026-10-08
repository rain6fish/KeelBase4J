// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * A proposal whose arguments do not match the tool's own declaration is refused before a human is asked
 * to approve it.
 *
 * <p>What this pins is the shape that was possible until the declaration existed: a tool reads its
 * arguments out of a map, nothing stated which keys it reads, so a model that named one differently
 * produced a well-formed-looking proposal, it became a confirmation row, a person approved it, and the
 * execution then failed having written nothing. The measurer for the RY-23 criteria hit exactly that on
 * a live host — `remember` proposed with a `note` where the tool reads `content` — and the trace it left
 * was an approved confirmation and no memory.
 *
 * <p>Refusing is what the two halves have to agree on, so both are asserted: the bad name and the
 * missing one are refused, and the thing that must not happen — a row for a human to answer — does not
 * happen. The last two cases are the controls, and they check opposite mistakes: a declaration must not
 * refuse a proposal that matches it, and a tool that declares nothing must keep working untouched,
 * which is what makes this an additive change rather than a breaking one.
 *
 * 参数与工具**自己的声明**不符的提议，在请人批准之前就被拒。
 *
 * <p>这条钉住的是**声明存在之前**就可能的那个形状：工具从一个 map 里读参数、而没有任何东西说过它读哪些
 * 键，于是模型把其中一个名字写错，产出的提议看上去完好、变成一行待确认、有人批准了它，而执行随即失败、
 * 什么都没写。RY-23 判据的量具在**真实宿主**上撞到的正是这个 —— `remember` 提议时写的是 `note`，而该工具
 * 读的是 `content` —— 它留下的痕迹是一条被批准的确认和一条不存在的记忆。
 *
 * <p>「拒」是两半必须一致的地方，所以两半都断言：**错名字**与**漏必填**都被拒，而**不该发生的**那件事
 * —— 一条等人回答的行 —— 不发生。最后两条是对照，查的是相反的两种错：声明不得拒掉与它相符的提议，而
 * **什么都没声明**的工具必须原样继续工作 —— 那正是本次改动是**加性**而非破坏性的原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BadArgumentsAreRefusedTest {

    private static final String CALLER = "alice";

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    ConfirmationRequestRepository confirmations;

    @TestConfiguration
    static class Probe {

        /** Declares its arguments, so a proposal naming them wrongly never reaches a person. */
        @Bean
        AiTool probeDeclaringTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_declaring";
                }

                @Override
                public String description() {
                    return "test double: a write that says which arguments it reads";
                }

                @Override
                public String riskLevel() {
                    return "R3";
                }

                @Override
                public List<ToolParameter> parameters() {
                    return List.of(
                            ToolParameter.required("content", ToolParameter.STRING, "the note to keep"),
                            ToolParameter.optional("customerId", ToolParameter.NUMBER, "who it is about"));
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of("content", String.valueOf(args.get("content"))));
                }
            };
        }

        /** The control: a tool written before arguments were declarable, declaring nothing. */
        @Bean
        AiTool probeUndeclaredTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_undeclared";
                }

                @Override
                public String description() {
                    return "test double: a write that declares nothing";
                }

                @Override
                public String riskLevel() {
                    return "R3";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of("seen", String.valueOf(args)));
                }
            };
        }
    }

    private static final Principal ALICE = new Principal(CALLER, Principal.ROLE_USER);

    @Test
    void aProposalNamingAnArgumentTheToolDoesNotReadIsRefusedAndLeavesNoRow() {
        long rowsBefore = confirmations.count();

        ExecutionOutcome outcome = engine.execute("probe_declaring", Map.of("note", "hello"), ALICE);

        assertEquals("invalid_arguments", outcome.status(),
                "a name the tool does not read is refused, not proposed");
        assertTrue(outcome.error().contains("`note`"),
                "the refusal must name the argument it could not place: " + outcome.error());
        assertTrue(outcome.error().contains("content"),
                "and say what the tool does read, or the model cannot correct itself: " + outcome.error());
        assertEquals(rowsBefore, confirmations.count(),
                "a refused proposal must not become a confirmation for somebody to answer");
    }

    @Test
    void aProposalMissingARequiredArgumentIsRefusedAndLeavesNoRow() {
        long rowsBefore = confirmations.count();

        ExecutionOutcome outcome = engine.execute("probe_declaring", Map.of("customerId", 7), ALICE);

        assertEquals("invalid_arguments", outcome.status());
        assertTrue(outcome.error().contains("`content`"),
                "the missing required argument is what must be named: " + outcome.error());
        assertEquals(rowsBefore, confirmations.count());
    }

    @Test
    void aRefusedProposalIsAuditedSoAnOperatorCanSeeTheAttempt() {
        engine.execute("probe_declaring", Map.of("note", "hello"), ALICE);

        AuditLog last = auditLogs.findTopByOrderByIdDesc().orElseThrow(
                () -> new AssertionError("a refused proposal left no audit row at all"));
        assertEquals("tool_call", last.getAction());
        assertEquals(CALLER, last.getUserId());
        assertTrue(last.getDetail().contains("probe_declaring refused:"),
                "the audit must name the refusal: " + last.getDetail());
    }

    @Test
    void theDeclaredShapeIsStillProposed() {
        ExecutionOutcome outcome = engine.execute("probe_declaring", Map.of("content", "keep me"), ALICE);

        assertEquals("pending_confirmation", outcome.status(),
                "a declaration must refuse bad proposals, not good ones");
        assertNotNull(outcome.token(), "and the proposal still waits for a person");
    }

    @Test
    void aToolThatDeclaresNothingIsNotChecked() {
        ExecutionOutcome outcome = engine.execute("probe_undeclared", Map.of("anything", "at all"), ALICE);

        assertEquals("pending_confirmation", outcome.status(),
                "declaring nothing means unchecked, not 'no arguments allowed' — otherwise this change "
                        + "would break every tool written before it");
    }
}
