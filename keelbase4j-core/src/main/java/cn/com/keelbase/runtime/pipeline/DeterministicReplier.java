// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     *
     * <p>It also spells out what the tool answered, and that is load-bearing rather than decorative:
     * the frozen {@code chat-response} has no field for a tool's result — its fields are the
     * conversation turn — so the reply is the only place a result can reach a caller of the
     * non-streaming path. A model would phrase it; this one lists it.
     *
     * 这个回合该说什么。刻意做到平实而具体：它报的是**引擎的答案**，绝不超出它去揣测。
     *
     * <p>它**还把工具答了什么说清楚**，而这是**承重**的、不是装饰：冻结的 {@code chat-response} **没有放
     * 工具结果的地方** —— 它的字段是**对话回合** —— 所以 reply 是结果能到达**非流式**调用方的**唯一**去处。
     * 模型会把它**措辞**出来；这一个把它**列出来**。
     */
    private String describe(ExecutionOutcome outcome) {
        if (outcome == null) {
            return "I could not match that to a tool I can call, so I proposed nothing.";
        }
        String said = switch (outcome.status()) {
            case "pending_confirmation" ->
                    "I proposed a write. It is waiting for your confirmation, and nothing has been "
                            + "written yet.";
            case "executed" -> "Done — it ran, and the side effect is recorded.";
            case "requires_approval" -> "That needs approval before it can run.";
            case "invalid_arguments" -> "I proposed a call whose arguments did not match the tool, so "
                    + "nothing was proposed to you: " + (outcome.error() == null ? "" : outcome.error());
            case "blocked" -> "That was blocked by the risk policy, so nothing ran.";
            case "declined" -> "You declined it, so nothing was written.";
            case "error" -> "It failed: " + (outcome.error() == null ? "no detail given" : outcome.error());
            default -> "The engine answered: " + outcome.status() + ".";
        };
        return said + answered(outcome);
    }

    /**
     * The tool's own answer, listed. Keys are sorted so the same result reads the same way every time
     * — a reply that reordered itself between two calls would look like two different answers.
     *
     * 工具**自己的答案**，逐项列出。键**排序**，故同一个结果每次读起来都一样 —— 一个在两次调用之间自己换了
     * 次序的 reply，看起来就像**两个不同的答案**。
     */
    private static String answered(ExecutionOutcome outcome) {
        if (!(outcome.data() instanceof Map<?, ?> values) || values.isEmpty()) {
            return "";
        }
        String listed = values.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
        return " It answered: " + listed + ".";
    }
}
