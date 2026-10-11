// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

/**
 * The seam a deployment satisfies to make its own {@code local_compensate} writes revocable.
 *
 * <p>Until this existed, the runtime's local revocation path named one entity — the sample module's
 * follow-up — so a write that declared {@code local_compensate} and produced anything else was refused
 * with a conflict, and that refusal happened before the audit line, leaving no trace that a revocation
 * had even been attempted. A deployment now registers one of these per {@code resultType} it wants
 * revocable, and the runtime looks the type up instead of knowing it.
 *
 * <p><b>At most one compensator per {@code resultType}.</b> Two beans claiming the same type fail the
 * application at startup rather than silently choosing between them, because "why did this one
 * compensate and not that one" is a question nobody can answer after the fact.
 *
 * <p><b>This adds no contract of its own.</b> {@code local_compensate} is already the contract's word
 * and {@code side-effect-revoke} is already a frozen object; what is missing is that the word leads to
 * a single demo entity. What the runtime lifts here is a <em>shape</em> — what a compensator actually
 * deletes, marks or reverses stays with the deployment. See {@code D37} and {@code D34}.
 *
 * 部署满足这条缝，才能让自己那些 {@code local_compensate} 的写**可撤销**。
 *
 * <p>在这条缝出现之前，运行时的本地撤销路**只点名一个实体** —— 样例模块的 follow-up —— 于是一次声明了
 * {@code local_compensate}、却产出别的东西的写会被一句冲突拒掉；而那次拒绝发生在审计行**之前**，连「试过撤销」
 * 都不留痕。现在部署**按 {@code resultType}** 各注册一个，运行时**查表**，而不是**知道**。
 *
 * <p>**一个 {@code resultType} 至多一个补偿器。** 两个 bean 认领同一个类型时，应用**启动即失败**，而不是在
 * 两者之间悄悄择一 —— 因为「为什么是这个补偿了、不是那个」是事后任何人都答不出来的问题。
 *
 * <p>**它不新增自己的契约。** {@code local_compensate} 本来就是契约的词，{@code side-effect-revoke} 本来
 * 就是冻结对象；缺的是这个词**只通向一个 demo 实体**。运行时在这里上提的是一个**形状** —— 补偿器**到底删什么、
 * 标什么、怎么反**，**留在部署手里**。见 `D37` 与 `D34`。
 */
public interface SideEffectCompensator {

    /** The {@code resultType} this compensator handles — the key the runtime looks up. */
    String resultType();

    /**
     * Undo what this effect did, locally. Called while the effect's row still says it is being revoked:
     * a throw aborts the whole revocation and leaves no audit line, exactly as before this seam.
     *
     * 就地撤销这个 effect 做过的事。调用时那一行**还没**改成 `revoked`：**抛异常会中止整次撤销、且不留审计
     * 行** —— 与这条缝出现之前完全一样。
     */
    void compensate(SideEffect effect);
}
