// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AI entry point. A deterministic intent router maps a message to a tool, then hands off to
 * the governed engine — so an AI request and a direct tool call run through exactly the same
 * boundary. (A real deployment swaps the router for a model; the boundary does not change.)
 *
 * <p>The acting identity comes from the authenticated request rather than the body, and an
 * unauthenticated caller never reaches this method at all.
 */
@RestController
public class ChatController {

    private final CurrentPrincipal principals;
    private final GovernedExecutionEngine engine;

    public ChatController(CurrentPrincipal principals, GovernedExecutionEngine engine) {
        this.principals = principals;
        this.engine = engine;
    }

    @PostMapping("/ai/chat")
    public ExecutionOutcome chat(@RequestBody ChatRequest request) {
        Principal principal = principals.current();
        String message = request.message() == null ? "" : request.message();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("customerId", request.customerId());

        String tool;
        if (message.contains("风险") || message.contains("分析")) {
            tool = "analyze_customer_risk";
        } else if (message.contains("跟进") || message.contains("创建")) {
            tool = "create_followup";
            args.put("note", message);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot route message to a tool");
        }
        return engine.execute(tool, args, principal);
    }
}
