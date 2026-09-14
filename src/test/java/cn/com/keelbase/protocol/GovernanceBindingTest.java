// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** G0 conformance: reproduce {@code governance-binding-v1-vector.json} (decision binding + deny vocabulary). */
class GovernanceBindingTest {

    @Test
    void denyVocabularyMatchesVector() {
        Map<String, Object> doc = Vectors.map(Vectors.read("governance-binding-v1-vector.json"));
        List<String> expected = new ArrayList<>();
        for (Object o : Vectors.list(doc, "denyChecks")) {
            expected.add((String) o);
        }
        assertEquals(expected, GovernanceBinding.DENY_CHECKS, "deny-check vocabulary");
    }

    @TestFactory
    List<DynamicTest> gateOutcomeByStrategy() {
        Map<String, Object> doc = Vectors.map(Vectors.read("governance-binding-v1-vector.json"));
        Map<String, Object> byStrategy = Vectors.map(doc.get("gateOutcomeByStrategy"));
        List<DynamicTest> tests = new ArrayList<>();
        byStrategy.forEach((strategy, o) -> tests.add(DynamicTest.dynamicTest(strategy, () -> {
            Map<String, Object> e = Vectors.map(o);
            GovernanceBinding.GateOutcome g = GovernanceBinding.gate(strategy);
            assertEquals(Vectors.str(e, "outcome"), g.outcome(), strategy + ": outcome");
            assertEquals(Boolean.TRUE.equals(e.get("executes")), g.executes(), strategy + ": executes");
            assertEquals(Boolean.TRUE.equals(e.get("requiresConfirmation")), g.requiresConfirmation(), strategy + ": requiresConfirmation");
            assertEquals(Boolean.TRUE.equals(e.get("requiresApproval")), g.requiresApproval(), strategy + ": requiresApproval");
            assertEquals(Boolean.TRUE.equals(e.get("blocked")), g.blocked(), strategy + ": blocked");
        })));
        return tests;
    }

    @TestFactory
    List<DynamicTest> derivationBindsLevelToGate() {
        Map<String, Object> doc = Vectors.map(Vectors.read("governance-binding-v1-vector.json"));
        return Vectors.dynamic(Vectors.list(doc, "derivation"), c -> {
            Map<String, Object> e = Vectors.map(c);
            String level = Vectors.str(e, "riskLevel");
            String strategy = RiskLevel.strategy(level);
            assertEquals(Vectors.str(e, "strategy"), strategy, level + ": strategy");
            GovernanceBinding.GateOutcome g = GovernanceBinding.gate(strategy);
            assertEquals(Vectors.str(e, "outcome"), g.outcome(), level + ": outcome");
            assertEquals(Boolean.TRUE.equals(e.get("executes")), g.executes(), level + ": executes");
            assertEquals(Boolean.TRUE.equals(e.get("requiresConfirmation")), g.requiresConfirmation(), level + ": requiresConfirmation");
            assertEquals(Boolean.TRUE.equals(e.get("requiresApproval")), g.requiresApproval(), level + ": requiresApproval");
            assertEquals(Boolean.TRUE.equals(e.get("blocked")), g.blocked(), level + ": blocked");
        });
    }
}
