// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import java.util.List;

/**
 * What a run did: the model's answer, every governed step the loop took in order, and the token of the
 * last step still waiting on a person — {@code null} when nothing is waiting.
 *
 * <p>The last field is what carries a confirmation out of the loop. A request that stops at "a human
 * must approve this" hands the caller the handle to go on with, instead of leaving it inside the
 * model's context where nobody can decide it.
 *
 * <p>It lives here rather than in an adapter because a run's shape is the seam's vocabulary
 * ({@link TaskRunner}), and the route that answers with it belongs to this runtime.
 *
 * <p>一次运行做了什么：模型的答复、循环**按序**走的每一步受治理的调用、以及最后一个仍在等人的步骤的
 * token —— 没有人在等时为 {@code null}。
 *
 * <p>最后那个字段就是**把确认带出循环**的东西：一个停在「这里得有人点头」的请求，把**继续下去的把手**
 * 交回调用方，而不是把它留在模型的上下文里、任谁都裁决不了。
 *
 * <p>它住在 core 而不是某个适配器里，因为一次运行的形状是**接缝的词汇**（{@link TaskRunner}），而用它作答的
 * 那条路由属于**本运行时**。
 */
public record TaskRun(String answer, List<Step> steps, String pendingToken) {

    public TaskRun {
        steps = List.copyOf(steps);
    }

    /**
     * One step: the tool it was an attempt at, and what the engine said about that call.
     *
     * <p>The tool's name is carried rather than inferred from the outcome, because an outcome does not
     * know which tool produced it — and a run that reported three results without saying what they were
     * three attempts at would not be a report anyone could line up against an audit chain.
     *
     * <p>一步：它冲的是哪个工具，以及引擎对那次调用说了什么。
     *
     * <p>工具的**名字**是被带上的、不是从结果里推的 —— 结果并不知道自己是哪个工具产生的；而一次运行若只报
     * 三个结果、不说它们是三次**什么的**尝试，那就不是一份能和审计链对得上的报告。
     */
    public record Step(String tool, ExecutionOutcome outcome) {
    }
}
