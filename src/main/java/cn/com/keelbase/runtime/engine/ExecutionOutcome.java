// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

/**
 * Outcome of a governed tool call.
 *
 * <p>{@code status} ∈ {@code executed | pending_confirmation | requires_approval | blocked | declined | error}.
 * A {@code token} is present only when the call is waiting on a human confirmation.
 */
public record ExecutionOutcome(String status, Object data, String token, Long effectId, String error) {

    public static ExecutionOutcome executed(Object data, Long effectId) {
        return new ExecutionOutcome("executed", data, null, effectId, null);
    }
}
