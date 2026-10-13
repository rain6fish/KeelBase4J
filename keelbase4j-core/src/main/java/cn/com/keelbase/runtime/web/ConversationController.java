// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationMessageRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.DeterministicReplier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A caller's own conversations, and the trace of one of them — the two faces the console's decision
 * trace page reads.
 *
 * <p><b>What a trace can hold here, and what it cannot.</b> The turns are answered from the
 * conversation itself: a caller's message is an `input` step and the reply an `assistant` one. The
 * other half of a reference trace — the tool calls, the confirmation decisions and their side effects —
 * is <em>not</em> answerable, and not because this deployment chose to leave it out: this runtime's
 * audit rows and side-effect rows carry **no conversation id**, so nothing links a call to the
 * conversation that proposed it. The steps are therefore what the conversation itself knows, and the
 * fact that the rest is unlinked is stated rather than dressed up as an empty list a reader would take
 * for "nothing happened".
 *
 * <p>`provider` and `model` are answered from the default replier's own constants. This runtime keeps
 * them per reply, not per conversation — the reference stores them on the conversation — so a
 * deployment that replaced the replier should read those two values as this runtime's default rather
 * than as a record of what answered a given turn.
 *
 * <p>**某个调用方自己的会话，以及其中一条的追踪** —— 控制台决策轨迹页读的就是这两条面。
 *
 * <p><b>这里一条追踪装得下什么、装不下什么。</b>回合是从**会话自己**答出来的：调用方那句是 `input` 步、回复是
 * `assistant` 步。参照那条追踪的**另一半** —— 工具调用、确认裁决与它们的副作用 —— **答不出来**，而原因不是这个
 * 部署选择不做：本运行时的**审计行与副作用行都不带会话 id**，于是**没有任何东西**把一次调用拴回提议它的那条会话。
 * 所以步骤只有会话自己知道的那部分；而「其余没有链」这件事**如实说出来**，不粉饰成一个会被读成「什么都没发生」的
 * 空列表。
 *
 * <p>`provider` 与 `model` 取自**默认 replier 自己的常量**。本运行时把它们记在**每次回复**上、不是每条会话上
 * —— 参照实现存在会话上 —— 所以**换过 replier 的部署**应把这两个值读作**本运行时的默认**，而不是某一回合究竟由谁
 * 作答的记录。
 */
@RestController
public class ConversationController {

    private final ConversationMessageRepository messages;
    private final CurrentPrincipal principals;

    public ConversationController(ConversationMessageRepository messages, CurrentPrincipal principals) {
        this.messages = messages;
        this.principals = principals;
    }

    @GetMapping("/ai/conversations")
    public List<Map<String, Object>> conversations() {
        Principal caller = principals.current();
        List<Conversation> found = new ArrayList<>();
        for (String id : messages.conversationIdsFor(caller.userId())) {
            found.add(new Conversation(id, messages.findTop2ByConversationIdOrderByIdAsc(id)));
        }
        // Most recent activity first, the order the console lists them in. Sorted here because this
        // runtime derives conversations from their messages, so there is no row to order by.
        //
        // 最近有活动的在前 —— 控制台列表就是这个序。排序放在这里，是因为本运行时的会话是**由消息推出来的**，
        // 没有一行可供排序。
        found.sort((a, b) -> b.lastActivity().compareTo(a.lastActivity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conversation conversation : found) {
            out.add(conversation.data());
        }
        return out;
    }

    @GetMapping("/ai/conversations/{id}/trace")
    public Map<String, Object> trace(@PathVariable String id) {
        Principal caller = principals.current();
        List<ConversationMessage> turns = messages.findByConversationIdOrderByIdAsc(id);
        if (turns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown conversation: " + id);
        }
        if (!turns.get(0).getUserId().equals(caller.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "conversation belongs to another user");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation", new Conversation(id, turns).data());
        body.put("steps", steps(turns));
        return body;
    }

    /**
     * The turns as steps. A caller's message is an input and a reply is an assistant turn; an empty
     * reply is skipped, the way the reference skips one, because there is no content to show.
     */
    private static List<Map<String, Object>> steps(List<ConversationMessage> turns) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            ConversationMessage turn = turns.get(i);
            String type;
            if (ConversationMessage.USER.equals(turn.getRole())) {
                type = "input";
            } else if (ConversationMessage.ASSISTANT.equals(turn.getRole())
                    && turn.getContent() != null && !turn.getContent().isBlank()) {
                type = "assistant";
            } else {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", "msg-" + i);
            step.put("type", type);
            step.put("time", turn.getCreatedAt());
            step.put("content", turn.getContent());
            steps.add(step);
        }
        // The one step that is not a turn, and it is a statement about this deployment rather than an
        // event in the conversation: the governance half of a reference trace has nothing to link it
        // here, and an empty list would be read as "nothing happened" rather than as "nothing is
        // recorded". A notice says which one it is, and a test holds it there so it cannot go quiet.
        //
        // 唯一不是回合的一步，而它说的是**这个部署**、不是会话里的一件事：参照追踪的治理那一半在本部署**没有东西
        // 可拴**，而一个空列表会被读成「什么都没发生」、不是「什么都没记」。一条 notice 说清是哪一种，并有测试
        // 把它钉住、不许它悄声消失。
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("id", "notice-unlinked-governance");
        notice.put("type", "notice");
        notice.put("time", turns.get(turns.size() - 1).getCreatedAt());
        notice.put("content",
                "this deployment records no conversation link on its audit and side-effect rows, so a "
                        + "tool call, a confirmation decision or an effect cannot be placed in this "
                        + "conversation's trace");
        steps.add(notice);
        return steps;
    }

    /** A conversation and the turns that were looked up for it. */
    private record Conversation(String id, List<ConversationMessage> turns) {

        Instant lastActivity() {
            return turns.get(turns.size() - 1).getCreatedAt();
        }

        Map<String, Object> data() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("userId", turns.get(0).getUserId());
            data.put("provider", DeterministicReplier.PROVIDER);
            data.put("model", DeterministicReplier.MODEL);
            data.put("summary", null);
            data.put("createdAt", turns.get(0).getCreatedAt());
            data.put("lastActivityAt", lastActivity());
            List<Map<String, Object>> shown = new ArrayList<>();
            for (ConversationMessage turn : turns) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", turn.getRole());
                message.put("content", turn.getContent());
                message.put("timestamp", turn.getCreatedAt());
                shown.add(message);
            }
            data.put("messages", shown);
            return data;
        }
    }
}
