// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.List;
import java.util.Map;

/**
 * AI Governance Protocol §4 — tool risk levels and derivation.
 *
 * <p>R0 informational · R1 read · R2 low-risk write · R3 business-sensitive write ·
 * R4 high-impact action · R5 irreversible/external. Explicit {@code riskLevel} wins; otherwise
 * a write tool ({@code requiresConfirmation}) derives to R3 and a read tool to R1.
 */
public final class RiskLevel {

    public static final Map<String, String> RISK_STRATEGY = Map.of(
            "R0", "auto",
            "R1", "auto",
            "R2", "policy",
            "R3", "confirmation",
            "R4", "human_approval",
            "R5", "block");

    /** Levels that gate on a human decision. */
    public static final List<String> CONFIRMATION_LEVELS = List.of("R3", "R4");

    private RiskLevel() {
    }

    /** Derive the effective level: explicit declaration wins, else write → R3, read → R1. */
    public static String resolve(String declaredLevel, boolean requiresConfirmation) {
        if (declaredLevel != null && !declaredLevel.isBlank()) {
            return declaredLevel;
        }
        return requiresConfirmation ? "R3" : "R1";
    }

    public static String strategy(String level) {
        String s = RISK_STRATEGY.get(level);
        if (s == null) {
            throw new IllegalArgumentException("unknown risk level: " + level);
        }
        return s;
    }

    public static boolean needsConfirmation(String level) {
        return CONFIRMATION_LEVELS.contains(level);
    }
}
