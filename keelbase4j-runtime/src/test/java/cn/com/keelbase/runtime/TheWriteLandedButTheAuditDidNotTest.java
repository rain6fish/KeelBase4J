// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.runtime.audit.AuditChainHeadRepository;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The crash drill for D28④: a host write that lands while the audit append does not.
 *
 * <p>{@code GovernedExecutionEngine} says so itself ({@code no transaction surrounds the engine call}):
 * the business write, the effect row and the audit row are separate commits, so a process that dies
 * between them leaves a write nobody audited. This drill makes that window deterministic instead of
 * leaving it to a killed process: a one-shot failure is injected into the audit sink <em>after</em> the
 * tool ran, the effect was recorded and the write claim was settled.
 *
 * <p>What it asserts is that the gap is <b>detectable from the rows the runtime already keeps</b>:
 * an effect row carrying an {@code execution_id} whose chain has no terminal row — any outcome other
 * than {@code attempted} — under the same id. It also asserts the claim does not stay
 * stuck — the existing semantics settle it before the terminal audit append, so a retry is served by
 * the reuse branch rather than refused with {@code 409 claimed} — and that the retry adds no second
 * business row and no second effect.
 *
 * <p>The control test runs the identical drill with the injection disarmed: the terminal row is
 * written, so the detector reports no gap. That is the reverse direction the drill needs — removing
 * the injection makes the gap disappear.
 *
 * <p><b>Coverage declaration.</b> This drill supports declaring {@code
 * host-write-not-in-same-transaction} as a <em>known, detectable residual</em>, in this exact sense:
 * a landed write whose effect row also landed is detectable — the effect names an
 * {@code execution_id}, and the absence of any non-{@code attempted} row under that id names it. The
 * narrower window between the tool's own commit and the effect insert leaves no row to join on, so
 * it stays unobservable from the tables alone. The drill does not remove the window; it makes the
 * larger part of it observable after the fact.
 *
 * <p>**D28④ 的崩溃演练：宿主写落了地，而审计 append 没有。**
 *
 * <p>{@code GovernedExecutionEngine} 自己写着（{@code no transaction surrounds the engine call}）：业务写、
 * effect 行与审计行是三次独立的提交，于是死在两者之间的进程会留下一次**没人审计的写**。本演练把这个窗口
 * **确定性地**造出来，而不去等一次真实的进程被杀：在工具跑完、effect 已记、写声明已 settle **之后**，向审计
 * 接收端注入一次性失败。
 *
 * <p>它断言这个缺口**能从运行时已经保留的行里被检测到**：一条带着 {@code execution_id} 的 effect 行，
 * 而同一个 id 下的链上**没有终局行**（outcome 不是 {@code attempted} 的任何一行）。它同时断言写声明不会卡死——
 * 现有语义在终局审计 append **之前**就 settle，于是重试会走复用分支、而不是被 {@code 409 claimed} 拒掉——
 * 并且重试不会多写一行业务数据、也不会多记一条 effect。
 *
 * <p>对照用例用**没有注入失败**的同一套演练：终局行被写下了，于是检测器报告**没有缺口**。这就是演练需要的
 * 反方向——拿掉注入，缺口就消失。
 *
 * <p><b>覆盖声明。</b>本演练支持把 {@code host-write-not-in-same-transaction} 声明为**已知、可检测的
 * 残余**，口径正好是：**effect 行也已落地**的那类写是可检测的 —— effect 点名了 {@code execution_id}，
 * 而该 id 下**缺少任何非 {@code attempted} 的行**，正好点名了那些「已执行未审计」的写。工具自己的提交与
 * effect 插入之间那个**更窄**的窗口不留任何可连接的行，只靠表**观测不到**。它不消除这个窗口；它让窗口的
 * **较大那一半**在事后**可观测**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TheWriteLandedButTheAuditDidNotTest {

    private static final String TOOL = "drill_write_landed_audit_missing";
    private static final String GAP_CALLER = "crash-drill-gap";
    private static final String CONTROL_CALLER = "crash-drill-control";
    private static final long GAP_CUSTOMER = 91_001L;
    private static final long CONTROL_CUSTOMER = 91_002L;

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    SideEffectRepository sideEffects;

    @Autowired
    WriteClaimRepository claims;

    @Autowired
    AuditLogRepository auditLogs;

    @BeforeEach
    void disarmTheInjection() {
        DrillingAuditSink.FAILURES_REMAINING.set(0);
    }

    @Test
    void aLandedWriteWithoutATerminalAuditRowIsDetectableAndTheClaimSettles() {
        DrillingAuditSink.FAILURES_REMAINING.set(1);
        Principal caller = new Principal(GAP_CALLER, Principal.ROLE_USER);
        String note = "gap-" + UUID.randomUUID();
        Map<String, Object> args = Map.of("customerId", GAP_CUSTOMER, "note", note);
        String argsJson = CanonicalJson.json(args);

        // The sink fails on the terminal row, so the exception reaches the caller — that is the
        // crash. Everything before it has already committed.
        //
        // 接收端在终局行上失败，异常于是到达调用方——这就是那次「崩溃」。它之前的一切都已提交。
        assertThrows(IllegalStateException.class, () -> engine.execute(TOOL, args, caller),
                "the injected sink failure must escape the engine; otherwise nothing crashed");

        // The business row landed: this is the write the audit never got to describe.
        //
        // 业务行落了地：这就是审计最终没能描述的那次写。
        List<FollowUp> written = followUps.findByCustomerIdAndDeletedAtIsNull(GAP_CUSTOMER);
        assertEquals(1, written.size(), "the drill's tool must have written exactly one business row");
        assertEquals(note, written.get(0).getNote(), "and it is the row this call wrote");

        // The effect row landed and names the execution — this is half of the detector.
        //
        // effect 行落了地、并点出这次执行——这是检测器的一半。
        Optional<SideEffect> effect = sideEffects.findByIdempotencyKey(
                WriteClaimService.keyFor(caller, TOOL, argsJson));
        assertTrue(effect.isPresent(), "the ledger must hold the effect the write produced");
        String executionId = effect.get().getExecutionId();
        assertNotNull(executionId, "the effect row must carry the execution it belongs to");

        // The other half: the chain has the intent row but no terminal row under that execution.
        //
        // 另一半：链上有 intent 行，但同一个执行下没有终局行。
        List<AuditLog> rows = auditLogs.findByExecutionIdOrderByIdAsc(executionId);
        assertEquals(1, rows.size(), "only the pre-execution intent row could be written: " + rows);
        assertEquals("attempted", rows.get(0).getOutcome(), "and it is the intent, not an outcome");
        assertTrue(unterminated(executionId),
                "the drill must expose the window: an effect exists and no terminal audit row does");

        // The write claim is settled before the terminal append, so the crash cannot strand the key.
        //
        // 写声明在终局 append 之前就 settle 了，故这次崩溃不会把键**永久**占住。
        WriteClaim claim = claims.findByIdempotencyKey(
                WriteClaimService.keyFor(caller, TOOL, argsJson)).orElseThrow();
        assertEquals(WriteClaim.SETTLED, claim.getStatus(),
                "the claim settles before the terminal audit row, so the key is not left claimed");
        assertEquals(effect.get().getId(), claim.getEffectId(),
                "and it points at the effect the write produced");

        // A repeat is served by the reuse branch: no 409, no second business row, no second effect.
        //
        // 重复调用走复用分支：没有 409、没有第二行业务数据、没有第二条 effect。
        ExecutionOutcome replay = engine.execute(TOOL, args, caller);
        assertEquals("executed", replay.status(), "the repeat is not refused with 409 claimed");
        assertEquals(effect.get().getId(), replay.effectId(), "it reuses the effect already written");
        assertEquals(1, followUps.findByCustomerIdAndDeletedAtIsNull(GAP_CUSTOMER).size(),
                "the repeat writes no second business row");
        assertEquals(1, sideEffects.findByExecutionIdOrderByIdAsc(executionId).size(),
                "the repeat writes no second effect under the crashed execution");
        assertEquals(WriteClaim.SETTLED, claims.findByIdempotencyKey(
                        WriteClaimService.keyFor(caller, TOOL, argsJson)).orElseThrow().getStatus(),
                "and the claim stays settled, not claimed");
    }

    /**
     * The reverse control: the identical call with the sink healthy leaves a terminal row, so the
     * detector reports no gap. Removing the injection is what turns the gap off.
     *
     * 反方向对照：同一次调用、接收端健康，会留下终局行，于是检测器报告没有缺口 —— **拿掉注入，缺口就消失**。
     */
    @Test
    void withTheSinkHealthyTheSameDrillReportsNoGap() {
        Principal caller = new Principal(CONTROL_CALLER, Principal.ROLE_USER);
        Map<String, Object> args =
                Map.of("customerId", CONTROL_CUSTOMER, "note", "control-" + UUID.randomUUID());

        ExecutionOutcome outcome = engine.execute(TOOL, args, caller);

        assertEquals("executed", outcome.status(), "the healthy run executes");
        assertFalse(unterminated(sideEffects
                        .findByIdempotencyKey(WriteClaimService.keyFor(
                                caller, TOOL, CanonicalJson.json(args))).orElseThrow()
                        .getExecutionId()),
                "with no injected failure the terminal row is written and the gap does not exist");
    }

    /**
     * The detector under test: an execution the ledger says wrote, with only its intent row on the
     * chain. Any other outcome — {@code executed}, {@code failed}, {@code refused} — is the attempt's
     * recorded end, so only {@code attempted} means the execution was never described.
     *
     * 被演练的检测器：账本说它写过，而链上**只有它的 intent 行**。其它任何结局——{@code executed}、
     * {@code failed}、{@code refused}——都是这次尝试被记下的结尾，故只有 {@code attempted} 意味着这次执行
     * **从未被描述**。
     */
    private boolean unterminated(String executionId) {
        return auditLogs.findByExecutionIdOrderByIdAsc(executionId).stream()
                .noneMatch(row -> !"attempted".equals(row.getOutcome()));
    }

    @TestConfiguration
    static class DrillingAuditSink {

        /** How many terminal appends the one-shot outage may consume. Zero is the healthy sink. */
        static final AtomicInteger FAILURES_REMAINING = new AtomicInteger();

        /**
         * A primary audit service that can fail the terminal append exactly once.
         *
         * <p>The real bean stays registered; this one is injected because it is primary, which is the
         * least invasive way to instrument a concrete service without changing production code.
         *
         * 一个可以让终局 append **恰好失败一次**的 primary 审计服务。真 bean 仍在容器里；这个因为 primary
         * 而被注入 —— 这是给一个具体类装仪器、又不动生产代码的最小做法。
         */
        @Bean
        @Primary
        AuditService drillingAuditService(AuditLogRepository repository,
                                          AuditChainHeadRepository heads,
                                          GovernancePolicyStore policy,
                                          @Value("${keelbase.audit.hmac-key}") String chainKey) {
            return new FailingTerminalAppend(repository, heads, policy, chainKey);
        }

        /** Writes one real business row, then answers with its id — a write the runtime can record. */
        @Bean
        AiTool drillWriteLandedAuditMissingTool(FollowUpRepository followUps) {
            return new AiTool() {
                @Override
                public String name() {
                    return TOOL;
                }

                @Override
                public String description() {
                    return "test double: writes a business row so the crash drill has one to lose";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public String resultType() {
                    return "follow_up";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    Long customerId = ((Number) args.get("customerId")).longValue();
                    FollowUp saved = followUps.save(new FollowUp(customerId,
                            String.valueOf(args.get("note")), null, principal.userId()));
                    return ToolResult.ok(Map.of("id", saved.getId()));
                }
            };
        }
    }

    /**
     * Fails the appends that would record a successful execution, once, after everything before them
     * has committed. The predicate is deliberately narrow: the intent row ({@code attempted}) and the
     * reuse branch's row pass through, so the drill observes exactly the terminal row of the first
     * execution.
     *
     * 让「记录一次成功执行」的 append 失败一次，且是在它之前的一切都已提交之后。判定刻意收窄：intent
     * 行（{@code attempted}）与复用分支的行都放行，于是演练观察到的**正好是第一次执行的终局行**。
     */
    static class FailingTerminalAppend extends AuditService {

        FailingTerminalAppend(AuditLogRepository repository, AuditChainHeadRepository heads,
                              GovernancePolicyStore policy, String chainKey) {
            super(repository, heads, policy, chainKey);
        }

        @Override
        @Transactional
        public AuditLog append(String action, String userId, String detail, boolean isError,
                               String outcome, String tool, String conversationId,
                               String authorization, String executionId) {
            if (DrillingAuditSink.FAILURES_REMAINING.get() > 0
                    && "executed".equals(outcome) && detail.endsWith("-> ok")
                    && DrillingAuditSink.FAILURES_REMAINING.compareAndSet(1, 0)) {
                throw new IllegalStateException(
                        "drill: the audit sink went down after the write landed");
            }
            return super.append(action, userId, detail, isError, outcome, tool, conversationId,
                    authorization, executionId);
        }
    }
}
