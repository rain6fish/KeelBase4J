// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
    void aCallWaitingOnAPersonReportsThatItIsWaiting() {
        String said = one((name, args, who) -> new ExecutionOutcome("pending_confirmation", null, "tok-1", null, null),
                watched("create_followup")).call("{}");

        assertTrue(said.contains("pending_confirmation"), said);
        assertTrue(said.contains("tok-1"), "the token is how the loop stops honestly instead of continuing: " + said);
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
}
