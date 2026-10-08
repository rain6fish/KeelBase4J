// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What the model is shown, and what it is not — the two claims this adapter has to keep once the
 * framework, rather than the adapter, is the thing invoking a tool.
 *
 * <p>No Spring context and no network: the engine is a lambda that answers whichever way the test
 * needs, and a tool that records whether it was ever asked to run. A real provider is a deployment's
 * business; these are the adapter's.
 */
class GovernedToolCallbacksTest {

    private final List<String> ranTools = new ArrayList<>();

    /** A tool that carries every piece of governance metadata, and notices if anything runs it. */
    private AiTool watched(String name) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "为客户创建一条跟进记录";
            }

            @Override
            public String riskLevel() {
                return "R4";
            }

            @Override
            public ToolResult execute(Map<String, Object> args, Principal principal) {
                ranTools.add(name);
                return ToolResult.ok(Map.of("id", 1));
            }
        };
    }

    private ToolCallback one(GovernedToolCallbacks.GovernedCall engine, AiTool tool) {
        List<ToolCallback> callbacks =
                GovernedToolCallbacks.forCaller(new ToolRegistry(List.of(tool)), engine, caller());
        return callbacks.get(0);
    }

    private static Principal caller() {
        return new Principal("7", "user");
    }

    /** A tool that says which arguments it reads — what stops a model having to guess their names. */
    private AiTool declaring(String name) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "记住一件事";
            }

            @Override
            public String riskLevel() {
                return "R4";
            }

            @Override
            public List<ToolParameter> parameters() {
                return List.of(
                        ToolParameter.required("content", ToolParameter.STRING, "the note to keep"),
                        ToolParameter.optional("customerId", ToolParameter.NUMBER, "who it is about"));
            }

            @Override
            public ToolResult execute(Map<String, Object> args, Principal principal) {
                ranTools.add(name);
                return ToolResult.ok(Map.of("id", 1));
            }
        };
    }

    @Test
    void theModelIsShownTheArgumentsTheToolDeclares() {
        ToolDefinition definition = one((name, args, who) -> ExecutionOutcome.executed(null, null),
                declaring("remember")).getToolDefinition();

        String schema = definition.inputSchema();
        assertTrue(schema.contains("\"content\""), "the required argument must reach the model: " + schema);
        assertTrue(schema.contains("\"customerId\""), "and so must the optional one: " + schema);
        assertTrue(schema.contains("\"required\""), "which of them is required has to travel too: " + schema);
        assertFalse(schema.contains("\"additionalProperties\":true"),
                "a tool that declares its arguments is not shown as open-ended: " + schema);

        // The declaration is not governance metadata, so it may travel — and the metadata still may not.
        String shown = definition.name() + " | " + definition.description() + " | " + schema;
        for (String governed : List.of("R4", "risk", "confirm", "revoke", "compensate")) {
            assertFalse(shown.toLowerCase().contains(governed.toLowerCase()),
                    "the model must not be shown governance metadata, but the definition carried: " + governed);
        }
    }

    @Test
    void aToolThatDeclaresNothingIsStillShownAnOpenSchema() {
        ToolDefinition definition = one((name, args, who) -> ExecutionOutcome.executed(null, null),
                watched("create_followup")).getToolDefinition();

        assertTrue(definition.inputSchema().contains("\"additionalProperties\":true"),
                "a tool written before arguments were declarable states nothing, and its schema says so "
                        + "rather than claiming it takes no arguments: " + definition.inputSchema());
    }

    @Test
    void theModelIsShownTheNameAndTheDescriptionAndNothingElse() {
        ToolDefinition definition = one((name, args, who) -> ExecutionOutcome.executed(null, null),
                watched("create_followup")).getToolDefinition();

        assertEquals("create_followup", definition.name());
        assertEquals("为客户创建一条跟进记录", definition.description());

        // The governance metadata lives on AiTool and must not travel: not as fields, and not
        // smuggled into the schema. This is the assertion that replaces the plain planner's habit of
        // simply not putting it in a prompt.
        String shown = definition.name() + " | " + definition.description() + " | " + definition.inputSchema();
        for (String governed : List.of("R4", "risk", "confirm", "revoke", "compensate")) {
            assertFalse(shown.toLowerCase().contains(governed.toLowerCase()),
                    "the model must not be shown governance metadata, but the definition carried: " + governed);
        }
    }

    @Test
    void whatRanComesBackToTheModel() {
        String said = one((name, args, who) -> ExecutionOutcome.executed(Map.of("id", 42), 99L),
                watched("create_followup")).call("{\"customerId\":7}");

        assertTrue(said.contains("executed"), "the outcome status is what the model reacts to: " + said);
        assertTrue(said.contains("42"), "the data the call produced has to reach the model: " + said);
    }

    @Test
    void theCallbackIsNotASecondWayAroundTheEngine() {
        // The replica of the plain planner's whole point, expressed where it can actually break: the
        // callback must ask the engine and nothing else. A tool that would have been run by a callback
        // that invoked it directly stays untouched.
        one((name, args, who) -> ExecutionOutcome.executed(Map.of("id", 1), 1L), watched("create_followup"))
                .call("{}");

        assertTrue(ranTools.isEmpty(),
                "the adapter invoked the tool itself, which is the path around the gate");
    }

    @Test
    void aCallWaitingOnAPersonReportsThatItIsWaitingWithoutHandingOverTheToken() {
        String said = one((name, args, who) -> new ExecutionOutcome("pending_confirmation", null, "tok-1", null, null),
                watched("create_followup")).call("{}");

        assertTrue(said.contains("pending_confirmation"), said);
        assertFalse(said.contains("tok-1"),
                "the token is the caller's handle to go on; a model holding it could continue without the decision: "
                        + said);
    }

    @Test
    void argumentsThatAreNotAnObjectAreRefusedRatherThanRunEmpty() {
        List<Map<String, Object>> seen = new ArrayList<>();
        String said = one((name, args, who) -> {
            seen.add(args);
            return ExecutionOutcome.executed(null, null);
        }, watched("create_followup")).call("[1,2,3]");

        assertTrue(said.contains("invalid_arguments"), said);
        assertTrue(seen.isEmpty(), "a call with no arguments is a real call; a broken message is not one");
        assertTrue(ranTools.isEmpty(), ranTools.toString());
    }

    @Test
    void malformedJsonIsRefusedRatherThanThrownThroughTheLoop() {
        List<Map<String, Object>> seen = new ArrayList<>();
        String said = one((name, args, who) -> {
            seen.add(args);
            return ExecutionOutcome.executed(null, null);
        }, watched("create_followup")).call("{\"customerId\":");

        assertTrue(said.contains("invalid_arguments"), said);
        assertTrue(seen.isEmpty(), "the engine must not be asked to run a call whose arguments did not parse");
    }

    @Test
    void argumentsReachTheEngineAsACommand() {
        List<Map<String, Object>> seen = new ArrayList<>();
        one((name, args, who) -> {
            seen.add(args);
            return ExecutionOutcome.executed(null, null);
        }, watched("create_followup")).call("{\"customerId\":7,\"note\":\"跟进\"}");

        assertEquals(1, seen.size());
        assertEquals(7, ((Number) seen.get(0).get("customerId")).intValue());
        assertEquals("跟进", seen.get(0).get("note"));
    }

    @Test
    void theToolListTheFrameworkHandsTheModelCarriesNoGovernanceMetadata() {
        // RY-25's acceptance ③, and it is not the same check as the first test above: that one reads the
        // object this adapter builds, while a native tool-calling request is assembled *by the
        // framework*, which could in principle carry more than it was handed. So this drives a real
        // ChatClient and reads back what actually went out — the tool list, as the model receives it.
        //
        // RY-25 验收 ③，而它与上面第一条**不是同一个检查**：那条读的是本适配器**自己构造的那个对象**，而原生
        // tool-calling 的请求是**由框架**装配的——原则上它可以带上比交给它的更多的东西。所以这一条驱动一个真
        // `ChatClient`，把**真正发出去的那份**读回来：工具列表，按模型收到的样子。
        List<Prompt> handed = new ArrayList<>();
        ChatModel recording = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                handed.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            // A stub that does not advertise tool calling gets a request with the tools stripped off it —
            // measured, and it is why an earlier attempt at this saw the framework answer from its own
            // head and never touch a callback. A real provider advertises the capability; so does this.
            //
            // **不声明支持 tool calling 的 stub，拿到的请求里工具是被摘掉的**——实测如此，也正是先前那次尝试
            // 「框架从自己的答案作答、从不碰回调」的由来。真实提供方会声明这个能力；这个 stub 也声明。
            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };

        // The advisor is what the autoconfigured builder carries in a real deployment. Without it the
        // callbacks never reach the model at all — measured: the request arrives with plain options and
        // the tools are simply absent — so a test that left it out would measure the wrong thing and
        // pass for the wrong reason.
        //
        // 这个 advisor 就是真实部署里**自动配置的那个 builder** 所带的。没有它，回调**根本到不了模型**——
        // **实测**：请求带着普通 options 到达、工具干脆不在——所以省掉它的测试**量的是错的东西**，而且会以
        // **错的理由**变绿。
        ChatClient.builder(recording)
                .defaultAdvisors(ToolCallingAdvisor.builder().build())
                .build()
                .prompt()
                .user("列出客户")
                .toolCallbacks(GovernedToolCallbacks.forCaller(
                        new ToolRegistry(List.of(watched("create_followup"))),
                        (name, args, who) -> ExecutionOutcome.executed(null, null),
                        caller()))
                .call()
                .content();

        assertEquals(1, handed.size(), "the model was not asked once, so nothing was measured");
        ChatOptions options = handed.get(0).getOptions();
        assertTrue(options instanceof ToolCallingChatOptions,
                "the request carried no tool-calling options, so the tools went out some other way: " + options);
        List<ToolCallback> tools = ((ToolCallingChatOptions) options).getToolCallbacks();
        assertEquals(1, tools.size(), "the tools registered are the tools handed over");

        ToolDefinition definition = tools.get(0).getToolDefinition();
        String shown = definition.name() + " | " + definition.description() + " | " + definition.inputSchema();
        for (String governed : List.of("R4", "risk", "confirm", "revoke", "compensate")) {
            assertFalse(shown.toLowerCase().contains(governed.toLowerCase()),
                    "the framework handed the model governance metadata the adapter never gave it: " + governed);
        }
    }
}
