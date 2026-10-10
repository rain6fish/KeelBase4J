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
 * <p><b>The answer is the frozen {@code chat-response} and nothing more.</b> It used to carry the
 * governance facts as well — {@code status}, {@code data}, {@code token}, {@code effectId},
 * {@code error} — on the argument that this endpoint is the only place a caller of it could learn that
 * a write was waiting. That argument was true and the answer was still wrong: the frozen object
 * declares {@code additionalProperties: false}, the reference puts none of those fields on this path,
 * and the protocol's own prose says a non-streaming call does not return a confirmation token. So the
 * facts moved rather than vanished — a pending write is read from {@code /ai/my/confirmations}, whose
 * items carry the token, and the streaming sibling reports the same turn as events. What reaches a
 * caller here is what the object promises, which is also what a client written against the reference
 * gets.
 *
 * <p>A turn that matches no tool is not an error here any more. A conversational endpoint that
 * answered 400 for "I cannot route that" would be reporting the planner's limits as a failed request;
 * it answers with a reply that says so, which is what the frontends' chat surfaces expect.
 *
 * <p>The streaming sibling is {@link ChatStreamController}; both run the same turn
 * ({@link ChatTurnService}) and differ only in how they report it.
 *
 * <p>答的就是冻结的 {@code chat-response}，别无其它。它过去连治理事实一起回 —— {@code status}、
 * {@code data}、{@code token}、{@code effectId}、{@code error} —— 理由是**只有这条端点**能让它的调用方
 * 知道有一次写正在等人。那个理由**是真的**，而这个答案**仍然是错的**：冻结对象写着
 * {@code additionalProperties: false}，参照实现在这条路径上**一个都不放**，而协议自己的散文写着
 * **非流式调用不返回确认 token**。所以那些事实是**搬家**、不是消失 —— 待确认的写从
 * {@code /ai/my/confirmations} 读（它的项**带着 token**），同一个回合在流式那条上以事件报出。到调用方
 * 手里的，就是这个对象承诺的东西 —— 也正是照着参照实现写的客户端会拿到的。
 *
 * <p>匹配不到工具的一个回合**在这里不再是错误**。一条对话式端点若为「我路由不了」答 400，就是把**规划器的
 * 极限**报成一次**失败的请求**；它用一个说明这一点的 reply 作答，而那正是两个前端的聊天面所期望的。
 *
 * <p>流式的兄弟是 {@link ChatStreamController}；两者跑**同一个回合**（{@link ChatTurnService}），
 * 只在**怎么报**上不同。
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
        return body;
    }
}
