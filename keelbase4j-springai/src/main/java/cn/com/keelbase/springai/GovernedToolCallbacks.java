// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
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
 * <p><b>What the model is shown is the tool's own declaration: its name, its description, and the
 * arguments it declares.</b> {@link AiTool} carries more — {@code riskLevel}, {@code
 * requiresConfirmation}, {@code revokeClass} — and its javadoc says server-side only; the definition
 * built here is where that is honoured. The declared arguments are not governance metadata: they are
 * the only statement anywhere of what the tool reads, and withholding them is what let a model name an
 * argument freely and find out only after somebody approved it. Once the framework generates the schema,
 * the restraint is no longer obvious from the outside, so it is asserted in the tests rather than
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
 * <p>**给模型看的是工具自己的声明：它的名字、描述，以及它声明的入参。** {@link AiTool} 还带着
 * {@code riskLevel} / {@code requiresConfirmation} / {@code revokeClass}，其 javadoc 写明「仅服务端」；
 * 这里构建的定义就是兑现那句话的地方。而入参声明**不是**治理元数据 —— 它是**这个工具读什么**的唯一陈述，
 * 把它扣下来，正是模型得以随便给参数起名、并直到有人点过批准才发现的缘故。一旦 schema 由框架生成，这份克制
 * **从外面看不出来**了，所以它被**断言**而不是被假定。
 *
 * <p>**回给模型的是结果，不是声明。** 模型必须知道某个动作没跑成；它**不预先**被告知级别与类别。因此一次
 * 「在等人」的调用会如实回报「在等人」——那是**结果**，也是循环能诚实收尾、而不是假装继续的唯一办法。
 */
public final class GovernedToolCallbacks {

    /**
     * What a tool that declares no arguments is shown as: an object with nothing stated about it.
     *
     * <p>It is the same statement the engine makes when it does not check such a tool's arguments, and
     * it is deliberately not {@code "no arguments allowed"} — a tool written before arguments were
     * declarable keeps working, and its schema says the truth about it: nothing is known here.
     */
    private static final String UNCONSTRAINED_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":true}";

    private GovernedToolCallbacks() {
    }

    /**
     * The tool's declared arguments, as the JSON schema the framework hands the model.
     *
     * <p>Which is the half that <em>prevents</em> the mistake rather than catching it: before this, the
     * model was shown a tool with no stated arguments and had to guess the names, and guessing is what
     * produced a confirmation somebody approved and an execution that wrote nothing.
     *
     * <p>Built through {@link CanonicalJson} rather than by concatenating strings, so the escaping is
     * the protocol's own — a description containing a quote would otherwise emit a schema no parser
     * accepts.
     *
     * 工具的**已声明入参**，就是框架交给模型的那份 JSON schema。
     *
     * <p>这是**防止**出错、而不只是接住它的那一半：在此之前，模型看到的是一个**没有声明任何参数**的工具，
     * 只能去猜名字 —— 而猜，正是「一条被人批准的确认 + 一次什么都没写的执行」的来源。
     *
     * <p>经 {@link CanonicalJson} 构建、而不是拼字符串，转义才是**协议自己的** —— 描述里带一个引号，
     * 拼出来的就会是一份没有解析器接受的 schema。
     */
    private static String inputSchema(AiTool tool) {
        List<ToolParameter> parameters = tool.parameters();
        if (parameters.isEmpty()) {
            return UNCONSTRAINED_SCHEMA;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : parameters) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", parameter.type());
            if (parameter.description() != null && !parameter.description().isBlank()) {
                property.put("description", parameter.description());
            }
            properties.put(parameter.name(), property);
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return CanonicalJson.json(schema);
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
                    .inputSchema(inputSchema(tool))
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
