// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

/**
 * One message becomes a task the framework may take several governed steps to finish.
 *
 * <p>The single-shot path asks the model for one plan and hands it back. This one hands the model the
 * tools themselves and lets the framework's own calling loop drive — it calls, reads what came back,
 * decides whether another step is warranted, and stops when it has an answer. Nothing here sequences
 * the steps; that is the primitive being reused rather than rebuilt.
 *
 * <p><b>Every step still arrives at the engine</b>, because the callbacks the loop invokes have no
 * other path: each one is a front door to {@code GovernedCall}, and a call that the gate blocks, or
 * that is waiting on a person, is reported as that rather than executed anyway.
 *
 * <p><b>The run reports what the engine was asked and what it said</b> — every step, in order, naming the
 * tool it was an attempt at, plus the last token still waiting on a person. That is what makes the
 * loop's behaviour inspectable without reading a transcript, and it is how a step that a human must
 * approve is carried back to the caller instead of being left inside the model's context.
 *
 * <p>一条消息变成一个**框架可以走若干受治理步骤**去完成的任务。
 *
 * <p>单发路径向模型要一个计划、然后交回；这一条把**工具本身**交给模型，让框架自己的调用循环来驱动——它调一次、
 * 读回结果、决定要不要再来一步、拿到答案就停。**这里没有任何东西在排步骤**；那正是被复用而不是被重建的原语。
 *
 * <p>**每一步仍然到达引擎**：循环调的那些回调**没有别的路**——每个都是 {@code GovernedCall} 的前门；被闸拦下的、
 * 或在等某个人的调用，都会被**如实报告**，而不是照样执行。
 *
 * <p>**这次运行会汇报「引擎被问了什么、它说了什么」**——每一步按序，并带上它是**冲着哪个工具**去的，外加最后
 * 一个仍在等人的 token。这让循环的行为**不必读转写就能检查**，也让「需要人点头的那一步」被**带回调用方**，而不是
 * 留在模型的上下文里。
 */
public final class GovernedTaskRunner {

    private final ChatClient chatClient;
    private final ToolRegistry tools;
    private final GovernedToolCallbacks.GovernedCall call;

    public GovernedTaskRunner(ChatClient chatClient, ToolRegistry tools,
                              GovernedToolCallbacks.GovernedCall call) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.call = call;
    }

    /**
     * One step the loop took: the tool it asked for, and what the engine said about that call.
     *
     * <p>The tool's name is carried rather than inferred from the outcome, because an outcome does not
     * know which tool produced it — and a run that reports three results without saying what they were
     * three attempts at is not a report anyone can check against an audit chain.
     *
     * <p>循环走过的一步：它要的那个工具，以及引擎对那次调用说了什么。
     *
     * <p>工具的**名字**是被带上的、不是从结果里推的——结果并不知道自己是哪个工具产生的；而一次运行若只报
     * 三个结果、不说它们是三次**什么的**尝试，那就不是一份能和审计链对得上的报告。
     */
    public record Step(String tool, ExecutionOutcome outcome) {
    }

    /**
     * What a run did: the model's answer, every governed call the loop made in order, and the token of
     * the last call still waiting on a person — {@code null} when nothing is waiting.
     *
     * <p>这次运行做了什么：模型的答复、循环按序做的每一次受治理调用、以及最后一个仍在等人的调用的
     * token —— 没有人在等时为 {@code null}。
     */
    public record TaskRun(String answer, List<Step> calls, String pendingToken) {

        public TaskRun {
            calls = List.copyOf(calls);
        }
    }

    public TaskRun run(String message, Principal principal) {
        List<Step> seen = new ArrayList<>();
        GovernedToolCallbacks.GovernedCall recording = (toolName, args, who) -> {
            ExecutionOutcome outcome = call.execute(toolName, args, who);
            seen.add(new Step(toolName, outcome));
            return outcome;
        };

        String answer = chatClient.prompt()
                .user(message)
                .toolCallbacks(GovernedToolCallbacks.forCaller(tools, recording, principal))
                .call()
                .content();

        String pending = seen.stream()
                .map(step -> step.outcome().token())
                .filter(Objects::nonNull)
                .reduce((earlier, later) -> later)
                .orElse(null);

        return new TaskRun(answer, seen, pending);
    }
}
