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

    /**
     * The business result type this tool creates (e.g. {@code follow_up}), or null for reads.
     *
     * <p><b>Declaring one is a promise about the result.</b> A tool that names a result type has its
     * execution recorded in the effect ledger, and the ledger — and the frozen contract — require the
     * effect to say <em>which</em> row it produced. So {@code execute} has to return a result whose
     * {@code id} is a number, which in practice means a {@code Map} carrying a numeric {@code id}. A
     * tool that declares a result type and then returns something else has written whatever it wrote
     * and left the runtime unable to record it: the run ends as a recorded failure, the effect is
     * unrecorded, and the claim on that call stays taken until its lease runs out. That is the honest
     * end — the write may have landed — but naming a result id is what keeps it from happening at all,
     * and that is the tool's job rather than the runtime's, because the runtime cannot know a row it
     * was never shown.
     *
     * <p>A tool that names none is never claimed and never recorded, which makes this the read/write
     * switch as much as a label.
     *
     * <p><b>A governed write (R3–R5, or a tool that overrides {@link #requiresConfirmation()} to
     * true) that names none is refused registration.</b> It is not offered to a model and cannot be
     * executed; {@code keelbase/status} names it. A read at R0–R2 keeps the old behaviour and needs
     * nothing here.
     *
     * 本工具创建的业务结果类型（例如 {@code follow_up}）；读则返回 null。
     *
     * <p><b>声明一个，就是就「结果」许下一个承诺。</b>点名了结果类型的工具，其执行会记进副作用账本；而账本
     * （以及冻结的契约）要求 effect 说清它**产出了哪一行**。故 {@code execute} 必须交回一个 `id` 为数字的结果，
     * 实践中即一个带数字 `id` 的 `Map`。一个声明了结果类型、却交回别的东西的工具，**写了它写的东西，却让运行时
     * 记不下来**：这次运行以**记录式失败**收尾、effect 不落、而这次调用的领取一直占着直到租约跑完。那是诚实的结局
     * —— 写可能已落地 —— 但**点名一个结果 id** 才是让它根本不发生的办法，而那是**工具**的事、不是运行时的事：
     * 运行时**无从知道**一张它从未被展示过的行。
     *
     * <p>什么都不点名的工具**从不占位、也从不记账** —— 故这个方法是**读/写开关**，不只是一个标签。
     *
     * <p><b>一个受治理的写（R3–R5，或覆写 {@link #requiresConfirmation()} 为真的工具）什么都不点名时，
     * 会被拒绝注册。</b>它不会被交给模型、也无法执行；`keelbase/status` 会点名它。R0–R2 的读保持旧行为，
     * 这里不需要改。
     */
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
