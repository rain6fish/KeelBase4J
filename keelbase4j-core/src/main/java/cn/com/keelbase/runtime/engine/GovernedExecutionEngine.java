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
import org.springframework.stereotype.Service;

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

    private ExecutionOutcome run(AiTool tool, Map<String, Object> args, Principal principal) {
        ToolResult result = tool.execute(args, principal); // may throw 403 — before any write
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
