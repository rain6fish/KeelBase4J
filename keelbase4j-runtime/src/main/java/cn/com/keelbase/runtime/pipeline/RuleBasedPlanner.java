// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The default planner: a deterministic router over the message.
 *
 * <p>It exists so the trust loop can be demonstrated without a model — the same reason the reference
 * ships a demo provider. Routing by rules is the least interesting part of an AI application and the
 * part most likely to make a test flaky; keeping it out of the tests' way means a failure here is
 * about governance rather than about what a model decided today.
 *
 * <p>A deployment that wants a model replaces this bean. Nothing else changes, because the runtime
 * never asks who planned a call — only what it was.
 */
@Component
public class RuleBasedPlanner implements ToolCallPlanner {

    @Override
    public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
        String text = message == null ? "" : message;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("customerId", context.get("customerId"));

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
