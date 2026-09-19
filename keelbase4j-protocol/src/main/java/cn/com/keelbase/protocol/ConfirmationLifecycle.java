// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.List;

/**
 * Confirmation lifecycle (R3/R4 writes) — the frozen cross-runtime semantics for
 * "a write waits for a human": state set, decision set, allowed transitions, guards, TTL windows.
 *
 * <p>Source of truth is {@code confirmation-lifecycle-v2-vector.json} (main repo). The reference
 * implementation derives it from {@code confirmation.store.ts}; this class is the Java side of the
 * same contract, so the runtime's confirmation vocabulary has one home instead of string literals.
 *
 * <pre>
 *   pending ──approve(owner, in_band|out_of_band)──→ approved
 *           ──decline(owner, in_band|out_of_band)──→ declined
 *           ──wait_ttl_elapsed(system)────────────→ pending    (the wait ends; the row lives on)
 *           ──offline_ttl_elapsed(system)─────────→ timeout
 * </pre>
 *
 * <p><b>v2 (2026-09-18) split one TTL into two windows, and this class follows it.</b> v1 bound the
 * SSE wait and the row's lifetime to the same 60 seconds, so a confirmation whose wait expired was
 * timed out for good — leaving a conversation made it undecidable, and a row still {@code pending}
 * after a restart could never be resolved at all. v2 separates them: {@link #DEFAULT_TTL_MILLIS}
 * ends the <em>wait</em> without changing the state, and {@link #DEFAULT_OFFLINE_TTL_MILLIS} is what
 * eventually times the row out. No new status was introduced — {@code timeout} is reused — so v2 is
 * a semantic extension rather than a redefinition of the state machine. Consequently v1's single
 * {@code ttl_elapsed} event no longer exists here: it is two events, and only one of them moves the
 * state out of {@code pending}. The v1 text stays in the main repo as protocol history.
 *
 * <p>A decision carries <em>where</em> it was taken ({@link #IN_BAND} / {@link #OUT_OF_BAND}) but
 * that never forks the state machine: both routes take the same {@code approve} / {@code decline}
 * transition. The difference lives in the guards, which is where the out-of-band path earns its
 * stricter treatment.
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

    /** In-conversation wait. Its expiry ends the wait; it does <em>not</em> change the state. */
    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    /**
     * Offline window — how long a still-{@code pending} confirmation stays actionable outside the
     * conversation before it becomes {@link #TIMEOUT}. This is the window v2 introduced.
     */
    public static final long DEFAULT_OFFLINE_TTL_MILLIS = 86_400_000L;

    /** Where a decision was taken. Both routes take the same transition; only the guards differ. */
    public static final String IN_BAND = "in_band";
    public static final String OUT_OF_BAND = "out_of_band";

    /** System events. Only {@link #OFFLINE_TTL_ELAPSED} leaves {@link #PENDING}. */
    public static final String WAIT_TTL_ELAPSED = "wait_ttl_elapsed";
    public static final String OFFLINE_TTL_ELAPSED = "offline_ttl_elapsed";

    // --- guards: a resolve that is not the owner's, or names an unknown token, changes nothing ---
    public static final String RESOLVE_BY_NON_OWNER = "ignored_no_transition";
    public static final String RESOLVE_UNKNOWN_TOKEN = "ignored_no_transition";

    // --- guards on deciding outside the conversation (v2) ---
    /** Only the operator whose row it is may decide it out of band; anyone else changes nothing. */
    public static final String DECIDE_OUT_OF_BAND_BY_NON_OWNER = "rejected_no_transition";
    /** Already resolved (a retry, a race): a no-op, never a second execution of the tool. */
    public static final String DECIDE_OUT_OF_BAND_WHEN_NOT_PENDING = "no_op_idempotent";
    /** Past the offline window: refused, and the row is later swept to {@link #TIMEOUT}. */
    public static final String DECIDE_OUT_OF_BAND_EXPIRED = "rejected_no_transition";

    /** Legacy decision words normalised to {@link #DECLINE} by the server. */
    public static final List<String> DECLINE_ALIASES = List.of("reject");

    /**
     * A transition of the frozen lifecycle: {@code from --event(by, via)--> to}.
     *
     * <p>{@code via} is null for the system events — a clock is not a place.
     */
    public record Transition(String from, String event, String by, String via, String to) {
    }

    public static final List<Transition> TRANSITIONS = List.of(
            new Transition(PENDING, APPROVE, "owner", IN_BAND, APPROVED),
            new Transition(PENDING, DECLINE, "owner", IN_BAND, DECLINED),
            new Transition(PENDING, APPROVE, "owner", OUT_OF_BAND, APPROVED),
            new Transition(PENDING, DECLINE, "owner", OUT_OF_BAND, DECLINED),
            new Transition(PENDING, WAIT_TTL_ELAPSED, "system", null, PENDING),
            new Transition(PENDING, OFFLINE_TTL_ELAPSED, "system", null, TIMEOUT));

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
