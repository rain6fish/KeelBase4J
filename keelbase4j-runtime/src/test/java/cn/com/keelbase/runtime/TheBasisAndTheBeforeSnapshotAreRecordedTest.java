// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
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
 * Two facts a reader of the trail could ask for and not get, until they were kept.
 *
 * <p><b>The basis.</b> The gate always decided whether a call was allowed, but only the sentence on
 * the row kept any trace of it — a cause word. So "why was this refused" had an answer that was a
 * word, and "why was this allowed" had none at all. Both now sit on the row as the same frozen
 * reasons the caller is handed back; a declined confirmation still carries neither, because nothing
 * was denied there and a row that looked refused would be read as one.
 *
 * <p><b>The before.</b> The ledger recorded what a write produced and never what it changed. That was
 * true while every tool here created a row; a tool that <em>updates</em> one makes it false, and its
 * result now carries the row as it was. Null for a create is the fact, not a gap.
 *
 * <p>Both assertions check the thing the change was for rather than its shape: the refusal's reasons
 * are found by a check name that only the gate's own vocabulary produces, and the snapshot is compared
 * against the exact string the tool handed over.
 *
 * 读者问得出、而此前答不了的两件事，各自被留下之后。
 *
 * <p><b>依据。</b>闸门一向都在判一次调用放不放行，但行上那句话是它唯一的痕迹 —— 一个成因词。于是「凭什么拒它」
 * 的答案是一个词，而「凭什么放它」根本没有答案。两样现在都作为**交回调用方的同一份冻结依据**落在行上；被拒绝的
 * **确认**仍旧两样都不带 —— 那里并没有任何东西被拒，而一行看起来像被拒的，就会被读成被拒。
 *
 * <p><b>之前的样子。</b>账本记下一次写**产出了什么**，从不记它**改了什么**。那在这里每个工具都新建一行时是对的；
 * 一个会**改行**的工具让它不再对，而它的结果如今带着那一行**原先**的样子。新建时为 null 是**事实**、不是缺口。
 *
 * <p>两条断言检查的都是这次改动**为之**的那件事、而不是它的形状：拒绝的依据由**只有闸门自己的词表**才会产出的一个
 * 检查名找到，快照则与工具交出的那个字符串**逐字**比对。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TheBasisAndTheBeforeSnapshotAreRecordedTest {

    /** The string the updating tool hands back; compared verbatim so a re-serialisation is caught. */
    private static final String BEFORE = "{\"id\":7,\"name\":\"before\"}";

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    SideEffectRepository sideEffects;

    @TestConfiguration
    static class Probe {

        /**
         * R5, so the gate refuses it on the risk level alone — a refusal whose basis the row must keep.
         *
         * R5，故闸门**单凭风险级**就拒它 —— 一次其依据必须留行上的拒绝。
         */
        @Bean
        AiTool probeBlockedTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_blocked";
                }

                @Override
                public String description() {
                    return "test double: a call the gate refuses on its risk level";
                }

                @Override
                public String riskLevel() {
                    return "R5";
                }

                @Override
                public String resultType() {
                    return "probe_blocked";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    throw new AssertionError("a blocked call must never reach the tool");
                }
            };
        }

        /**
         * A write that <em>updates</em>: it hands back the row as it was, which is what a tool over
         * host data does when it reads the row before changing it.
         *
         * 一个**改行**的写：它交回那一行**原先**的样子 —— 一个跑在宿主数据上的工具在改之前先读那一行，做的正是这件事。
         */
        @Bean
        AiTool probeUpdatingTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_updating";
                }

                @Override
                public String description() {
                    return "test double: an allowed write that updates a row";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public String resultType() {
                    return "probe_row";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of("id", 7, "name", "after"), BEFORE);
                }
            };
        }
    }

    @Test
    void aBlockedCallKeepsItsReasons() {
        Principal caller = new Principal("bob", Principal.ROLE_USER);

        ExecutionOutcome outcome = engine.execute("probe_blocked", Map.of(), caller);
        assertEquals("blocked", outcome.status(), "R5 is refused before its tool is reached");

        AuditLog last = auditLogs.findTopByOrderByIdDesc().orElseThrow(
                () -> new AssertionError("a blocked call left no audit row at all"));
        assertEquals("blocked", last.getOutcome());

        String basis = last.getAuthorization();
        assertNotNull(basis, "the row must keep why it was refused, not only a cause word");
        assertTrue(basis.contains("\"tool\":\"probe_blocked\""),
                "the basis must name the tool it was decided for: " + basis);
        assertTrue(basis.contains("\"riskLevel\":\"R5\""),
                "and the risk level the refusal turned on: " + basis);
        assertTrue(basis.contains("risk_policy"),
                "and the check that failed, from the gate's own vocabulary: " + basis);
    }

    @Test
    void anUpdatingWriteKeepsWhatTheRowWas() {
        Principal caller = new Principal("carol", Principal.ROLE_USER);

        engine.execute("probe_updating", Map.of(), caller);

        SideEffect effect = sideEffects.findByUserIdOrderByIdDesc("carol").stream()
                .filter(row -> "probe_updating".equals(row.getToolName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the write recorded no side effect"));
        assertEquals(BEFORE, effect.getBeforeSnapshot(),
                "the ledger must keep the row as it was, verbatim");
        assertTrue(effect.getAfterSnapshot() != null && effect.getAfterSnapshot().contains("\"after\""),
                "and what it produced, which it already did: " + effect.getAfterSnapshot());
    }
}
