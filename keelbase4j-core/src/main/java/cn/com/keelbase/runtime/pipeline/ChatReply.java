// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

/**
 * What a {@link ChatReplier} decided to say, and who said it.
 *
 * <p>{@code provider} and {@code model} are not decoration: a caller has to be able to tell a
 * deterministic fallback from a model's answer, and a runtime that left them blank would make the two
 * indistinguishable on the wire. {@link DeterministicReplier} names itself.
 */
public record ChatReply(String text, String provider, String model) {

    public ChatReply {
        if (text == null) {
            throw new IllegalArgumentException("a reply has text");
        }
    }
}
