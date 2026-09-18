// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Conformance: reproduce {@code confirmation-lifecycle-v1-vector.json}.
 *
 * <p>Same binding the reference makes in {@code confirmation.store.spec.ts} — state set, decision
 * set and default TTL — plus the parts the reference freezes but does not assert (the transition
 * table and the resolve guards), so a change to either side fails here.
 */
class ConfirmationLifecycleTest {

    private static Map<String, Object> vector() {
        return Vectors.map(Vectors.read("confirmation-lifecycle-v1-vector.json"));
    }

    private static List<String> strings(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : Vectors.list(m, key)) {
            out.add((String) o);
        }
        return out;
    }

    @Test
    void statusAndOutcomeVocabularyMatchesVector() {
        Map<String, Object> vec = vector();
        assertEquals(strings(vec, "states"), ConfirmationLifecycle.STATUSES, "state set");
        assertEquals(strings(vec, "outcomes"), ConfirmationLifecycle.OUTCOMES, "decision set");
    }

    @Test
    void initialStateTerminalStatesAndTtlMatchVector() {
        Map<String, Object> vec = vector();
        assertEquals(Vectors.str(vec, "initialState"), ConfirmationLifecycle.INITIAL_STATE);
        assertEquals(strings(vec, "terminalStates"), ConfirmationLifecycle.TERMINAL_STATES);
        assertEquals(ConfirmationLifecycle.DEFAULT_TTL_MILLIS,
                ((Number) vec.get("defaultTtlSeconds")).longValue() * 1000L, "default TTL");
    }

    @Test
    void transitionsMatchVector() {
        Map<String, Object> vec = vector();
        List<ConfirmationLifecycle.Transition> expected = new ArrayList<>();
        for (Object o : Vectors.list(vec, "transitions")) {
            Map<String, Object> t = Vectors.map(o);
            expected.add(new ConfirmationLifecycle.Transition(
                    Vectors.str(t, "from"), Vectors.str(t, "event"),
                    Vectors.str(t, "by"), Vectors.str(t, "to")));
        }
        assertEquals(expected, ConfirmationLifecycle.TRANSITIONS, "allowed transitions");
        assertEquals(ConfirmationLifecycle.INITIAL_STATE, ConfirmationLifecycle.TRANSITIONS.get(0).from(),
                "every transition starts at the initial state");
    }

    @Test
    void resolveGuardsMatchVector() {
        Map<String, Object> guards = Vectors.map(vector().get("guards"));
        assertEquals(Vectors.str(guards, "resolveByNonOwner"), ConfirmationLifecycle.RESOLVE_BY_NON_OWNER);
        assertEquals(Vectors.str(guards, "resolveUnknownToken"), ConfirmationLifecycle.RESOLVE_UNKNOWN_TOKEN);
        assertEquals(strings(guards, "declineAlias"), ConfirmationLifecycle.DECLINE_ALIASES);
    }

    @Test
    void decisionVocabularyIsClosedAndLegacyRejectNormalisesToDecline() {
        assertEquals(ConfirmationLifecycle.APPROVE, ConfirmationLifecycle.normalizeDecision("approve"));
        assertEquals(ConfirmationLifecycle.DECLINE, ConfirmationLifecycle.normalizeDecision("decline"));
        assertTrue(ConfirmationLifecycle.DECLINE_ALIASES.contains("reject"), "vector declares reject as an alias");
        assertEquals(ConfirmationLifecycle.DECLINE, ConfirmationLifecycle.normalizeDecision("reject"));
        assertThrows(IllegalArgumentException.class, () -> ConfirmationLifecycle.normalizeDecision("maybe"));
    }

    @Test
    void terminalStatesAreRecognised() {
        for (String terminal : ConfirmationLifecycle.TERMINAL_STATES) {
            assertTrue(ConfirmationLifecycle.isTerminal(terminal), terminal + " is terminal");
        }
        assertFalse(ConfirmationLifecycle.isTerminal(ConfirmationLifecycle.INITIAL_STATE), "pending is not terminal");
    }
}
