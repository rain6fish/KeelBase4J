// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The default planner: a deterministic router over the message.
 *
 * <p>It routes on words and on the context the runtime hands it; it does <em>not</em> work out which
 * customer a conversation is about. That is conversation state — the transcript lives with the turn
 * service, which resolves the reference and passes the id here (see
 * {@code ChatTurnService.mentionedCustomer}). One reader for one rule.
 *
 * <p>It exists so the trust loop can be demonstrated without a model — the same reason the reference
 * ships a demo provider. Routing by rules is the least interesting part of an AI application and the
 * part most likely to make a test flaky; keeping it out of the tests' way means a failure here is
 * about governance rather than about what a model decided today.
 *
 * <p>It is registered by {@link RuleBasedPlannerAutoConfiguration}, and that registration is
 * conditional — the moment a deployment declares a {@link ToolCallPlanner} of its own, this one steps
 * aside. Deliberately not a {@code @Component}: as one it was unconditional, which forced every
 * adopter to out-rank it with {@code @Primary}. A fallback should yield, not have to be shouted over.
 */
public class RuleBasedPlanner implements ToolCallPlanner {

    @Override
    public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
        String text = message == null ? "" : message;
        // Every tool this router knows acts on a customer or a project, and the caller has already
        // resolved which row this conversation is about (see ChatTurnService.mentionedCustomer).
        // Without it there is nothing to propose: saying so is the honest answer, where handing a
        // tool a null id fails at the tool and reaches the caller as a server error they cannot act on.
        //
        // The context key is the generic slot; the argument names below belong to each sample tool.
        //
        // 本路由器认识的每个工具都作用于一个客户或一个项目，而这条会话说的是哪一行，调用方已经解析好了
        // （见 `ChatTurnService.mentionedCustomer`）。没有它就无从提议：如实说出来才是对的答案，而把 null
        // id 交给工具会在工具处失败、以调用方无法处置的服务器错误到达。上下文那个键是**通用槽位**；
        // 下面的参数名各属对应的样例工具。
        if (!(context.get("targetId") instanceof Number target)) {
            return Optional.empty();
        }
        long targetId = target.longValue();

        // "项目" is what tells the two sample domains apart. The CRM messages the console sends do
        // not carry it, and a PM message that did not carry it would be routed as CRM — which is the
        // honest limit of a deterministic router, stated here rather than hidden behind a guess.
        //
        // 「项目」是把两个示例域分开的那个词。控制台发的 CRM 消息不带它，而不带它的 PM 消息会被当成 CRM
        // 路由 —— 这是确定性路由器诚实的边界，写在这里，不藏在猜测后面。
        if (text.contains("项目")) {
            Map<String, Object> pmArgs = new LinkedHashMap<>();
            pmArgs.put("projectId", targetId);
            if (text.contains("删除")) {
                return Optional.of(new IntentPlan("delete_project", pmArgs));
            }
            if (text.contains("升级") || text.contains("上报")) {
                return Optional.of(new IntentPlan("escalate_project", pmArgs));
            }
            if (text.contains("任务") || text.contains("创建") || text.contains("跟进")) {
                pmArgs.put("title", text);
                return Optional.of(new IntentPlan("create_pm_task", pmArgs));
            }
            if (text.contains("风险") || text.contains("分析") || text.contains("延期")) {
                return Optional.of(new IntentPlan("analyze_project_risk", pmArgs));
            }
            return Optional.empty();
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("customerId", targetId);
        if (text.contains("风险") || text.contains("分析")) {
            return Optional.of(new IntentPlan("analyze_customer_risk", args));
        }
        if (text.contains("升级") || text.contains("上报")) {
            return Optional.of(new IntentPlan("escalate_customer", args));
        }
        if (text.contains("跟进") || text.contains("创建")) {
            args.put("note", text);
            return Optional.of(new IntentPlan("create_followup", args));
        }
        return Optional.empty();
    }
}
