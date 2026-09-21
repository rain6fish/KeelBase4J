// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The default planner: a deterministic router over the message.
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

    /**
     * The customer the caller is looking at, as the console writes it into the first message of a
     * conversation: {@code 当前客户「Acme」（ID 1）。…}.
     *
     * <p>It is in the message rather than in a field because that is the product's convention — no
     * frontend sends a customer id, and a model is expected to read the reference the way it reads
     * anything else. Without a model, this router is what reads it; the alternative is proposing a
     * write with nobody to act on.
     */
    private static final Pattern CUSTOMER_MARKER =
            Pattern.compile("[（(]\\s*ID\\s*(\\d+)\\s*[）)]", Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<IntentPlan> plan(String message, Map<String, Object> context) {
        String text = message == null ? "" : message;
        Long customerId = customerId(text, context.get("customerId"));
        // Every tool this router knows acts on a customer, so without one there is nothing it can
        // propose. Saying so is the honest answer; passing a null id through would fail at the tool
        // and surface as a server error the caller cannot act on.
        if (customerId == null) {
            return Optional.empty();
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("customerId", customerId);
        if (text.contains("风险") || text.contains("分析")) {
            return Optional.of(new IntentPlan("analyze_customer_risk", args));
        }
        if (text.contains("跟进") || text.contains("创建")) {
            args.put("note", text);
            return Optional.of(new IntentPlan("create_followup", args));
        }
        return Optional.empty();
    }

    /** The caller's own id when it sent one — it is explicit, so it wins — else the one in the text. */
    private static Long customerId(String text, Object given) {
        if (given instanceof Number number) {
            return number.longValue();
        }
        Matcher marker = CUSTOMER_MARKER.matcher(text);
        return marker.find() ? Long.valueOf(marker.group(1)) : null;
    }
}
