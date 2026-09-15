// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.List;

/**
 * Confirmation lifecycle (R3/R4 writes) — the frozen cross-runtime semantics for
 * "a write waits for a human": state set, decision set, allowed transitions, guards, default TTL.
 *
 * <p>Source of truth is {@code confirmation-lifecycle-v1-vector.json} (main repo). The reference
 * implementation derives it from {@code confirmation.store.ts}; this class is the Java side of the
 * same contract, so the runtime's confirmation vocabulary has one home instead of string literals.
 *
 * <pre>
 *   pending ──approve(owner)──→ approved
 *           ──decline(owner)──→ declined
 *           ──ttl_elapsed(sys)─→ timeout
 * </pre>
 */
public final class ConfirmationLifecycle {

    // --- states (the ai_confirmation_requests.status column domain) ---
    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String DECLINED = "declined";
    public static final String TIMEOUT = "timeout";

    // --- decision outcomes (what the operator/ SSE confirmation_decision carries) ---
    public static final String APPROVE = "approve";
    public static final String DECLINE = "decline";

    public static final List<String> STATUSES = List.of(PENDING, APPROVED, DECLINED, TIMEOUT);
    public static final List<String> OUTCOMES = List.of(APPROVE, DECLINE, TIMEOUT);

    public static final String INITIAL_STATE = PENDING;
    public static final List<String> TERMINAL_STATES = List.of(APPROVED, DECLINED, TIMEOUT);

    /** Default confirmation TTL (HS-6 may override it per deployment). */
    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    // --- guards: a resolve that is not the owner's, or names an unknown token, changes nothing ---
    public static final String RESOLVE_BY_NON_OWNER = "ignored_no_transition";
    public static final String RESOLVE_UNKNOWN_TOKEN = "ignored_no_transition";

    /** Legacy decision words normalised to {@link #DECLINE} by the server. */
    public static final List<String> DECLINE_ALIASES = List.of("reject");

    /** A transition of the frozen lifecycle: {@code from --event(by)--> to}. */
    public record Transition(String from, String event, String by, String to) {
    }

    public static final List<Transition> TRANSITIONS = List.of(
            new Transition(PENDING, APPROVE, "owner", APPROVED),
            new Transition(PENDING, DECLINE, "owner", DECLINED),
            new Transition(PENDING, "ttl_elapsed", "system", TIMEOUT));

    private ConfirmationLifecycle() {
    }

    public static boolean isTerminal(String status) {
        return TERMINAL_STATES.contains(status);
    }

    /**
     * Normalise a client decision word to the closed decision set (approve | decline); the legacy
     * {@code reject} is the server's alias for {@code decline}. Anything else is not a decision.
     */
    public static String normalizeDecision(String decision) {
        if (APPROVE.equals(decision)) {
            return APPROVE;
        }
        if (DECLINE.equals(decision) || DECLINE_ALIASES.contains(decision)) {
            return DECLINE;
        }
        throw new IllegalArgumentException("decision must be approve or decline");
    }
}
