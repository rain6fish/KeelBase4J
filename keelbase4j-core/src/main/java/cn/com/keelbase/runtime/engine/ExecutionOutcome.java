// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

/**
 * Outcome of a governed tool call.
 *
 * <p>{@code status} ∈ {@code executed | pending_confirmation | requires_approval | blocked | declined |
 * invalid_arguments | error}. A {@code token} is present only when the call is waiting on a human
 * confirmation. {@code invalid_arguments} means the proposal never got that far: its arguments did not
 * match what the tool declares, so no confirmation row was written and no human was asked.
 *
 * 一次受治理工具调用的结果。
 *
 * <p>{@code status} ∈ {@code executed | pending_confirmation | requires_approval | blocked | declined |
 * invalid_arguments | error}。{@code token} 只在这次调用**在等人确认**时出现。{@code invalid_arguments}
 * 的意思是这份提议**根本没走到那一步**：它的参数与工具声明的不符，所以**没有写下任何确认行、也没有惊动任何人**。
 */
public record ExecutionOutcome(String status, Object data, String token, Long effectId, String error) {

    public static ExecutionOutcome executed(Object data, Long effectId) {
        return new ExecutionOutcome("executed", data, null, effectId, null);
    }
}
