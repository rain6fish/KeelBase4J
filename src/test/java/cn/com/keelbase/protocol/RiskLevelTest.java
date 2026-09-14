// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** G0 conformance: reproduce {@code risk-level-v1-vector.json} (strategy table + derivation). */
class RiskLevelTest {

    @Test
    void strategyTableMatchesVector() {
        Map<String, Object> doc = Vectors.map(Vectors.read("risk-level-v1-vector.json"));
        Map<String, Object> strategies = Vectors.map(doc.get("strategies"));
        assertEquals(strategies.size(), RiskLevel.RISK_STRATEGY.size(), "strategy table size");
        strategies.forEach((level, strategy) ->
                assertEquals(strategy, RiskLevel.RISK_STRATEGY.get(level), "strategy " + level));
    }

    @TestFactory
    List<DynamicTest> derivationCases() {
        Map<String, Object> doc = Vectors.map(Vectors.read("risk-level-v1-vector.json"));
        return Vectors.dynamic(Vectors.list(doc, "cases"), c -> {
            Map<String, Object> tc = Vectors.map(c);
            String id = Vectors.str(tc, "id");
            Map<String, Object> in = Vectors.map(tc.get("in"));
            Map<String, Object> expect = Vectors.map(tc.get("expect"));

            String declared = (String) in.get("riskLevel");
            boolean requiresConfirmation = Boolean.TRUE.equals(in.get("requiresConfirmation"));
            String level = RiskLevel.resolve(declared, requiresConfirmation);

            assertEquals(Vectors.str(expect, "level"), level, id + ": derived level");
            assertEquals(Vectors.str(expect, "strategy"), RiskLevel.strategy(level), id + ": strategy");
            assertEquals(Boolean.TRUE.equals(expect.get("needsConfirmation")),
                    RiskLevel.needsConfirmation(level), id + ": needsConfirmation");
        });
    }
}
