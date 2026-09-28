// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import java.util.List;

/**
 * Where the words come from — the second AI seam, alongside {@link ToolCallPlanner}.
 *
 * <p>{@code ToolCallPlanner} answers "what should be called"; this answers "what should be said".
 * They are separate because a runtime can have one without the other: the rule-based planner routes
 * without a model, and the deterministic replier below describes what happened without one.
 *
 * <p>The runtime binds no provider (ADR-0005, ADR-0009 D2). A model-backed implementation belongs to
 * an adapter outside this module — the Spring AI adapter is one — and the deployment decides whether
 * it is present. What the runtime owns is the seam and a default that works with no model at all.
 */
public interface ChatReplier {

    /**
     * What to say back.
     *
     * @param message the caller's words
     * @param history the conversation so far, oldest first — what a model needs to answer in context
     * @param outcome what the engine did with this turn, or {@code null} when nothing was routed. The
     *                runtime rather than the replier decides what may run, so this is a fact to
     *                describe, never something a replier can influence.
     */
    ChatReply reply(String message, List<ChatTurn> history, ExecutionOutcome outcome);
}
