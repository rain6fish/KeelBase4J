// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

/**
 * What an out-of-band decision did, in the shape the operator's own console reads (ADR-0015).
 *
 * <p>{@code ok} answers "is the decision in effect"; it is {@code false} for a decision that changed
 * nothing — an unknown token, somebody else's, one already decided, or one past the offline window —
 * and {@code message} says which, because a caller that only saw "not ok" could not tell a retry apart
 * from a refusal. {@code success} is whether the approved write ran, and {@code resultId} is the record
 * it produced.
 *
 * <p>This is <b>not a frozen wire object</b>: the registry carries the in-conversation decision body
 * ({@code confirm-decision-body}) and not this response. It matches the console that consumes it, and
 * the gap is recorded rather than papered over.
 */
public record OutOfBandDecision(boolean ok, Boolean success, Long resultId, String message) {
}
