// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.pipeline.ChatReplier;
import cn.com.keelbase.runtime.pipeline.ChatReply;
import cn.com.keelbase.runtime.pipeline.ChatTurn;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The model-backed replier: Spring AI's {@link ChatClient} on one side, the runtime's second seam on
 * the other.
 *
 * <p>Like its sibling {@link SpringAiToolCallPlanner} it is an adapter in the strict sense. It holds
 * no governance state and makes no governance decision: what the engine did is handed to it as a
 * <em>fact to describe</em>, and the system prompt says so outright, because a model that believed it
 * decided whether a write may run would be a model talking about authority it does not have.
 *
 * <p>What it does not do is withhold the governance facts the way the planner withholds risk levels.
 * The difference is who can act on them: a risk level in a prompt is something a planner could be
 * talked into proposing around, whereas the outcome is already settled — the write has been gated, or
 * not, before this class is reached. Describing it accurately is the point.
 */
public final class SpringAiChatReplier implements ChatReplier {

    private static final String INSTRUCTIONS = """
            You are the assistant inside a governed enterprise application.
            Answer the user's message directly and briefly.

            The runtime decides what may actually run — not you. It reports the outcome of each turn,
            and you describe that outcome; you never claim an action succeeded when the runtime says it
            is waiting, blocked, or failed. If a write is waiting for confirmation, say so and make
            clear that nothing has been written yet.
            """;

    private final ChatClient chatClient;
    private final String model;

    public SpringAiChatReplier(ChatClient chatClient, String model) {
        this.chatClient = chatClient;
        this.model = model;
    }

    @Override
    public ChatReply reply(String message, List<ChatTurn> history, ExecutionOutcome outcome) {
        String text = chatClient.prompt()
                .system(INSTRUCTIONS)
                .user(turn(message, history, outcome))
                .call()
                .content();
        return new ChatReply(text == null ? "" : text, "spring-ai", model);
    }

    private String turn(String message, List<ChatTurn> history, ExecutionOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        if (history != null && history.size() > 1) {
            sb.append("Conversation so far:\n");
            // Everything but the last turn: the last one is this message, which is appended below as
            // the actual question rather than repeated as history.
            for (ChatTurn t : history.subList(0, history.size() - 1)) {
                sb.append(t.role()).append(": ").append(t.content()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("User: ").append(message == null ? "" : message).append('\n');
        sb.append("Runtime outcome: ").append(outcome == null
                ? "no tool was proposed for this message"
                : outcome.status()).append('\n');
        return sb.toString();
    }
}
