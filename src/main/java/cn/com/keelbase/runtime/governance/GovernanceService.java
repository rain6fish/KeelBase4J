// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.GovernanceBinding;
import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
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
}
