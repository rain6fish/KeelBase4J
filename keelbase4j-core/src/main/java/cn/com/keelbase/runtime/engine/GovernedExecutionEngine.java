// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.ConfirmationWatchers;
import cn.com.keelbase.runtime.governance.GateDecision;
import cn.com.keelbase.runtime.governance.GovernanceService;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The trust loop. Every AI tool call goes through here — there is no path that executes a tool
 * while bypassing the gate, the audit, or the side-effect ledger.
 *
 * <pre>
 *   gate(risk) ─┬─ BLOCK             → audit, never execute
 *               ├─ REQUIRE_APPROVAL  → audit, wait for a second party
 *               ├─ CONFIRM           → audit, issue a token, wait for the operator
 *               └─ ALLOW             → audit, execute, record side effect
 * </pre>
 */
@Service
public class GovernedExecutionEngine {

    private final ToolRegistry registry;
    private final GovernanceService governance;
    private final ConfirmationStore confirmations;
    private final SideEffectService sideEffects;
    private final AuditService audit;
    private final ConfirmationWatchers watchers;

    public GovernedExecutionEngine(
            ToolRegistry registry,
            GovernanceService governance,
            ConfirmationStore confirmations,
            SideEffectService sideEffects,
            AuditService audit,
            ConfirmationWatchers watchers) {
        this.registry = registry;
        this.governance = governance;
        this.confirmations = confirmations;
        this.sideEffects = sideEffects;
        this.audit = audit;
        this.watchers = watchers;
    }

    /** Gate and (if allowed) execute a tool call. */
    public ExecutionOutcome execute(String toolName, Map<String, Object> args, Principal principal) {
        AiTool tool = registry.require(toolName);
        GateDecision decision = governance.decide(tool, principal);
        switch (decision) {
            case BLOCK:
                audit.append("tool_call", principal.userId(), toolName + " blocked (risk policy)");
                return new ExecutionOutcome("blocked", governance.reasons(tool).toWire(), null, null,
                        "blocked by risk policy");
            case REQUIRE_APPROVAL:
                audit.append("tool_call", principal.userId(), toolName + " requires approval");
                return new ExecutionOutcome("requires_approval", null, null, null, null);
            case CONFIRM: {
                ConfirmationRequest req = confirmations.create(
                        principal, toolName, CanonicalJson.json(args), tool.riskLevel());
                audit.append("tool_call", principal.userId(), toolName + " pending confirmation");
                return new ExecutionOutcome("pending_confirmation", null, req.getToken(), null, null);
            }
            default:
                return run(tool, args, principal);
        }
    }

    /**
     * Approve a pending confirmation and perform the write.
     *
     * <p><b>The claim comes first, and it is what makes the write single.</b> Claiming takes the row
     * out of {@code pending} atomically, so a second approval arriving at the same instant loses the
     * claim and never reaches the tool. The order that reads more naturally — check it is pending,
     * execute, then record — is the order that lets two winners through, because both check before
     * either records.
     */
    public ExecutionOutcome approve(String token, Principal principal) {
        return performApproval(confirmations.claim(token, principal, ConfirmationLifecycle.APPROVED),
                principal, ConfirmationLifecycle.IN_BAND);
    }

    /** Decline a pending confirmation — nothing is written. */
    public ExecutionOutcome decline(String token, Principal principal) {
        return performDecline(confirmations.claim(token, principal, ConfirmationLifecycle.DECLINED),
                principal, ConfirmationLifecycle.IN_BAND);
    }

    /**
     * Decide this operator's confirmation from outside the conversation — the Action Center, after the
     * dialogue has been left (ADR-0015).
     *
     * <p>Same transitions as the in-band path and the same single execution, because the claim still
     * comes first; what differs is the guards, which are the frozen lifecycle's out-of-band ones, and
     * the fact that a lost claim is reported as a no-op instead of raised as a conflict.
     */
    public OutOfBandDecision decideOutOfBand(String token, Principal principal, String decision) {
        ConfirmationStore.OutOfBandResult result = confirmations.decideOutOfBand(token, principal, decision,
                Instant.now(), ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS);
        return switch (result.outcome()) {
            case NOT_FOUND -> new OutOfBandDecision(false, null, null, "not found");
            case ALREADY_DECIDED -> new OutOfBandDecision(false, null, null, "already decided");
            case EXPIRED -> new OutOfBandDecision(false, null, null, "the offline window has closed");
            case DECIDED -> {
                ExecutionOutcome outcome = ConfirmationLifecycle.APPROVE.equals(decision)
                        ? performApproval(result.request(), principal, ConfirmationLifecycle.OUT_OF_BAND)
                        : performDecline(result.request(), principal, ConfirmationLifecycle.OUT_OF_BAND);
                yield new OutOfBandDecision(true, "executed".equals(outcome.status()), outcome.effectId(),
                        outcome.error());
            }
        };
    }

    /**
     * The approval tail, shared by both decision paths: audit, execute, record the effect, then tell
     * whoever is watching.
     *
     * <p>{@code via} is recorded in the audit because the frozen lifecycle says a decision carries
     * where it was taken; the state machine does not fork on it, and neither does the wire.
     */
    private ExecutionOutcome performApproval(ConfirmationRequest req, Principal principal, String via) {
        AiTool tool = registry.require(req.getToolName());
        audit.append("tool_confirmation", principal.userId(), tool.name() + " approved (" + via + ")");
        // Claim the execution before running it (ADR-0016): the row then records that an attempt took
        // it, so an attempt that never reports back reads as such instead of as "approved, nothing
        // happened". The claim is the lease — an attempt older than it derives `failed`.
        req.setExecutionClaimedAt(Instant.now());
        confirmations.save(req);
        ExecutionOutcome outcome;
        try {
            outcome = run(tool, parseArgs(req.getArgsJson()), principal);
        } catch (RuntimeException failed) {
            // The tool threw instead of answering. The attempt is recorded as failed and keeps its
            // claim on purpose: clearing it would say the attempt never happened, and a claim with no
            // result is exactly what the lease turns into `failed`.
            req.setExecutionError(message(failed));
            confirmations.save(req);
            throw failed;
        }
        if ("executed".equals(outcome.status())) {
            req.setExecutedAt(Instant.now());
            req.setExecutionError(null);
        } else {
            req.setExecutionError(outcome.error() == null ? "execution failed" : outcome.error());
        }
        req.setResultId(outcome.effectId());
        confirmations.save(req);
        // Last, and after the row is durable: whoever is watching the stream is told once the decision
        // is a fact, not before. An out-of-band decision reaches an open stream the same way — the
        // stream reports events, it does not execute, so telling it cannot run the tool twice.
        watchers.decided(req.getToken(), decision(ConfirmationLifecycle.APPROVE, req.getToolName(), outcome));
        return outcome;
    }

    private static String message(RuntimeException failed) {
        return failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
    }

    /** The decline tail. Nothing is written, and whoever is watching is told the same way. */
    private ExecutionOutcome performDecline(ConfirmationRequest req, Principal principal, String via) {
        audit.append("tool_confirmation", principal.userId(), req.getToolName() + " declined (" + via + ")");
        ExecutionOutcome outcome =
                new ExecutionOutcome(ConfirmationLifecycle.DECLINED, null, null, null, null);
        watchers.decided(req.getToken(), decision(ConfirmationLifecycle.DECLINE, req.getToolName(), outcome));
        return outcome;
    }

    /**
     * What to tell a waiting stream. Shaped like the reference's {@code AiConfirmationDecision}: the
     * decision word, whether it approved, whether the tool ran, and the result if it did.
     */
    private Map<String, Object> decision(String decision, String toolName, ExecutionOutcome outcome) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("toolName", toolName);
        d.put("decision", decision);
        d.put("approved", "approve".equals(decision));
        d.put("success", "executed".equals(outcome.status()));
        if (outcome.effectId() != null) {
            d.put("resultId", outcome.effectId());
        }
        if (outcome.error() != null) {
            d.put("error", outcome.error());
        }
        return d;
    }

    /**
     * Execute an allowed tool, and audit it — including the case where it refuses.
     *
     * <p><b>The refusal is an event, so it gets a line.</b> The audit below is composed from the
     * outcome, which means it can only be written once an outcome exists. A refusal arrives as an
     * exception — the row scope rejecting a row that is not the caller's, for instance — and control
     * leaves before that line is reached. The effect is that the audit reports what succeeded and
     * never what was attempted and refused, which is the half an operator asks about first: not "what
     * did the AI do" but "did anything reach for what it should not". Measured before it was reasoned
     * about: an out-of-scope read left no audit row at all.
     *
     * <p>The catch is deliberately narrow in what it changes and broad in what it catches. It adds a
     * line and rethrows — the call is still refused, it is only no longer silent — and it catches
     * every {@code RuntimeException} rather than filtering for the type that happens to be thrown
     * today, because a filter would leave the next kind of refusal silently unrecorded, which is the
     * defect it exists to fix. A crash inside a tool is also an attempt worth seeing.
     *
     * <p>Nothing here can roll the line back: no transaction surrounds the engine call, so the audit
     * service's own transaction commits on its own.
     *
     * 执行一个被允许的工具，并审计它——**包括它拒绝的时候**。
     *
     * <p><b>拒绝本身就是一个事件，所以它要有一行。</b>下面那次审计是**由结果拼出来的**，也就是说只有
     * 结果存在时才写得出来。拒绝以异常到来——比如行范围驳回了一行不属于调用方的数据——控制流在到达那
     * 一行之前就离开了。后果是：审计报告「**做成了什么**」，而不报告「**尝试了什么、被拒了**」，而后者
     * 恰恰是运维第一个会问的那一半：不是「AI 做了什么」，而是「**有没有什么东西伸手去够了它不该够的**」。
     * **先测到、后推理**：一次越界读没有留下任何审计行。
     *
     * <p>这个 catch 在「改什么」上有意收窄、在「捕什么」上有意放宽。它**只加一行、再原样抛出**——调用
     * 仍然被拒，只是不再无声——并且捕**所有** {@code RuntimeException}，而不是按今天恰好抛出的那个类型
     * 过滤；因为过滤会让**下一种**拒绝继续被静默漏记，而那正是它要修的缺陷。工具内部的崩溃同样是一次
     * 值得被看见的尝试。
     *
     * <p>这里写下的行**不可能被回滚**：引擎调用外面没有事务，审计服务自己的事务独立提交。
     */
    private ExecutionOutcome run(AiTool tool, Map<String, Object> args, Principal principal) {
        ToolResult result;
        try {
            ExecutionOutcome reused = reuseIfAlreadyExecuted(tool, args, principal);
            if (reused != null) {
                return reused;
            }
            result = tool.execute(args, principal); // may throw 403 — before any write
        } catch (RuntimeException refused) {
            audit.append("tool_call", principal.userId(),
                    tool.name() + " refused: " + refused.getMessage());
            throw refused;
        }
        Long effectId = null;
        if (result.success() && tool.resultType() != null) {
            Long resultId = resultId(result.data());
            SideEffect effect = sideEffects.record(
                    principal, tool.name(), tool.resultType(), resultId,
                    CanonicalJson.json(args), tool.revokeClass());
            effectId = effect.getId();
        }
        audit.append("tool_call", principal.userId(),
                tool.name() + " -> " + (result.success() ? "ok" : "fail: " + result.error()));
        return result.success()
                ? ExecutionOutcome.executed(result.data(), effectId)
                : new ExecutionOutcome("error", null, null, null, result.error());
    }

    /**
     * The effect this exact call already produced, if it did — asked <b>before</b> the tool runs.
     *
     * <p><b>Why before, and why it is a fix rather than an optimisation.</b> The ledger's idempotency
     * key is content-derived, and its javadoc promises that "re-running the same call reuses the
     * existing effect instead of writing twice". The write did not honour that: the tool wrote a new
     * row every time and the ledger then deduped, so the second identical call left a row <em>named by
     * no effect</em> — a write with no revocation path, invisible to the ledger and unreachable by
     * {@code revoke}. Measured on a host (seam S11), and only by a rerun: a single pass writes each
     * distinct call once. Asking first is what makes the promise true instead of merely stated.
     *
     * <p>A revoked effect keeps its key, and the call is refused rather than quietly remade under it —
     * the same answer the reference implementation gives (its write pipeline probes by key and returns
     * early, and a revoked effect still occupies the key). Making the same thing again is a different
     * request and needs different arguments; pretending otherwise would either write an untracked row
     * or silently do nothing.
     *
     * <p><b>What this does not cover, and the reference does:</b> two identical calls <em>at the same
     * instant</em> can both probe and miss and both write, leaving the loser's row untracked again.
     * The reference arbitrates that with a write claim taken before execution; this runtime has no such
     * row, so the concurrent case remains open and is recorded as such rather than claimed.
     */
    private ExecutionOutcome reuseIfAlreadyExecuted(AiTool tool, Map<String, Object> args,
                                                   Principal principal) {
        SideEffect existing = sideEffects
                .existingFor(principal, tool.name(), CanonicalJson.json(args))
                .orElse(null);
        if (existing == null) {
            return null;
        }
        if ("revoked".equals(existing.getRevokeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this call was revoked (effect " + existing.getId() + ") and its key stays occupied"
                            + " — change the arguments to make it again");
        }
        audit.append("tool_call", principal.userId(),
                tool.name() + " -> ok (effect " + existing.getId() + " reused: the same call)");
        return ExecutionOutcome.executed(
                existing.getResultId() == null ? null : Map.of("id", existing.getResultId()),
                existing.getId());
    }

    private static Long resultId(Object data) {
        if (data instanceof Map<?, ?> m && m.get("id") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String argsJson) {
        return (Map<String, Object>) cn.com.keelbase.protocol.Json.parse(argsJson);
    }
}
