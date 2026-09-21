// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

/**
 * One turn handed to a {@link ChatReplier} — a role and what was said, and nothing else.
 *
 * <p>A separate type rather than the stored entity: what a replier needs is the conversation, not a
 * handle on the runtime's persistence.
 *
 * @param role {@code user} or {@code assistant}
 */
public record ChatTurn(String role, String content) {
}
