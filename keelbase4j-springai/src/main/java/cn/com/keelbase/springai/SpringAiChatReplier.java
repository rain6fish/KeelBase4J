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
        // What the call returned, not only that it ran. A model asked to describe an outcome it cannot
        // see will refuse to invent one — the right instinct and a useless answer, and it was measured:
        // the tool answered with three customers and the reply described the result as empty. The data
        // is safe to hand over because it is the tool's own projection — a tool decides what it gives
        // the AI, which ListSysUsersTool's javadoc makes the security-relevant part — so what arrives
        // here is already a shape somebody chose to disclose, never an entity and never a risk level.
        //
        // 写的是**这一调用返回了什么**，而不只是「它跑了」。被要求描述一个看不见的结果的模型**会拒绝编造**
        // ——那是**对的直觉**、也是**没用的答案**——而这是实测的：工具答了三个客户，回复把结果描述成空的。
        // 这笔数据**可以交出去**，因为它是**工具自己的投影**——工具自己决定交给 AI 什么，`ListSysUsersTool`
        // 的 javadoc 正是把「投影」定为与安全相关的那部分——所以到这里的东西**已经是有人选择披露过的形状**，
        // 从来不是实体、也不是风险级。
        if (outcome != null && outcome.data() != null) {
            // `String.valueOf` rather than a serialiser: this module deliberately carries no JSON
            // library, and a tool's projection is a list of small maps, which is already readable as
            // `{id=2, name=…}`. Adding a dependency to the adapter to change punctuation would be the
            // wrong trade.
            //
            // 用 `String.valueOf` 而不是序列化器：本模块刻意不带 JSON 库，而一个工具的投影是**一列小 map**，
            // 印成 `{id=2, name=…}` 本来就够读。为了改几个标点就往适配器上加依赖，是本末倒置。
            sb.append("What the call returned: ").append(String.valueOf(outcome.data())).append('\n');
        }
        if (outcome != null && outcome.error() != null) {
            sb.append("The call reported: ").append(outcome.error()).append('\n');
        }
        return sb.toString();
    }
}
