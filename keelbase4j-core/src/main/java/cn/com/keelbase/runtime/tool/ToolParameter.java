// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One argument a tool declares, so the runtime can refuse a bad proposal before a human is asked to
 * approve it.
 *
 * <p><b>Why the tool declares it and not the caller.</b> A tool reads its arguments out of a map, and
 * until this type existed nothing said which keys it reads: a model that named one of them differently
 * produced a proposal that looked well-formed, became a confirmation row, and only failed inside
 * {@code execute} — after somebody had already approved it, and with nothing written. The declaration
 * lives here because the tool is the only party that knows, and it is checked in one place downstream
 * of every planner.
 *
 * <p><b>The type vocabulary is the protocol's, not this class's.</b> The five names below are the
 * values {@code ai-tool-inventory.schema.json} already uses for a tool's parameter list, so a
 * declaration here can be rendered onto that wire shape without translation.
 *
 * 一个工具声明的**一个入参**，好让运行时在请人批准之前就拒掉一份坏提议。
 *
 * <p><b>为什么由工具声明、而不是由调用方。</b>工具从一个 map 里读它的参数，而在这个类型存在之前，
 * **没有任何东西说过它读哪些键**：模型把其中一个名字写错，产出的提议看上去完好、变成一行待确认，直到
 * {@code execute} 里才失败 —— 那已经是**有人点过批准之后**，而且什么都没写进去。声明放在这里，是因为
 * **只有工具自己知道**；校验则放在**所有规划器的下游、唯一那处**。
 *
 * <p><b>类型词表是协议的，不是本类的。</b>下面五个名字就是
 * {@code ai-tool-inventory.schema.json} 早就在用的那套工具参数取值 —— 于是这里的声明可以**不经翻译**
 * 渲染到那条 wire 形状上。
 *
 * @param name the key the tool reads out of its argument map
 * @param type one of {@link #STRING}, {@link #NUMBER}, {@link #BOOLEAN}, {@link #ARRAY}, {@link #OBJECT}
 * @param description what the argument means, in the words a model should see
 * @param required whether a proposal without it is refused
 */
public record ToolParameter(String name, String type, String description, boolean required) {

    public static final String STRING = "string";
    public static final String NUMBER = "number";
    public static final String BOOLEAN = "boolean";
    public static final String ARRAY = "array";
    public static final String OBJECT = "object";

    private static final Set<String> TYPES = Set.of(STRING, NUMBER, BOOLEAN, ARRAY, OBJECT);

    /** The type names a declaration may use — the protocol's vocabulary, and the only accepted ones. */
    public static Set<String> types() {
        return TYPES;
    }

    public ToolParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a parameter needs a name");
        }
        if (!TYPES.contains(type)) {
            // Refused at declaration time rather than at validation time: a typo in the type would
            // otherwise produce a schema the model reads as `{"type":"sting"}` and silently obey, and
            // the mistake would be invisible on both sides of the wire.
            //
            // 在**声明时**就拒，而不是等到校验时：类型写错会产出一份模型读成 `{"type":"sting"}` 的 schema，
            // 它会**默默照做**，而错误在 wire 两边都看不见。
            throw new IllegalArgumentException(
                    "unknown parameter type `" + type + "`; expected one of " + TYPES);
        }
    }

    /** A parameter a proposal must carry. */
    public static ToolParameter required(String name, String type, String description) {
        return new ToolParameter(name, type, description, true);
    }

    /** A parameter a proposal may leave out. */
    public static ToolParameter optional(String name, String type, String description) {
        return new ToolParameter(name, type, description, false);
    }

    /** The declared names, in declaration order — what a proposal is allowed to carry. */
    public static Set<String> namesOf(List<ToolParameter> parameters) {
        return parameters.stream().map(ToolParameter::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The names a proposal must carry. */
    public static Set<String> requiredOf(List<ToolParameter> parameters) {
        return parameters.stream().filter(ToolParameter::required).map(ToolParameter::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** {@code content: string, required; customerId: number} — for an error message a model can act on. */
    public static String describe(List<ToolParameter> parameters) {
        return parameters.stream().map(p -> p.name() + ": " + p.type()
                        + (p.required() ? ", required" : ""))
                .collect(Collectors.joining("; "));
    }
}
