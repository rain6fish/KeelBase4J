// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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
 *
 * <p>Two more entries exist for the mode where the caller is deliberately <em>not</em> that person —
 * a high-impact action answered by a second one (ADR-0018). They are separate paths rather than a
 * flag on this one because they answer a different question, and the engine keeps them apart the same
 * way: what decides which rows a path may move is the row's mode, not what the caller passed.
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
        String decision = decisionOf(request);
        if (ConfirmationLifecycle.APPROVE.equals(decision)) {
            return engine.approve(token, principal);
        }
        return engine.decline(token, principal);
    }

    /**
     * Answer an approval confirmation — the second person a high-impact action waits for (ADR-0018).
     * Administrators only.
     *
     * <p>Approving is refused when the caller is the row's initiator; declining is not, because
     * withdrawing one's own request is not a self-approval.
     */
    @PostMapping("/ai/confirmations/{token}/approve-by")
    public ExecutionOutcome approveBy(@PathVariable String token, @RequestBody DecisionRequest request) {
        return engine.decideApproval(token, requireManager(), decisionOf(request));
    }

    /**
     * Run an approved-but-not-succeeded confirmation again — the entry an attempt that died without
     * recording a result needs (ADR-0018). Administrators only.
     */
    @PostMapping("/ai/confirmations/{token}/retry-execution")
    public ExecutionOutcome retryExecution(@PathVariable String token) {
        return engine.retryExecution(token, requireManager());
    }

    /** Answering somebody else's high-impact action is an administrative act. */
    private Principal requireManager() {
        Principal principal = principals.current();
        if (!principal.isManager()) {
            throw new AccessDeniedException("answering an approval is for administrators");
        }
        return principal;
    }

    private static String decisionOf(DecisionRequest request) {
        try {
            return ConfirmationLifecycle.normalizeDecision(request.decision());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
