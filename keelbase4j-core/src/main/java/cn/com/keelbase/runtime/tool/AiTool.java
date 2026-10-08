// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;

/**
 * A governable AI tool — the minimum a tool must declare for the runtime to gate it.
 *
 * <p>Governance-relevant metadata (risk level, whether it needs confirmation, its revoke class)
 * is server-side only and never sent to a model. {@code execute} receives the acting principal,
 * so data isolation is a property of the tool, not of the caller.
 */
public interface AiTool {

    String name();

    String description();

    /** Declared risk level (R0–R5). */
    String riskLevel();

    /** Derived from the risk level unless a tool overrides it. */
    default boolean requiresConfirmation() {
        return RiskLevel.needsConfirmation(riskLevel());
    }

    /** The business result type this tool creates (e.g. {@code follow_up}), or null for reads. */
    default String resultType() {
        return null;
    }

    /** Revoke class (§2.3 revoke contract): {@code none} for reads, {@code local_compensate} for writes. */
    default String revokeClass() {
        return requiresConfirmation() ? "local_compensate" : "none";
    }

    /**
     * The arguments this tool reads, so a proposal naming them wrongly is refused <em>before</em> it
     * becomes a confirmation row.
     *
     * <p>Declaring them is not a formality: a proposal that names a parameter the tool does not read,
     * or leaves out one it requires, is refused at the gate with {@code invalid_arguments} and reaches
     * no human. What it prevents is the shape this interface used to allow — a plausible-looking
     * proposal, an approved confirmation, and an execution that failed having written nothing.
     *
     * <p><b>An empty list means "this tool declares nothing", and nothing is checked.</b> That default
     * is deliberate and is what keeps this an additive change: every tool written before this method
     * existed keeps working untouched, and a tool that declares nothing is not silently treated as
     * taking no arguments. Declaring is how a tool opts into being checked, so the cost of leaving it
     * out is that the tool keeps the old behaviour rather than gaining a wrong one.
     *
     * <p>The list also becomes what a model is shown, which is the half that prevents the mistake
     * instead of merely catching it.
     *
     * 这个工具**读哪些入参**，好让一份把名字写错的提议**在变成待确认行之前**就被拒。
     *
     * <p>声明它不是走形式：一份写了工具不读的参数、或漏了它必填的参数的提议，会在闸口以
     * {@code invalid_arguments} 被拒，**到不了人**。它挡掉的正是这个接口原先允许的那种形状 —— 一份看上去
     * 像样的提议、一条被批准的确认、和一次**什么都没写**的执行。
     *
     * <p><b>空列表的意思是「本工具什么都没声明」，于是什么都不校验。</b>这个默认是刻意的，也正是它让本次
     * 改动**只是加性的**：在这个方法存在之前写下的每个工具都原样继续工作，而**什么都没声明的工具不会被
     * 悄悄当成「不接受任何参数」**。声明是工具**选择**被校验的方式；不声明的代价是它保持旧行为，而不是
     * 换来一个错的行为。
     *
     * <p>这份列表同时也会成为**给模型看的东西** —— 那是**防止**出错的那一半，而不只是接住它。
     */
    default List<ToolParameter> parameters() {
        return List.of();
    }

    ToolResult execute(Map<String, Object> args, Principal principal);
}
