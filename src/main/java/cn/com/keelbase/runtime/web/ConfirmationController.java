// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolve a pending confirmation: approve (then execute) or decline (nothing written).
 *
 * <p>Whoever approves is the authenticated caller — the running engine checks the confirmation was
 * raised for that same principal, so a valid token cannot be used to approve someone else's pending
 * write.
 */
@RestController
public class ConfirmationController {

    private final CurrentPrincipal principals;
    private final GovernedExecutionEngine engine;

    public ConfirmationController(CurrentPrincipal principals, GovernedExecutionEngine engine) {
        this.principals = principals;
        this.engine = engine;
    }

    @PostMapping("/ai/confirmations/{token}")
    public ExecutionOutcome decide(@PathVariable String token, @RequestBody DecisionRequest request) {
        Principal principal = principals.current();
        String decision;
        try {
            decision = ConfirmationLifecycle.normalizeDecision(request.decision());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (ConfirmationLifecycle.APPROVE.equals(decision)) {
            return engine.approve(token, principal);
        }
        return engine.decline(token, principal);
    }
}
