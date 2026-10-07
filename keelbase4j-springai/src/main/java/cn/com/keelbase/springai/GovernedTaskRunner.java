// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.TaskRun;
import cn.com.keelbase.runtime.pipeline.TaskRunner;
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
 * <p><b>The run reports what the engine was asked and what it said</b> — every step, in order, naming
 * the tool it was an attempt at, plus the last token still waiting on a person. That is what makes the
 * loop's behaviour inspectable without reading a transcript, and it is how a step that a human must
 * approve is carried back to the caller instead of being left inside the model's context.
 *
 * <p>It implements {@link TaskRunner} and defines none of that vocabulary itself: a run's shape belongs
 * to the seam, and the route that answers with it belongs to the runtime. This class is what runs; the
 * runtime is where a caller asks.
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
 *
 * <p>它实现 {@link TaskRunner}，而**不自己定义那套词汇**：一次运行的形状属于**接缝**，用它的那条路由属于**运行时**。
 * 这个类是**跑什么**，运行时是**在哪儿问**。
 */
public final class GovernedTaskRunner implements TaskRunner {

    private final ChatClient chatClient;
    private final ToolRegistry tools;
    private final GovernedToolCallbacks.GovernedCall call;

    public GovernedTaskRunner(ChatClient chatClient, ToolRegistry tools,
                              GovernedToolCallbacks.GovernedCall call) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.call = call;
    }

    @Override
    public TaskRun run(String message, Principal principal) {
        List<TaskRun.Step> seen = new ArrayList<>();
        GovernedToolCallbacks.GovernedCall recording = (toolName, args, who) -> {
            ExecutionOutcome outcome = call.execute(toolName, args, who);
            seen.add(new TaskRun.Step(toolName, outcome));
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
