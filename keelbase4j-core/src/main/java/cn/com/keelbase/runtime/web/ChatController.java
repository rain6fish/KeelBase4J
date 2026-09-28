// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI entry point, answering in one body. A planner decides what to call, a replier decides what to
 * say, and the engine decides what may run — so an AI request and a direct tool call cross the same
 * boundary.
 *
 * <p><b>The response carries the reference implementation's fields, and more.</b> A conversational
 * client (the mobile app, the mini program) reads {@code conversationId}, {@code reply} and
 * {@code navigateTo}; those are exactly what the reference's {@code ChatResponse} promises, so code
 * written against either runtime works unchanged. What is added is the governance facts —
 * {@code status}, {@code token}, {@code effectId}, {@code data}, {@code error}. They are here rather
 * than dropped because the reference delivers them over SSE, where a confirmation token travels as an
 * event, and this response is the only place a caller of <em>this</em> endpoint can learn that a write
 * is waiting on them. A superset, not a divergence — see ADR-0009 D1.
 *
 * <p>A turn that matches no tool is not an error here any more. A conversational endpoint that
 * answered 400 for "I cannot route that" would be reporting the planner's limits as a failed request;
 * it answers with a reply that says so, which is what the frontends' chat surfaces expect.
 *
 * <p>The streaming sibling is {@link ChatStreamController}; both run the same turn
 * ({@link ChatTurnService}) and differ only in how they report it.
 */
@RestController
public class ChatController {

    private final CurrentPrincipal principals;
    private final ChatTurnService turns;

    public ChatController(CurrentPrincipal principals, ChatTurnService turns) {
        this.principals = principals;
        this.turns = turns;
    }

    @PostMapping("/ai/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Principal principal = principals.current();
        ChatTurnService.Turn turn = turns.run(
                request.message(), request.customerId(), request.conversationId(), principal);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", turn.conversationId());
        body.put("reply", turn.reply().text());
        body.put("provider", turn.reply().provider());
        body.put("model", turn.reply().model());
        // The tool this turn actually used, which the reference also reports. Omitted rather than sent
        // empty when nothing was routed — the field means "these were called".
        if (turn.plan() != null) {
            body.put("toolCalls", List.of(turn.plan().tool()));
        }
        body.put("status", turn.outcome() == null ? null : turn.outcome().status());
        body.put("data", turn.outcome() == null ? null : turn.outcome().data());
        body.put("token", turn.outcome() == null ? null : turn.outcome().token());
        body.put("effectId", turn.outcome() == null ? null : turn.outcome().effectId());
        body.put("error", turn.outcome() == null ? null : turn.outcome().error());
        return body;
    }
}
