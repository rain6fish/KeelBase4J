// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationStore;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ChatReplier;
import cn.com.keelbase.runtime.pipeline.ChatReply;
import cn.com.keelbase.runtime.pipeline.ChatTurn;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * One turn of a conversation, from the caller's words to what to say back.
 *
 * <p>Extracted because two endpoints run the same turn and differ only in how they report it: the
 * plain one answers with a body, the streaming one reports the steps as they happen. Leaving the
 * sequence in both would mean two places to keep in step, and this repository has already paid for
 * that lesson twice — a second implementation of the same thing drifts (JV-21, JV-22).
 *
 * <p>The sequence itself is the load-bearing part and it does not change between the two: the planner
 * proposes, the engine disposes, and the reply is written afterwards from what the engine actually
 * answered.
 */
@Service
public class ChatTurnService {

    /**
     * What the turn produced, in the order a caller wants to report it.
     *
     * @param plan    what the planner proposed, or {@code null} when nothing was routed
     * @param outcome what the engine did about it, or {@code null} when there was nothing to gate
     */
    public record Turn(String conversationId, IntentPlan plan, ExecutionOutcome outcome, ChatReply reply) {
    }

    private final ToolCallPlanner planner;
    private final ChatReplier replier;
    private final ConversationStore conversations;
    private final GovernedExecutionEngine engine;

    public ChatTurnService(ToolCallPlanner planner, ChatReplier replier,
                           ConversationStore conversations, GovernedExecutionEngine engine) {
        this.planner = planner;
        this.replier = replier;
        this.conversations = conversations;
        this.engine = engine;
    }

    public Turn run(String message, Long customerId, String conversationId, Principal principal) {
        String id = conversations.openFor(conversationId, principal);
        conversations.append(id, principal, ConversationMessage.USER, message);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("customerId", customerId);

        IntentPlan plan = planner.plan(message, context).orElse(null);
        ExecutionOutcome outcome = plan == null
                ? null
                : engine.execute(plan.tool(), plan.args(), principal);

        ChatReply reply = replier.reply(message, turns(id), outcome);
        conversations.append(id, principal, ConversationMessage.ASSISTANT, reply.text());
        return new Turn(id, plan, outcome, reply);
    }

    /**
     * The recent turns, oldest first. The caller's own message is already among them by the time a
     * replier is asked, so a model reads the same conversation the runtime does.
     */
    public List<ChatTurn> turns(String conversationId) {
        return conversations.history(conversationId).stream()
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .toList();
    }
}
