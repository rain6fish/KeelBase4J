// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
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
 * A write that does not say which row it made fails <em>on the record</em>.
 *
 * <p>The effect ledger records one effect per write and it must name what was produced — the contract's
 * effect object requires a {@code resultId}, and the column will not take null. So a tool that declares
 * a result type and then returns no numeric {@code id} used to reach an insert that threw a constraint
 * violation, and it threw it <em>after</em> the tool had already run: a landed write, no effect, no
 * settle, and — because the throw left the block before its own audit line — an intent row with no
 * outcome and a 500 that said nothing about any of it.
 *
 * <p>The fix does not make such a write succeed; nothing can, the runtime was never shown a subject.
 * What it does is stop the runtime from turning a tool's omission into an unexplained failure: the call
 * ends as a <b>recorded</b> failure with a reason, the effect stays unrecorded, and the claim stays
 * taken, because the tool ran and the write may have landed. The control next to it is the same write
 * naming its row, which records and settles exactly as before.
 *
 * 一次不说清自己做了哪一行的写，**在轨上**失败。
 *
 * <p>副作用账本给每次写记一条 effect，而它必须说清**产出了什么** —— 契约的 effect 对象要求 `resultId`，而那一
 * 列不收 null。于是：一个声明了结果类型、却交回不出数字 `id` 的工具，从前会走到一条**抛约束违例**的插入，而它是在
 * 工具**已经跑完之后**才抛的 —— 写落了、没有 effect、没有 settle；又因为那次抛出在自己的审计行**之前**就离开了这个
 * 块，轨上只剩一条 intent 行、没有结局，调用方拿到一个什么都没说的 500。
 *
 * <p>修法**不会让这样的写成功**——没有东西能让它成功，运行时从没被展示过一个主体。它做的是**不让运行时把工具的一次
 * 疏漏变成一个说不清来路的失败**：这次调用以**记录式失败**收尾、带理由；effect 仍然不落；领取仍然占着——因为工具跑过
 * 了、写可能已落地。旁边那个对照是同一个写、点名了自己的行，它照旧记账并 settle。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AWriteWithoutAResultIdFailsOnTheRecordTest {

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    SideEffectRepository sideEffects;

    @Autowired
    WriteClaimRepository claims;

    @TestConfiguration
    static class Probe {

        /** Writes, and forgets to say which row: what a bulk or hand-rolled tool does. */
        @Bean
        AiTool probeNamesNoResultTool() {
            return new WriteDouble("probe_no_result") {
                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of("changed", 3));
                }
            };
        }

        /** The control: the same write, naming the row it made. */
        @Bean
        AiTool probeNamesResultTool() {
            return new WriteDouble("probe_with_result") {
                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of("id", 7));
                }
            };
        }
    }

    /**
     * A write double: {@code R3} so it runs at an approval, and a result type — which is the promise
     * this test is about.
     *
     * 一个写的测试替身：`R3`，故它在被批准时运行；且带一个结果类型——而那个结果类型正是本条要讲的承诺。
     */
    abstract static class WriteDouble implements AiTool {

        private final String name;

        WriteDouble(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test double: a write";
        }

        @Override
        public String riskLevel() {
            return "R3";
        }

        @Override
        public String resultType() {
            return name + "_result";
        }
    }

    @Test
    void aWriteWithNoResultIdEndsAsARecordedFailure() {
        // A delta, not a total: other tests share this database. / 用**增量**、不用总数：别的测试共用这个库。
        int effectsBefore = sideEffects.findByUserIdOrderByIdDesc("alice").size();
        ExecutionOutcome outcome = approve("probe_no_result");

        assertEquals("error", outcome.status(),
                "the caller is told it failed, rather than left with a constraint violation");
        assertNotNull(outcome.error(), "and told why");
        assertTrue(outcome.error().contains("no result id"), outcome.error());

        AuditLog last = auditLogs.findTopByOrderByIdDesc()
                .orElseThrow(() -> new AssertionError("the outcome must be on the record"));
        assertEquals("tool_call", last.getAction());
        assertTrue(last.getDetail().contains("probe_no_result -> fail:"), last.getDetail());
        assertTrue(last.getDetail().contains("no result id"), last.getDetail());
        assertEquals("failed", last.getOutcome(), "and as a failure, in the frozen vocabulary");

        assertEquals(effectsBefore, sideEffects.findByUserIdOrderByIdDesc("alice").size(),
                "nothing is recorded: an effect with no subject is not an effect");

        assertEquals(WriteClaim.CLAIMED, claimStatus("probe_no_result"),
                "and the key stays taken — the tool ran, so the write may have landed");
    }

    @Test
    void theControlRecordsTheEffectAndSettlesTheClaim() {
        ExecutionOutcome outcome = approve("probe_with_result");

        assertEquals("executed", outcome.status());
        assertNotNull(outcome.effectId(), "a write that names its row records an effect");
        assertEquals(WriteClaim.SETTLED, claimStatus("probe_with_result"));
    }

    /** Propose the call and answer its own confirmation, returning how it ended. */
    private ExecutionOutcome approve(String toolName) {
        ExecutionOutcome pending = engine.execute(toolName, Map.of(), ALICE);
        assertNotNull(pending.token(), "an R3 proposal waits for a person");
        return engine.approve(pending.token(), ALICE);
    }

    private String claimStatus(String toolName) {
        String key = WriteClaimService.keyFor(ALICE, toolName, CanonicalJson.json(Map.of()));
        return claims.findByIdempotencyKey(key).map(WriteClaim::getStatus).orElse(null);
    }
}
