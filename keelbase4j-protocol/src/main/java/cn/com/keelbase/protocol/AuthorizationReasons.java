// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why a tool call was allowed, gated or blocked — explainable authorization for one call.
 *
 * <p>Java carrier of the frozen wire contract {@code authorization.schema.json} (main repo,
 * {@code AuthorizationReasons}, W5-⑦). The runtime answers "may AI do this under enterprise rules"
 * with the tool, its risk, the resulting strategy, and the individual checks that were evaluated —
 * so a refusal is structured data on the decision trace, not a bare string.
 *
 * <p>{@link Check} names come from {@link GovernanceBinding#DENY_CHECKS}, the frozen denial vocabulary.
 *
 * @param policy governance-policy revision this decision was taken under; {@code null} when the
 *               runtime holds no revisioned policy (it must not invent one)
 */
public record AuthorizationReasons(
        String tool,
        String riskLevel,
        String riskStrategy,
        boolean requiresConfirmation,
        List<Check> checks,
        Policy policy) {

    /** One evaluated check: its name from the denial vocabulary, its outcome, and why. */
    public record Check(String name, boolean ok, String note) {
    }

    public record Policy(String revision) {
    }

    /** The wire shape of one check; {@code note} is omitted when there is nothing to say. */
    private static Map<String, Object> checkWire(Check check) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("name", check.name());
        wire.put("ok", check.ok());
        if (check.note() != null) {
            wire.put("note", check.note());
        }
        return wire;
    }

    /** The wire shape: only the checks this runtime actually evaluated. */
    public Map<String, Object> toWire() {
        List<Object> checkWires = new ArrayList<>();
        for (Check check : checks) {
            checkWires.add(checkWire(check));
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("tool", tool);
        wire.put("riskLevel", riskLevel);
        wire.put("riskStrategy", riskStrategy);
        wire.put("requiresConfirmation", requiresConfirmation);
        wire.put("checks", checkWires);
        if (policy != null) {
            wire.put("policy", Map.of("revision", policy.revision()));
        }
        return wire;
    }
}
