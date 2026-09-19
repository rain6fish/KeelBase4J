// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Conformance: reproduce {@code confirmation-lifecycle-v2-vector.json}.
 *
 * <p>Same binding the reference makes in {@code confirmation.store.spec.ts} — state set, decision
 * set and the TTL windows — plus the parts the reference freezes but does not assert (the transition
 * table, which now carries {@code via}, and the resolve / out-of-band guards), so a change to either
 * side fails here.
 *
 * <p>The v1 file stays in the snapshot as protocol history and is <b>not</b> asserted against: v2
 * supersedes it by splitting the single TTL into a wait window and an offline window, so v1's one
 * {@code ttl_elapsed} event deliberately no longer exists in this class. Asserting the parts the two
 * versions happen to share would say nothing that the v2 assertions below do not already say.
 */
class ConfirmationLifecycleTest {

    private static Map<String, Object> vector() {
        return Vectors.map(Vectors.read("confirmation-lifecycle-v2-vector.json"));
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
    void initialStateTerminalStatesAndBothTtlWindowsMatchVector() {
        Map<String, Object> vec = vector();
        assertEquals(Vectors.str(vec, "initialState"), ConfirmationLifecycle.INITIAL_STATE);
        assertEquals(strings(vec, "terminalStates"), ConfirmationLifecycle.TERMINAL_STATES);
        assertEquals(ConfirmationLifecycle.DEFAULT_TTL_MILLIS,
                ((Number) vec.get("defaultTtlSeconds")).longValue() * 1000L, "the in-conversation wait");
        assertEquals(ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS,
                ((Number) vec.get("defaultOfflineTtlSeconds")).longValue() * 1000L,
                "the offline window");
    }

    @Test
    void transitionsMatchVector() {
        Map<String, Object> vec = vector();
        List<ConfirmationLifecycle.Transition> expected = new ArrayList<>();
        for (Object o : Vectors.list(vec, "transitions")) {
            Map<String, Object> t = Vectors.map(o);
            expected.add(new ConfirmationLifecycle.Transition(
                    Vectors.str(t, "from"), Vectors.str(t, "event"),
                    Vectors.str(t, "by"), Vectors.str(t, "via"), Vectors.str(t, "to")));
        }
        assertEquals(expected, ConfirmationLifecycle.TRANSITIONS, "allowed transitions");
        assertEquals(ConfirmationLifecycle.INITIAL_STATE, ConfirmationLifecycle.TRANSITIONS.get(0).from(),
                "every transition starts at the initial state");
    }

    /**
     * The whole point of v2, asserted rather than left implicit in the table: the wait ending is not
     * the confirmation ending. If this ever regresses to v1's single TTL, a user who leaves the
     * conversation loses the ability to decide their own pending write.
     */
    @Test
    void onlyTheOfflineWindowEndsAPendingConfirmation() {
        ConfirmationLifecycle.Transition wait = event(ConfirmationLifecycle.WAIT_TTL_ELAPSED);
        ConfirmationLifecycle.Transition offline = event(ConfirmationLifecycle.OFFLINE_TTL_ELAPSED);

        assertEquals(ConfirmationLifecycle.PENDING, wait.to(),
                "the in-conversation wait expiring leaves the confirmation decidable");
        assertNull(wait.via(), "a clock is not a place");
        assertEquals(ConfirmationLifecycle.TIMEOUT, offline.to(), "the offline window is what times it out");
    }

    private static ConfirmationLifecycle.Transition event(String name) {
        return ConfirmationLifecycle.TRANSITIONS.stream()
                .filter(t -> name.equals(t.event()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no transition for " + name));
    }

    @Test
    void guardsMatchVector() {
        Map<String, Object> guards = Vectors.map(vector().get("guards"));
        assertEquals(Vectors.str(guards, "resolveByNonOwner"), ConfirmationLifecycle.RESOLVE_BY_NON_OWNER);
        assertEquals(Vectors.str(guards, "resolveUnknownToken"), ConfirmationLifecycle.RESOLVE_UNKNOWN_TOKEN);
        assertEquals(Vectors.str(guards, "decideOutOfBandByNonOwner"),
                ConfirmationLifecycle.DECIDE_OUT_OF_BAND_BY_NON_OWNER);
        assertEquals(Vectors.str(guards, "decideOutOfBandWhenNotPending"),
                ConfirmationLifecycle.DECIDE_OUT_OF_BAND_WHEN_NOT_PENDING);
        assertEquals(Vectors.str(guards, "decideOutOfBandExpired"),
                ConfirmationLifecycle.DECIDE_OUT_OF_BAND_EXPIRED);
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
