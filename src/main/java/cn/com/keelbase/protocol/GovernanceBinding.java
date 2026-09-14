// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.List;

/**
 * AI Governance Protocol §4.3/§4.4 — the tool → policy → decision binding: the frozen mapping
 * from a risk strategy to the gate outcome, plus the closed vocabulary of denial reasons.
 *
 * <p>This is the cross-runtime contract for "why allowed / why blocked": any implementation must
 * agree on the outcome semantics and the denial-reason vocabulary so decisions are comparable.
 */
public final class GovernanceBinding {

    /** Closed set of denial reasons a gate may report. */
    public static final List<String> DENY_CHECKS = List.of(
            "risk_policy",
            "tool_enabled",
            "role_allowed",
            "feature_flag",
            "admin_only",
            "agent_read_only");

    /** Gate outcome for a risk strategy. */
    public record GateOutcome(
            String outcome,
            boolean executes,
            boolean requiresConfirmation,
            boolean requiresApproval,
            boolean blocked) {
    }

    private GovernanceBinding() {
    }

    /** Map a risk strategy to its gate outcome (mirrors the frozen table in the vector). */
    public static GateOutcome gate(String strategy) {
        switch (strategy) {
            case "auto":
                return new GateOutcome("allow", true, false, false, false);
            case "policy":
                return new GateOutcome("allow_unless_policy_denies", true, false, false, false);
            case "confirmation":
                return new GateOutcome("requiresConfirmation", false, true, false, false);
            case "human_approval":
                return new GateOutcome("requiresApproval", false, false, true, false);
            case "block":
                return new GateOutcome("denied", false, false, false, true);
            default:
                throw new IllegalArgumentException("unknown strategy: " + strategy);
        }
    }
}
