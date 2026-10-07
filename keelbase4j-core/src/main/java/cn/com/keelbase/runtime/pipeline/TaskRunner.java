// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import cn.com.keelbase.runtime.identity.Principal;

/**
 * Where a message becomes a task a framework may take several governed steps to finish — the seam an
 * orchestration adapter plugs into.
 *
 * <p>{@link ToolCallPlanner} hands back one plan for one message. This one is the other shape: the
 * model is given the runtime's tools and the framework's own calling loop reads each result before
 * deciding what to call next. Nothing here sequences anything — that is the primitive being reused
 * rather than rebuilt — and this runtime ships <b>no</b> implementation of it, because a loop worth
 * reusing belongs to a framework rather than to a runtime. A deployment without one says so rather
 * than inventing an answer (see {@code /ai/task}).
 *
 * <p>Deliberately not here, for the same reason the planner does not have it: model or provider
 * configuration. Which framework drives the loop, and which provider sits behind it, is a deployment
 * decision.
 *
 * <p><b>The planner's invariant, one step further out.</b> A task runner decides what to call and in
 * what order, never whether it may run: every step reaches the engine, because the callbacks it hands
 * the framework have no other path. Risk level, the confirmation requirement, the audit entry and the
 * revoke path are applied downstream and unconditionally — so a run cannot propose its way past the
 * gate, and a step waiting on a person comes back as that, with its token, instead of being decided
 * here.
 *
 * <p>一条消息变成一个**框架可以走若干受治理步骤**去完成的任务 —— 编排适配器插进来的那条接缝。
 *
 * <p>{@link ToolCallPlanner} 为一条消息交回**一个计划**；这一条管的是另一个形状：把运行时的工具交给
 * 模型，由**框架自己的调用循环**读一步的结果、再决定下一步调什么。**这里没有任何东西在排步骤** —— 那正是
 * 被复用而不是被重建的原语 —— 而**本运行时不提供任何实现**，因为一条值得复用的循环属于**框架**，不属于运行时。
 * 没有实现的部署会**如实说出这一点**，而不是编一个答复（见 `{@code /ai/task}`）。
 *
 * <p>与规划器不设模型配置是同一个理由，这里也**刻意没有**：由哪个框架驱动这条循环、后面接哪家 provider，
 * 都是**部署**的决定。
 *
 * <p>**规划器那条不变式，往外挪一步。** 任务运行器决定**调什么、按什么顺序**，从不决定**能不能跑**：每一步
 * 都到达引擎，因为它交给框架的那些回调**没有别的路**。风险级、确认要求、审计条目与撤销路径都在下游无条件
 * 施加 —— 所以一次运行**不能靠提议绕过闸**；而等某个人的那一步会**如实带回**它自己与它的 token，不在这里被
 * 裁决。
 */
public interface TaskRunner {

    /**
     * Runs this message as a task and reports what happened, in the order it happened.
     *
     * @param message   the caller's words
     * @param principal who is asking — every step is governed as that caller, so a step they may not
     *                  take is refused exactly as it would be for a direct call
     */
    TaskRun run(String message, Principal principal);
}
