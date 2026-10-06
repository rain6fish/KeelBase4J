// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The runtime's tools, handed to the framework as tool callbacks — and the one line this class exists
 * to keep.
 *
 * <p>The plain {@link SpringAiToolCallPlanner} deliberately never lets the framework invoke anything:
 * the model is asked for a plan and the plan is handed back, so the only path from a model's choice to
 * a side effect runs through the engine. Native tool calling inverts that — the framework calls the
 * callback — so the callback has to be the engine's front door rather than a way around it: every call
 * goes to {@link GovernedCall}, which is the engine. Risk level, the confirmation requirement, the
 * audit entry and the revoke path are still applied there and are still not negotiable by the caller.
 *
 * <p><b>What the model is shown is name and description only.</b> {@link AiTool} carries more — {@code
 * riskLevel}, {@code requiresConfirmation}, {@code revokeClass} — and its javadoc says server-side
 * only; the definition built here is where that is honoured. Once the framework generates the schema,
 * that restraint is no longer obvious from the outside, so it is asserted in the tests rather than
 * assumed.
 *
 * <p><b>What comes back is the outcome, not the declaration.</b> The model has to be told that an
 * action did not run; it is not told the level or the class in advance. A call that is waiting on a
 * person therefore reports that it is waiting — which is a result, and is the only way the loop can
 * stop honestly rather than pretend. The <em>token</em> is not among what it sees: the handle to go on
 * belongs to the caller, which reads it from the engine's outcome, and a model holding it could carry
 * on without the person's decision ever being made.
 *
 * <p>把运行时的工具当作框架的 tool callback 交出去 —— 这个类存在的意义就是守住那一行。
 *
 * <p>朴素的 {@link SpringAiToolCallPlanner} **刻意**不让框架调用任何东西：它只要一个计划，然后把计划交回，
 * 于是「模型的选择 → 副作用」这条路上**只有引擎**。原生 tool calling 把这个关系反过来了——**框架**来调回调
 * ——所以回调必须是**引擎的前门**、而不是绕过它的路：每次调用都进 {@link GovernedCall}，那就是引擎。风险级、
 * 确认要求、审计条目与撤销路径仍在那里施加，仍不是调用方能谈的。
 *
 * <p>**给模型看的只有 name 与 description。** {@link AiTool} 还带着 {@code riskLevel} /
 * {@code requiresConfirmation} / {@code revokeClass}，其 javadoc 写明「仅服务端」；这里构建的定义就是兑现
 * 那句话的地方。一旦 schema 由框架生成，这份克制**从外面看不出来**了，所以它被**断言**而不是被假定。
 *
 * <p>**回给模型的是结果，不是声明。** 模型必须知道某个动作没跑成；它**不预先**被告知级别与类别。因此一次
 * 「在等人」的调用会如实回报「在等人」——那是**结果**，也是循环能诚实收尾、而不是假装继续的唯一办法。
 */
public final class GovernedToolCallbacks {

    /**
     * A permissive object schema, because a tool declares no parameters of its own yet: it receives a
     * map and validates what it reads. Tightening this is a change to {@link AiTool}, not to this
     * adapter — until then the model gets no guidance from the schema, which is a known cost.
     */
    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":true}";

    private GovernedToolCallbacks() {
    }

    /** One callback per registered tool, all of them answering through {@code call}. */
    public static List<ToolCallback> forCaller(ToolRegistry tools, GovernedCall call, Principal principal) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AiTool tool : tools.all()) {
            callbacks.add(new GovernedTool(tool, call, principal));
        }
        return List.copyOf(callbacks);
    }

    /**
     * The engine, seen from the adapter: a tool name, its arguments and the caller in, an outcome out.
     *
     * <p>It is an interface rather than the engine's class so this adapter stays testable without the
     * runtime's whole object graph, and so the dependency points at what the adapter actually uses —
     * one method — rather than at everything the engine can do.
     *
     * <p>引擎从适配器看过去的样子：工具名 + 参数 + 调用者进去，结果出来。它是个接口而不是引擎的类，好让本
     * 适配器**不必拖来运行时整个对象图**就能被测，也让依赖指向它**真正用到的那一个方法**。
     */
    @FunctionalInterface
    public interface GovernedCall {
        ExecutionOutcome execute(String toolName, Map<String, Object> args, Principal principal);
    }

    private record GovernedTool(AiTool tool, GovernedCall call, Principal principal) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> args = args(toolInput);
            if (args == null) {
                // Malformed, or not an object at all. Refused here rather than passed on as "no
                // arguments": an empty call is a real call, and running one on the strength of a
                // broken message is the silent kind of wrong.
                return CanonicalJson.json(Map.of("status", "invalid_arguments"));
            }
            ExecutionOutcome outcome = call.execute(tool.name(), args, principal);
            Map<String, Object> said = new LinkedHashMap<>();
            said.put("status", outcome.status());
            if (outcome.data() != null) {
                said.put("data", outcome.data());
            }
            if (outcome.error() != null) {
                said.put("error", outcome.error());
            }
            return CanonicalJson.json(said);
        }

        /**
         * The arguments as a command, or {@code null} when the input is not a JSON object at all —
         * which is a refusal, not an empty argument list.
         */
        private static Map<String, Object> args(String toolInput) {
            if (toolInput == null || toolInput.isBlank()) {
                return Map.of();
            }
            Object parsed;
            try {
                parsed = Json.parse(toolInput);
            } catch (RuntimeException malformed) {
                return null;
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                return null;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
            return args;
        }
    }
}
