// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * What the adapter does with a model's answer, and what it refuses to do.
 *
 * <p>No Spring context and no network: the model is a lambda that returns a fixed reply and records
 * the prompt it was handed. A real provider is a deployment's business; these are the adapter's.
 */
class SpringAiToolCallPlannerTest {

    private final List<String> prompts = new ArrayList<>();

    @Test
    void aToolTheModelNamesBecomesAPlan() {
        Optional<IntentPlan> plan = planWith("""
                {"tool": "analyze_customer_risk", "args": {"customerId": 7}}""");

        assertTrue(plan.isPresent());
        assertEquals("analyze_customer_risk", plan.get().tool());
        assertEquals(7, ((Number) plan.get().args().get("customerId")).intValue());
    }

    @Test
    void aToolTheRuntimeDoesNotHaveIsNotAPlan() {
        // The model does not get to widen the surface: a name outside the registry routes nowhere.
        Optional<IntentPlan> plan = planWith("""
                {"tool": "delete_every_customer", "args": {}}""");

        assertTrue(plan.isEmpty(), "a tool the runtime does not have must not become a plan");
    }

    @Test
    void noFittingToolRoutesNowhere() {
        assertTrue(planWith("""
                {"tool": null}""").isEmpty());
    }

    @Test
    void aReplyThatIsNotAPlanRoutesNowhere() {
        assertTrue(planWith("I'm sorry, I can't help with that.").isEmpty(),
                "prose is not a plan; the caller should see that rather than a guessed call");
    }

    @Test
    void aFencedJsonReplyIsStillReadAsAPlan() {
        Optional<IntentPlan> plan = planWith("""
                ```json
                {"tool": "create_followup", "args": {"customerId": 1}}
                ```""");

        assertTrue(plan.isPresent(), "a code fence is formatting, not part of the answer");
        assertEquals("create_followup", plan.get().tool());
    }

    @Test
    void theCatalogueSentToTheModelCarriesNoGovernanceMetadata() {
        planWith("{\"tool\": null}");

        String prompt = prompts.get(0);
        assertTrue(prompt.contains("analyze_customer_risk"), "the model must be told what exists");
        assertFalse(prompt.contains("R1"),
                "the risk level is the runtime's fact and must not reach the model: " + prompt);
        assertFalse(prompt.toLowerCase().contains("confirm"),
                "nor may the confirmation requirement: " + prompt);
    }

    @Test
    void thePlannerNeverExecutesATool() {
        // Tool.execute below throws. A planner that invoked what it proposed would fail here rather
        // than return — which is the boundary this seam exists to hold.
        Optional<IntentPlan> plan = planWith("""
                {"tool": "analyze_customer_risk", "args": {"customerId": 7}}""");

        assertTrue(plan.isPresent(), "proposing a call is all a planner may do");
    }

    private Optional<IntentPlan> planWith(String reply) {
        ChatModel model = prompt -> {
            prompts.add(prompt.getContents());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        };
        ChatClient client = ChatClient.builder(model).build();
        ToolRegistry registry = new ToolRegistry(List.of(
                readTool(), writeTool()));
        return new SpringAiToolCallPlanner(client, registry)
                .plan("帮我看看这个客户", Map.of("customerId", 7));
    }

    private AiTool readTool() {
        return stub("analyze_customer_risk", "分析客户风险，只读", "R1");
    }

    private AiTool writeTool() {
        return stub("create_followup", "为客户创建一条跟进记录", "R3");
    }

    private AiTool stub(String name, String description, String riskLevel) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String riskLevel() {
                return riskLevel;
            }

            @Override
            public ToolResult execute(Map<String, Object> args, Principal principal) {
                throw new AssertionError("a planner proposes; it must never execute " + name);
            }
        };
    }
}
