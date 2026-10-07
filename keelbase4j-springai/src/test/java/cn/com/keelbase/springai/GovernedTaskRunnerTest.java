// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.TaskRun;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * The loop itself, driven deterministically: the framework calls a tool, that call reaches the engine,
 * and what the engine said comes back in the run — in order, with the tool it was an attempt at.
 *
 * <p>This is the half that a script against a real model could only demonstrate one run at a time. Two
 * things have to be true of the stub for it to measure the right thing, and both were established by
 * measurement rather than reasoning (RY-25's acceptance ③): the client must carry a tool-calling
 * advisor, or the callbacks never reach the model at all, and the stub must advertise tool calling, or
 * the tools are stripped off the request before it arrives.
 *
 * <p>The engine is a stand-in here — the question is whether the framework's call arrives at it and
 * whether what it answered is reported back, not what the gate decides.
 *
 * <p>**循环本身**，被确定性地驱动：框架调用一个工具、那次调用到达引擎、而引擎说的话**按序**回到运行报告里，
 * 并带着它是**冲着哪个工具**去的。
 *
 * <p>这正是「对着真模型跑脚本」每次只能演示一次的那一半。这个 stub 要成立有两个条件，而两条都是**量出来的**、
 * 不是推出来的（RY-25 验收 ③）：client 必须带 tool-calling advisor，否则回调**根本到不了模型**；stub 必须**声明**
 * 支持 tool calling，否则工具会在请求到达之前被**摘掉**。
 *
 * <p>引擎在这里是个替身 —— 要问的是「框架的那次调用到不到得了它、它答的话有没有被报回去」，不是闸怎么判。
 */
class GovernedTaskRunnerTest {

    /** What the engine was asked, in the order it was asked — the order the run has to report. */
    private final List<String> askedTools = new ArrayList<>();
    private final List<Map<String, Object>> askedArgs = new ArrayList<>();

    @Test
    void theFrameworksCallReachesTheEngineAndComesBackInTheRun() {
        GovernedTaskRunner runner = runnerWhoseEngineSays(
                ExecutionOutcome.executed(Map.of("level", "high"), null));

        TaskRun run = runner.run("查一下这个客户的风险", new Principal("7", "user"));

        assertEquals(List.of("analyze_customer_risk"), askedTools,
                "the loop's call reached the engine, once");
        assertEquals(7, ((Number) askedArgs.get(0).get("customerId")).intValue(),
                "carrying the model's own arguments — read as a number rather than compared as text, "
                        + "because a JSON number arrives here as a Double (this codebase's parsing, "
                        + "not this seam's): " + askedArgs.get(0));
        assertEquals(1, run.steps().size(), "and the run reports that step: " + run.steps());
        assertEquals("analyze_customer_risk", run.steps().get(0).tool(),
                "naming the tool it was an attempt at");
        assertEquals("executed", run.steps().get(0).outcome().status(), "with what the engine said");
        assertEquals("done", run.answer(), "the model's second turn is the answer");
        assertNull(run.pendingToken(), "and nothing is waiting on a person");
    }

    @Test
    void aStepWaitingOnAPersonComesBackWithItsToken() {
        GovernedTaskRunner runner = runnerWhoseEngineSays(
                new ExecutionOutcome("pending_confirmation", null, "token-1", null, null));

        TaskRun run = runner.run("给客户建一条跟进记录", new Principal("7", "user"));

        assertEquals("pending_confirmation", run.steps().get(0).outcome().status());
        assertEquals("token-1", run.pendingToken(),
                "the handle to go on with belongs to the caller, and the run hands it back");
    }

    private GovernedTaskRunner runnerWhoseEngineSays(ExecutionOutcome said) {
        return new GovernedTaskRunner(
                ChatClient.builder(aModelThatCallsOnce("analyze_customer_risk"))
                        .defaultAdvisors(ToolCallingAdvisor.builder().build())
                        .build(),
                new ToolRegistry(List.of(aTool("analyze_customer_risk"))),
                (toolName, args, who) -> {
                    askedTools.add(toolName);
                    askedArgs.add(args);
                    return said;
                });
    }

    /** A model that calls one tool and then answers — the loop's two turns, without a provider. */
    private static ChatModel aModelThatCallsOnce(String toolName) {
        return new ChatModel() {

            private int turn = 0;

            @Override
            public ChatResponse call(Prompt prompt) {
                if (++turn == 1) {
                    AssistantMessage asks = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-1", "function", toolName, "{\"customerId\":7}")))
                            .build();
                    return new ChatResponse(List.of(new Generation(asks)));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            // A stub that does not advertise tool calling gets the tools stripped off the request —
            // measured, and a real provider advertises the capability, so this one does too.
            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
    }

    private static AiTool aTool(String name) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "查一个客户的风险";
            }

            @Override
            public String riskLevel() {
                return "R1";
            }

            @Override
            public ToolResult execute(Map<String, Object> args, Principal principal) {
                throw new AssertionError("the engine is the only path to a tool, and this is not it");
            }
        };
    }
}
