// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationStore;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.ChatReplier;
import cn.com.keelbase.runtime.pipeline.ChatReply;
import cn.com.keelbase.runtime.pipeline.ChatTurn;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI entry point. A planner decides what to call, a replier decides what to say, and the engine
 * decides what may run — so an AI request and a direct tool call cross the same boundary.
 *
 * <p><b>The response carries the reference implementation's fields, and more.</b> A conversational
 * client (the mobile app, the mini program) reads {@code conversationId}, {@code reply} and
 * {@code navigateTo}; those are exactly what the reference's {@code ChatResponse} promises, so code
 * written against either runtime works unchanged. What is added is the governance facts —
 * {@code status}, {@code token}, {@code effectId}, {@code data}, {@code error}. They are here rather
 * than dropped because the reference delivers them over SSE, where a confirmation token travels as an
 * event, and this runtime has no SSE: this response is the only place a caller can learn that a write
 * is waiting on them. A superset, not a divergence — see ADR-0009 D1.
 *
 * <p>A turn that matches no tool is not an error here any more. A conversational endpoint that
 * answered 400 for "I cannot route that" would be reporting the planner's limits as a failed request;
 * it answers with a reply that says so, which is what the frontends' chat surfaces expect.
 */
@RestController
public class ChatController {

    private final CurrentPrincipal principals;
    private final ToolCallPlanner planner;
    private final ChatReplier replier;
    private final ConversationStore conversations;
    private final GovernedExecutionEngine engine;

    public ChatController(CurrentPrincipal principals, ToolCallPlanner planner, ChatReplier replier,
                          ConversationStore conversations, GovernedExecutionEngine engine) {
        this.principals = principals;
        this.planner = planner;
        this.replier = replier;
        this.conversations = conversations;
        this.engine = engine;
    }

    @PostMapping("/ai/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Principal principal = principals.current();
        String conversationId = conversations.openFor(request.conversationId(), principal);
        conversations.append(conversationId, principal, ConversationMessage.USER, request.message());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("customerId", request.customerId());

        // A planner proposes; the engine disposes. Nothing about the reply can change that, because
        // the reply is written after the engine has already answered.
        ExecutionOutcome outcome = planner.plan(request.message(), context)
                .map(plan -> engine.execute(plan.tool(), plan.args(), principal))
                .orElse(null);

        ChatReply reply = replier.reply(request.message(), turns(conversationId), outcome);
        conversations.append(conversationId, principal, ConversationMessage.ASSISTANT, reply.text());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("reply", reply.text());
        body.put("provider", reply.provider());
        body.put("model", reply.model());
        body.put("status", outcome == null ? null : outcome.status());
        body.put("data", outcome == null ? null : outcome.data());
        body.put("token", outcome == null ? null : outcome.token());
        body.put("effectId", outcome == null ? null : outcome.effectId());
        body.put("error", outcome == null ? null : outcome.error());
        return body;
    }

    private List<ChatTurn> turns(String conversationId) {
        return conversations.history(conversationId).stream()
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .toList();
    }
}
