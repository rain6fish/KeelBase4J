// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.AuthorizationReasons;
import cn.com.keelbase.protocol.GovernanceBinding;
import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Turns a tool's declared risk level into a gate decision, via the frozen protocol binding
 * ({@link RiskLevel} strategy table + {@link GovernanceBinding} outcome table). This is the
 * same binding the vectors pin — the Java runtime makes the same decision the reference does.
 */
@Service
public class GovernanceService {

    public GateDecision decide(AiTool tool, Principal principal) {
        String strategy = RiskLevel.strategy(tool.riskLevel());
        GovernanceBinding.GateOutcome outcome = GovernanceBinding.gate(strategy);
        if (outcome.blocked()) {
            return GateDecision.BLOCK;
        }
        if (outcome.requiresApproval()) {
            return GateDecision.REQUIRE_APPROVAL;
        }
        if (outcome.requiresConfirmation()) {
            return GateDecision.CONFIRM;
        }
        return GateDecision.ALLOW;
    }

    /**
     * Why the gate reached its verdict, in the frozen {@code authorization} vocabulary — so a
     * refusal reaches the caller as structured data rather than a bare sentence.
     *
     * <p>Only the checks this runtime actually evaluates are reported; it does not claim to have
     * checked policy toggles or role allowlists it does not have. Governance-policy revision is
     * absent for the same reason: the runtime holds no revisioned policy and must not invent one.
     */
    public AuthorizationReasons reasons(AiTool tool) {
        String strategy = RiskLevel.strategy(tool.riskLevel());
        GovernanceBinding.GateOutcome outcome = GovernanceBinding.gate(strategy);
        List<AuthorizationReasons.Check> checks = List.of(new AuthorizationReasons.Check(
                GovernanceBinding.DENY_RISK_POLICY,
                !outcome.blocked(),
                outcome.blocked()
                        ? tool.riskLevel() + " is irreversible or external — refused before any confirmation"
                        : null));
        return new AuthorizationReasons(tool.name(), tool.riskLevel(), strategy,
                outcome.requiresConfirmation(), checks, null);
    }
}
