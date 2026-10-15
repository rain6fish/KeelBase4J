// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A deployment's planner, in the shape two independent hosts arrived at: <b>its own routes first, the
 * runtime's rule-based router last</b>.
 *
 * <p><b>Lifted, not invented.</b> Two hosts that embed this runtime wrote their planners independently
 * — different class names, same shape: each implements {@link ToolCallPlanner}, each holds a list of
 * {@link ModuleRoute}s, each falls back to {@code new RuleBasedPlanner()}, and each {@code plan()} asks
 * its own routes and then hands the message to the runtime's router. The order is the substance: a
 * module's phrase is more specific than the runtime's sample-module keywords, so the deployment's own
 * routing gets the first word and the runtime's rules remain the floor rather than a competitor.
 *
 * <p><b>The runtime's rules are delegated to, not copied.</b> The fallback is instantiated here and
 * asked last, so the sample module's routes keep behaving exactly as they did. Copying them would
 * create a second place where routing is written, and the two would drift.
 *
 * <p><b>A planner decides what to call, never whether it may run.</b> Nothing here can widen anything:
 * the risk level, the confirmation requirement, the audit entry and the revoke path come from the
 * tool's own declaration and are applied by the engine, downstream and unconditionally. The worst a bad
 * route can do is propose a call that is then refused.
 *
 * <p><b>How a deployment takes the seam.</b> The runtime registers its own router through
 * {@code @ConditionalOnMissingBean}, so declaring one {@code ToolCallPlanner} bean suppresses it — no
 * {@code @Primary}, no edit to the runtime. This class is that bean's body, not its registration: which
 * phase it is registered in is the deployment's to get right, because a scanned bean would take the
 * single planner slot ahead of a model-backed planner.
 *
 * <p><b>Where the model goes is the deployment's call, and that is why the fallback is a parameter.</b>
 * A model-backed planner is registered the same conditional way, so the very deployment that declares
 * routes suppresses it too: taking the seam and reaching a configured model cannot both be true through
 * registration alone. The constructor is the way out — a deployment passes what its routes should ask
 * next, and composes the model behind them (the runtime's own rules can stay in front of it). Passing
 * {@code new RuleBasedPlanner()} is the arrangement this class used to hard-code; both shapes are the
 * same seam, and neither can widen anything: a plan is still only a proposal.
 *
 * 部署方的规划器，形状是两个宿主**各自独立**走到的：**先走自己的路由，运行时的规则路由器最后**。
 *
 * <p><b>这是上提来的、不是发明的。</b>两个嵌入本运行时的宿主各自写了自己的规划器 —— 类名不同、**形状相同**：
 * 都 `implements ToolCallPlanner`、都持一份 {@link ModuleRoute} 列表、都回落 `new RuleBasedPlanner()`，
 * 且 `plan()` 都是「先问自己的路由、再把消息交给运行时的路由器」。**次序才是实质**：模块的短语比运行时样例模块的
 * 关键词**更具体**，所以部署方自己的路由**先说**，而运行时的规则**仍是地板、不是竞争对手**。
 *
 * <p><b>运行时的规则是被**委托**的，不是被**抄**的。</b>兜底在这里实例化、**最后**被问，所以样例模块那几条路由
 * 的行为**一字不变**。抄一份等于给路由造第二个书写处，两者迟早漂移。
 *
 * <p><b>规划器决定**调什么**，从不决定**能不能跑**。</b>这里没有任何东西能放宽什么：风险级、是否需确认、审计
 * 记录与撤销路径都来自**工具自己的声明**，由引擎在下游**无条件**施加。一条坏路由最坏也就是提议一个**随后被拒**
 * 的调用。
 *
 * <p><b>部署方怎么接过这条缝。</b>运行时用 `@ConditionalOnMissingBean` 注册它自己的路由器，所以**声明一个**
 * `ToolCallPlanner` bean 就把它顶掉 —— 不需要 `@Primary`、不需要改运行时。本类是**那个 bean 的身子、不是它的
 * 注册**：注册在**哪个阶段**是部署方要弄对的事，因为被扫描的 bean 会**抢在**模型驱动的规划器之前占住那唯一的槽。
 *
 * <p><b>模型排在哪儿是部署方的决定 —— 兜底做成参数就是为了这件事。</b>模型驱动的规划器**同样**是按条件注册的，
 * 于是**声明了路由的那个部署也把它顶掉了**：靠注册本身，「占住这条缝」与「走到一个配了的模型」**不可能同时成立**。
 * 出口就是构造参数 —— 部署方传「路由之后该问谁」，把模型排在路由之后（运行时的规则也可以仍在它前面）。传
 * `new RuleBasedPlanner()` 就是本类从前写死的那个安排；两种形状**同一条缝**，而两者都放宽不了任何东西：
 * 一个计划**仍然只是提议**。
 */
public abstract class ModuleRoutePlanner implements ToolCallPlanner {

    private final ToolCallPlanner fallback;

    /** The runtime's own router behind the deployment's routes — the arrangement both hosts arrived at. */
    protected ModuleRoutePlanner() {
        this(new RuleBasedPlanner());
    }

    /**
     * The same, with the deployment naming what its routes should ask next. A model-backed planner is the
     * reason this exists: taking the seam suppresses the model-backed one, so a deployment that wants both
     * has to put the model here rather than wait for it to arrive as a bean.
     *
     * <p>A null is refused rather than quietly meaning "no fallback": a deployment that wants its routes
     * asked and nothing else can say so with {@code (message, context) -> Optional.empty()}, and one that
     * meant the runtime's rules can say that too.
     *
     * 同上，只是由**部署方**点名「路由之后该问谁」。模型驱动的规划器正是它存在的原因：**占住这条缝就会把模型
     * 那个也顶掉**，所以「两个都要」的部署得把模型放在这里，而不是等它作为一个 bean 自己到来。
     *
     * <p>null 一律**拒掉**，而不是悄悄当作「没有兜底」：「只要自己的路由、别的都不要」用
     * `(message, context) -> Optional.empty()` 说得出来，「要运行时的规则」也说得出来。
     */
    protected ModuleRoutePlanner(ToolCallPlanner fallback) {
        this.fallback = Objects.requireNonNull(fallback,
                "the fallback asked after the routes: pass RuleBasedPlanner if that is what you mean");
    }

    /** The routes this deployment owns, in the order they should be asked. */
    protected abstract List<ModuleRoute> routes();

    /**
     * The deployment's routes first, its fallback last — and never nothing: the fallback answers
     * {@link Optional#empty()} when it recognises nothing, which is a proposal's absence rather than a
     * refusal.
     *
     * <p>The turn's message is handed to the route inside the context as well, because the runtime's
     * context carries only what the request declared, and a route that fills a write's arguments from
     * what the user typed has nothing else to read. Declared rather than hidden — a route that ignores
     * it is unaffected.
     *
     * 部署方自己的路由在前、它的**兜底在后** —— 而且**从不无所答**：兜底认不出来时答
     * {@link Optional#empty()}，那是**没有提议**、不是**拒绝**。
     *
     * <p>本轮的消息也**放进上下文里**交给路由：运行时的上下文只带**请求声明过的**东西，而「从用户所写里填一次
     * 写的参数」的路由没有别的可读。这是**声明出来**的、不是藏起来的 —— 用不到它的路由不受影响。
     */
    @Override
    public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
        String text = message == null ? "" : message;
        Map<String, Object> withMessage = new LinkedHashMap<>();
        if (context != null) {
            withMessage.putAll(context);
        }
        withMessage.put("message", text);
        for (ModuleRoute route : routes()) {
            for (String trigger : route.triggers()) {
                if (!text.isEmpty() && text.contains(trigger)) {
                    return Optional.of(new IntentPlan(route.toolName(),
                            new LinkedHashMap<>(route.args(withMessage))));
                }
            }
        }
        return fallback.plan(message, context);
    }
}
