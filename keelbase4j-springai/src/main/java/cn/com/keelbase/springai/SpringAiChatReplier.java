// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.app.ApplicationProfileProperties;
import cn.com.keelbase.runtime.app.ApplicationProfileProperties.BusinessModule;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.pipeline.ChatReplier;
import cn.com.keelbase.runtime.pipeline.ChatReply;
import cn.com.keelbase.runtime.pipeline.ChatTurn;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 *
 * <p>What it does carry beyond the turn is what this deployment <em>is</em> —
 * {@link #applicationFacts} — because "what modules does this system have" is a question only the
 * system can answer, and a model left to guess will guess. That widens what the model knows and says
 * nothing about what it may do: the block names modules, feature flags and tools, and never a risk
 * level, a confirmation requirement or a revoke class.
 *
 * <p>模型驱动的 replier：一侧是 Spring AI 的 {@link ChatClient}，另一侧是运行时的第二条缝。
 *
 * <p>与它的兄弟 {@link SpringAiToolCallPlanner} 一样，它是**严格意义上的适配器**。它不持有任何治理状态、
 * 也不做任何治理决定：引擎做了什么，是作为**一件待描述的事实**交给它的，而 system prompt 把这一点直说
 * ——因为一个**以为**自己决定了「一次写能不能跑」的模型，就是在谈它**没有的**权柄。
 *
 * <p>它并没有像 planner 压住风险级那样压住治理事实。差别在于**谁能拿它做事**：prompt 里的一个风险级，是
 * planner **可能被说动**去绕着提议的东西；而结局**早就定了**——在这次调用到达本类**之前**，那一次写要么已被
 * 拦下、要么没有。**如实描述结局**才是它的职责所在。
 *
 * <p>除本回合之外它还带着**这个部署是什么**（{@link #applicationFacts}）——因为「这个系统有哪些模块」是
 * **只有系统自己**答得上来的问题，而放任不管的模型**会去猜**。这放宽的是模型**知道什么**，与它**能做什么**
 * 无关：那一块只写模块、特性开关与工具，从不写风险级、是否需确认、撤销档。
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

    /**
     * The heading the {@link #applicationFacts} block arrives under, and the one instruction that makes
     * it worth carrying: answer from it, and do not add to it.
     *
     * <p>The instruction is stated rather than left to judgement because the failure it prevents is
     * not one a model reasons its way out of: asked what a system offers, a model that was never given
     * the list produces a plausible one.
     *
     * {@link #applicationFacts} 那一块到达时用的抬头，以及让这一块**值得带**的那一条指令：**据它作答、
     * 不要往里加**。
     *
     * <p>指令是要**写出来**的、不是留给判断的：它防的那种失败**不是**模型能推出来的 —— 被问「一个系统提供
     * 什么」时，**从没拿到清单**的模型会**编出一份像样的**。
     */
    private static final String FACTS_HEADING = """
            What this deployment is (answer from this; do not name a module, feature or tool that is
            not listed):
            """;

    private final ChatClient chatClient;
    private final String model;
    private final String system;

    public SpringAiChatReplier(ChatClient chatClient, String model, String applicationFacts) {
        this.chatClient = chatClient;
        this.model = model;
        // Assembled once, here: the profile and the tool set are fixed at startup, so rebuilding this
        // string on every turn is work whose answer cannot differ.
        //
        // 只在这里拼一次：档案与工具集在启动时就定了，于是每一轮重建这个串是**答案不可能不同**的工作。
        this.system = applicationFacts == null || applicationFacts.isBlank()
                ? INSTRUCTIONS
                : INSTRUCTIONS + "\n" + applicationFacts;
    }

    @Override
    public ChatReply reply(String message, List<ChatTurn> history, ExecutionOutcome outcome) {
        String text = chatClient.prompt()
                .system(system)
                .user(turn(message, history, outcome))
                .call()
                .content();
        return new ChatReply(text == null ? "" : text, "spring-ai", model);
    }

    /**
     * What this deployment is, as prompt text — every fact a question about the system itself has to
     * be answered from.
     *
     * <p>Same reason the sibling adapter shows the model the tool catalogue: a model cannot answer
     * "what does this system offer" without being told, and the alternative to telling it is a model
     * that invents a module. It is assembled from what the deployment already declares — the profile
     * ({@code keelbase.app.*}) and the tools it registered — so a host widens this block by declaring
     * more, never by editing the adapter.
     *
     * <p>Package-private and static so the text can be pinned without standing up a context, the same
     * reason {@code modelName} is. Blank in, blank out: an empty heading in a prompt is worse than no
     * heading at all.
     *
     * <p>这个部署**是什么**，作为 prompt 文本 —— 一个关于系统自身的问题，能拿来作答的**每一条**事实。
     *
     * <p>理由与它的兄弟适配器把工具目录给模型看**是同一条**：不说，「这个系统提供什么」模型就答不上来；而
     * 不说的另一条路，是一个**凭空造出一个模块**的模型。它由部署**已经声明过**的东西拼成 —— 档案
     * （{@code keelbase.app.*}）与它注册的工具 —— 所以宿主**加声明**即拓宽这一块，**不必改适配器**。
     *
     * <p>包内可见且静态，好让这段文本**不必起一个上下文**就能钉住，与 {@code modelName} 同一个理由。
     * 空进空出：prompt 里一个**空的抬头**比**没有抬头**更坏。
     */
    static String applicationFacts(ApplicationProfileProperties profile, ToolRegistry tools) {
        StringBuilder sb = new StringBuilder();
        if (profile != null) {
            sb.append("- preset: ").append(profile.getPreset()).append('\n');
            List<String> enabled = profile.getFeatures().entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            if (!enabled.isEmpty()) {
                sb.append("- features on: ").append(String.join(", ", enabled)).append('\n');
            }
            if (!profile.getBusinessModules().isEmpty()) {
                sb.append("- modules: ").append(profile.getBusinessModules().stream()
                        .map(SpringAiChatReplier::describe)
                        .collect(Collectors.joining("; "))).append('\n');
            }
        }
        if (tools != null && !tools.all().isEmpty()) {
            sb.append("- tools: ").append(tools.all().stream()
                    .map(tool -> tool.name() + " — " + tool.description())
                    .collect(Collectors.joining("; "))).append('\n');
        }
        return sb.isEmpty() ? "" : FACTS_HEADING + sb;
    }

    private static String describe(BusinessModule module) {
        return module.getId() + " (" + module.getLabel() + " — " + module.getDescription() + ")";
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
