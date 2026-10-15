// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationMessageRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.DeterministicReplier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p><b>A trace is the conversation's own story, and now it holds both halves of it.</b> The turns
 * are answered from the conversation: a caller's message is an `input` step and the reply an
 * `assistant` one. The governance half — the tool calls the turns proposed, the decisions a person
 * took, and the effects those writes produced — is answered from the audit and side-effect rows that
 * carry this conversation's id, and the two are merged into one timeline in the order things
 * happened. The id is recorded where the call is made (see {@code V11__conversation_link}), so the
 * half that a reference trace shows is answerable here too.
 *
 * <p><b>What a trace still does not carry.</b> A row written with no conversation — a task run, an
 * MCP caller — is genuinely not this conversation's, so it does not appear, and neither does the
 * call's argument string: this runtime does not store it on the audit row, and inventing one would be
 * a description of a call the row never held. Those absences are the row's own, not a statement about
 * the conversation.
 *
 * <p>`provider` and `model` are answered from the default replier's own constants. This runtime keeps
 * them per reply, not per conversation — the reference stores them on the conversation — so a
 * deployment that replaced the replier should read those two values as this runtime's default rather
 * than as a record of what answered a given turn.
 *
 * <p>**某个调用方自己的会话，以及其中一条的追踪** —— 控制台决策轨迹页读的就是这两条面。
 *
 * <p><b>一条追踪是这条会话自己的故事，而现在它两半都在。</b>回合从**会话自己**答出来：调用方那句是 `input` 步、
 * 回复是 `assistant` 步。治理那一半 —— 这些回合提议过的工具调用、有人作过的裁决、以及那些写产出的副作用 —— 从**带着
 * 这条会话 id** 的审计行与副作用行答出来，两半按**事情发生的先后**并成一条时间线。这个 id 在调用发生的地方就被记下
 * （见 `V11__conversation_link`），所以参照追踪显示的那一半，这里也答得出。
 *
 * <p><b>一条追踪仍然不装的东西。</b>**没有会话**时写下的行 —— 一次任务运行、一个 MCP 调用方 —— 本来就不属于这条会话，
 * 故不出现；也没有调用的参数串：本运行时**不在审计行上存它**，而编一个等于**描述一次那一行从未持有过的调用**。
 * 这些缺席是**行自身**的，不是关于这条会话的陈述。
 *
 * <p>`provider` 与 `model` 取自**默认 replier 自己的常量**。本运行时把它们记在**每次回复**上、不是每条会话上
 * —— 参照实现存在会话上 —— 所以**换过 replier 的部署**应把这两个值读作**本运行时的默认**，而不是某一回合究竟由谁
 * 作答的记录。
 */
@RestController
public class ConversationController {

    private final ConversationMessageRepository messages;
    private final AuditLogRepository audit;
    private final SideEffectRepository effects;
    private final CurrentPrincipal principals;

    public ConversationController(ConversationMessageRepository messages, AuditLogRepository audit,
                                  SideEffectRepository effects, CurrentPrincipal principals) {
        this.messages = messages;
        this.audit = audit;
        this.effects = effects;
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
        body.put("steps", steps(id, turns));
        return body;
    }

    /**
     * The trace as one timeline: the turns, and the governance events those turns produced, merged by
     * when each happened.
     *
     * <p>Merged rather than concatenated because a trace is a story in order — a tool call belongs
     * between the message that proposed it and the reply that reports it, and two lists side by side
     * would lose that. Each source carries its own timestamp, so the merge is an ordering by time; a
     * tie is broken by the order the steps were gathered in, so the result is stable.
     *
     * 把追踪并成**一条时间线**：回合，与那些回合产出的治理事件，按各自发生的先后合并。
     *
     * <p>合并、而不是拼接，因为一条追踪是**按序的故事** —— 一次工具调用落在**提议它的那句**与**报告它的那句回复**
     * 之间，两份并列的列表会丢掉这一点。每个来源自带时间戳，故合并就是按时间排序；并列时按**收集的先后**打破，
     * 于是结果是稳定的。
     */
    private List<Map<String, Object>> steps(String conversationId, List<ConversationMessage> turns) {
        List<Timed> timeline = new ArrayList<>();
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
            timeline.add(new Timed(turn.getCreatedAt(), timeline.size(), step));
        }
        for (AuditLog row : audit.findByConversationIdOrderByIdAsc(conversationId)) {
            Map<String, Object> step = auditStep(row);
            if (step != null) {
                timeline.add(new Timed(row.getCreatedAt(), timeline.size(), step));
            }
        }
        for (SideEffect effect : effects.findByConversationIdOrderByIdAsc(conversationId)) {
            timeline.add(new Timed(effect.getCreatedAt(), timeline.size(), effectStep(effect)));
        }
        timeline.sort(Comparator.comparing(Timed::time).thenComparingInt(Timed::order));
        return timeline.stream().map(Timed::step).toList();
    }

    /**
     * One audit row as a step: a {@code tool_call} for a call, a {@code confirmation} for a decision.
     *
     * <p>A row that is neither — none exists today, but the vocabulary is the store's to widen — is
     * left out rather than shown as a step type it is not. The call's outcome is carried as
     * {@code success} (false is a refusal, a block, a failed execution or a pending one, matching the
     * row's own {@code isError}), and a decision carries the frozen {@code outcome} the row's action
     * maps to.
     *
     * 一行审计作为一步：一次调用是 `tool_call`，一次裁决是 `confirmation`。
     *
     * <p>两者都不是的行 —— 今天不存在，但词表归存储去放宽 —— **略过**，而不是显示成它并非的那个步类型。调用那一行
     * 的结局以 {@code success} 承载（false 即拒绝、拦截、执行失败或尚在待决，与那一行自己的 `isError` 一致），
     * 裁决则承载它那一行映射到的冻结 {@code outcome}。
     */
    private static Map<String, Object> auditStep(AuditLog row) {
        String type;
        if ("tool_call".equals(row.getAction())) {
            type = "tool_call";
        } else if ("tool_confirmation".equals(row.getAction())) {
            type = "confirmation";
        } else {
            return null;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "audit-" + row.getId());
        step.put("type", type);
        step.put("time", row.getCreatedAt());
        if (row.getTool() != null) {
            step.put("toolName", row.getTool());
        }
        step.put("detail", row.getDetail());
        if ("tool_call".equals(type)) {
            step.put("success", !row.isError());
        } else {
            String outcome = confirmationOutcome(row.getOutcome());
            if (outcome != null) {
                step.put("outcome", outcome);
            }
        }
        return step;
    }

    /**
     * The frozen {@code confirmation} step's {@code outcome}, from this runtime's audit outcome.
     *
     * <p>The vocabulary is the object's own (approve / decline / timeout / pending_approval). A retry
     * records {@code attempted}, which is none of those — a decision is not what happened — so it is
     * left off rather than forced into a word that would misdescribe it.
     *
     * 冻结 `confirmation` 步的 {@code outcome}，取自本运行时的审计结局。
     *
     * <p>词表是对象自己的（approve / decline / timeout / pending_approval）。一次重试记的是 {@code attempted}，
     * 它不属于其中任何一个 —— 发生的不是一次裁决 —— 故**不填**，而不是硬塞进一个会misdescribe它的词。
     */
    private static String confirmationOutcome(String auditOutcome) {
        if ("approved".equals(auditOutcome)) {
            return "approve";
        }
        if ("declined".equals(auditOutcome)) {
            return "decline";
        }
        return null;
    }

    /**
     * One effect as a step, in the frozen {@code traceEffect} shape the object declares.
     *
     * <p>{@code before} is the row as it was, for a write that updated rather than created; null for a
     * create, which is the fact rather than an omission. A trace that showed only {@code after} could
     * say what a row became and never what it had been — which is the half an investigation asks about
     * first when a row is not what it was.
     *
     * 一个 effect 作为一个步，按对象声明的冻结 `traceEffect` 形状。
     *
     * <p>{@code before} 是那一行**原先**的样子（给**改行**而非新建的写）；**新建**时为 null，那是**事实**、
     * 不是省略。一条只显示 `after` 的轨迹说得出「这一行变成了什么」，却从不说它**本来**是什么 —— 而当一行不再是
     * 它原来的样子时，调查先问的恰恰是这一半。
     */
    private static Map<String, Object> effectStep(SideEffect effect) {
        Map<String, Object> traceEffect = new LinkedHashMap<>();
        traceEffect.put("effectId", effect.getId());
        traceEffect.put("resultType", effect.getResultType());
        traceEffect.put("resultId", effect.getResultId());
        traceEffect.put("revocable", !"none".equals(effect.getRevokeClass()));
        traceEffect.put("before", effect.getBeforeSnapshot());
        traceEffect.put("after", effect.getAfterSnapshot());
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "effect-" + effect.getId());
        step.put("type", "effect");
        step.put("time", effect.getCreatedAt());
        step.put("effect", traceEffect);
        return step;
    }

    /** A step with the moment it happened and the order it was gathered in, for a stable merge. */
    private record Timed(Instant time, int order, Map<String, Object> step) {
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
