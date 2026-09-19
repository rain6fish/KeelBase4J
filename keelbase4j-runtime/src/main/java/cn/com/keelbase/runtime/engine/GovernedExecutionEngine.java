// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.GateDecision;
import cn.com.keelbase.runtime.governance.GovernanceService;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
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

    public GovernedExecutionEngine(
            ToolRegistry registry,
            GovernanceService governance,
            ConfirmationStore confirmations,
            SideEffectService sideEffects,
            AuditService audit) {
        this.registry = registry;
        this.governance = governance;
        this.confirmations = confirmations;
        this.sideEffects = sideEffects;
        this.audit = audit;
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
        ConfirmationRequest req = confirmations.claim(token, principal, ConfirmationLifecycle.APPROVED);
        AiTool tool = registry.require(req.getToolName());
        audit.append("tool_confirmation", principal.userId(), tool.name() + " approved");
        ExecutionOutcome outcome = run(tool, parseArgs(req.getArgsJson()), principal);
        req.setResultId(outcome.effectId());
        confirmations.save(req);
        return outcome;
    }

    /** Decline a pending confirmation — nothing is written. */
    public ExecutionOutcome decline(String token, Principal principal) {
        ConfirmationRequest req = confirmations.claim(token, principal, ConfirmationLifecycle.DECLINED);
        audit.append("tool_confirmation", principal.userId(), req.getToolName() + " declined");
        return new ExecutionOutcome(ConfirmationLifecycle.DECLINED, null, null, null, null);
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
