// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import java.util.List;

/**
 * The default replier: it describes what the engine did, without a model.
 *
 * <p>It exists for the same reason {@link RuleBasedPlanner} does — the runtime is demonstrable
 * without a provider — and it is held to one rule that a fallback is especially prone to breaking:
 * <b>it must not read as though a model wrote it.</b> Every sentence here is derived from an outcome
 * the engine actually produced, and {@link #PROVIDER} says outright that no model was involved. A
 * deployment that wants words from a model replaces this bean; a deployment that does not gets
 * something true and plainly mechanical, which is the honest alternative to an invented answer.
 */
public class DeterministicReplier implements ChatReplier {

    /** Named as what it is. A caller can tell this apart from a model's answer without guessing. */
    public static final String PROVIDER = "deterministic";

    public static final String MODEL = "none";

    @Override
    public ChatReply reply(String message, List<ChatTurn> history, ExecutionOutcome outcome) {
        return new ChatReply(describe(outcome), PROVIDER, MODEL);
    }

    /**
     * What to say about this turn. Deliberately flat and specific: it reports the engine's answer and
     * never speculates past it.
     */
    private String describe(ExecutionOutcome outcome) {
        if (outcome == null) {
            return "I could not match that to a tool I can call, so I proposed nothing.";
        }
        return switch (outcome.status()) {
            case "pending_confirmation" ->
                    "I proposed a write. It is waiting for your confirmation, and nothing has been "
                            + "written yet.";
            case "executed" -> "Done — it ran, and the side effect is recorded.";
            case "requires_approval" -> "That needs approval before it can run.";
            case "blocked" -> "That was blocked by the risk policy, so nothing ran.";
            case "declined" -> "You declined it, so nothing was written.";
            case "error" -> "It failed: " + (outcome.error() == null ? "no detail given" : outcome.error());
            default -> "The engine answered: " + outcome.status() + ".";
        };
    }
}
