// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AI entry point. A planner decides what to call, then hands off to the governed engine — so an
 * AI request and a direct tool call run through exactly the same boundary.
 *
 * <p>This controller has no opinion about how the decision was reached, and that is the point: the
 * planner is a seam ({@link ToolCallPlanner}), and a model-driven pipeline plugs into it without this
 * boundary moving. What it decided is gated downstream exactly as before — a planner proposes, the
 * runtime disposes.
 *
 * <p>The acting identity comes from the authenticated request rather than the body, and an
 * unauthenticated caller never reaches this method at all.
 */
@RestController
public class ChatController {

    private final CurrentPrincipal principals;
    private final ToolCallPlanner planner;
    private final GovernedExecutionEngine engine;

    public ChatController(CurrentPrincipal principals, ToolCallPlanner planner,
                          GovernedExecutionEngine engine) {
        this.principals = principals;
        this.planner = planner;
        this.engine = engine;
    }

    @PostMapping("/ai/chat")
    public ExecutionOutcome chat(@RequestBody ChatRequest request) {
        Principal principal = principals.current();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("customerId", request.customerId());

        IntentPlan plan = planner.plan(request.message(), context)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "cannot route message to a tool"));
        return engine.execute(plan.tool(), plan.args(), principal);
    }
}
