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
        // Every tool this router knows acts on a customer, and the caller has already resolved which
        // one this conversation is about (see ChatTurnService.mentionedCustomer). Without it there is
        // nothing to propose: saying so is the honest answer, where handing a tool a null id fails at
        // the tool and reaches the caller as a server error they cannot act on.
        if (!(context.get("customerId") instanceof Number customer)) {
            return Optional.empty();
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("customerId", customer.longValue());
        if (text.contains("风险") || text.contains("分析")) {
            return Optional.of(new IntentPlan("analyze_customer_risk", args));
        }
        if (text.contains("跟进") || text.contains("创建")) {
            args.put("note", text);
            return Optional.of(new IntentPlan("create_followup", args));
        }
        return Optional.empty();
    }
}
