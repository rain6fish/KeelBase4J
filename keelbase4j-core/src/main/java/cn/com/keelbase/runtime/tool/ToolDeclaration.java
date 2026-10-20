// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

/**
 * The declaration rule that keeps a governed write from turning into an unrecorded read.
 *
 * <p>{@link AiTool#resultType()} doubles as the read/write switch: the engine claims a write and
 * records its side effect only when the tool names a result type. A tool that a human must confirm
 * (R3/R4), or that the risk policy can only refuse (R5), is a governed write regardless of what it
 * returns — so a missing result type there is not a valid read, it is a declaration gap. Registering
 * it would let a person approve a write that leaves no effect to audit or revoke.
 *
 * <p><b>Reads are deliberately untouched.</b> R0-R2 tools without a result type are reads, and a
 * runtime that demanded one from them would reject the ordinary tools it was built to govern.
 *
 * 这条声明规则让**受治理的写**不会退化成一笔无人记账的读。
 *
 * <p>{@link AiTool#resultType()} 同时是读写开关：只有当工具点名了结果类型，引擎才占位并登记副作用。
 * 一个需要人确认（R3/R4）、或风险策略只会拒绝（R5）的工具，无论它返回什么，都是**受治理的写**——所以
 * 那里缺结果类型不是合法的只读，而是**声明缺口**。把它注册进来，等于让人批准一次**留不下可审计、可撤销
 * 副作用**的写。
 *
 * <p><b>读工具刻意不在范围内。</b>没有结果类型的 R0–R2 工具是读；连它们也一并要求结果类型，会让这个
 * 运行时拒绝掉它本来就要治理的普通工具。
 */
public final class ToolDeclaration {

    private ToolDeclaration() {
    }

    /**
     * Whether the runtime governs this tool as a write: the two levels that wait for a human (R3/R4),
     * the level the policy always refuses (R5), or a tool that opts into confirmation by overriding
     * {@link AiTool#requiresConfirmation()}.
     *
     * 运行时是否把这个工具按写治理：等人工决定的两级（R3/R4）、策略总是拒绝的一级（R5），或一个通过
     * 覆写 {@link AiTool#requiresConfirmation()} 主动要求确认的工具。
     */
    public static boolean isGovernedWrite(AiTool tool) {
        String level = tool.riskLevel();
        return tool.requiresConfirmation()
                || "R3".equals(level)
                || "R4".equals(level)
                || "R5".equals(level);
    }

    /**
     * Whether this tool is a governed write whose {@code resultType} is absent or blank.
     *
     * 这个工具是不是一个**结果类型缺失（或为空白）**的受治理写。
     */
    public static boolean missingResultType(AiTool tool) {
        String resultType = tool.resultType();
        return isGovernedWrite(tool) && (resultType == null || resultType.isBlank());
    }

    /**
     * The refusal reason, or {@code null} when the declaration is complete.
     *
     * <p>The sentence names the tool, what is missing and how to fix it — all three, because a
     * refusal that only says "invalid declaration" leaves the reader to grep for the rest.
     *
     * 拒绝理由；声明完整时为 {@code null}。
     *
     * <p>这句话点名**哪个工具、缺什么、怎么补**——三样都要，因为只说「声明非法」的拒绝，会让读的人
     * 自己去翻剩下的部分。
     */
    public static String refusal(AiTool tool) {
        if (!missingResultType(tool)) {
            return null;
        }
        return "tool `" + tool.name() + "` declares risk level `" + tool.riskLevel()
                + "`, which this runtime governs as a write, but it declares no `resultType`. "
                + "Without one the runtime would treat the write as a read: an approved execution "
                + "would leave no side effect to audit or revoke. Fix: return the business result "
                + "type from `resultType()` and make `execute` return a result with a numeric `id`; "
                + "if the tool only reads, lower its risk level to R0-R2.";
    }
}
