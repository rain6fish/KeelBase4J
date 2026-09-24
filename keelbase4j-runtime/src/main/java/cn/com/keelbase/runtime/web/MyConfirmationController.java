// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.engine.OutOfBandDecision;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator's own decisions, taken outside the conversation (ADR-0015).
 *
 * <p>This is the service side of the Action Center: a confirmation whose in-conversation wait has ended
 * is still its operator's to decide, for as long as the offline window lasts. It is a separate route
 * from {@code /ai/confirmations/{token}} because the <em>guards</em> differ, not the transitions: there,
 * deciding something already decided is a conflict; here it is a no-op.
 *
 * <p>The list of what is waiting is <b>not</b> served yet — the frozen item shape requires a mode and an
 * execution state this runtime does not have — so this route answers a caller that already knows its
 * token.
 */
@RestController
public class MyConfirmationController {

    private final CurrentPrincipal principals;
    private final GovernedExecutionEngine engine;

    public MyConfirmationController(CurrentPrincipal principals, GovernedExecutionEngine engine) {
        this.principals = principals;
        this.engine = engine;
    }

    @PostMapping("/ai/my/confirmations/{token}/decide")
    public OutOfBandDecision decide(@PathVariable String token, @RequestBody DecisionRequest request) {
        Principal principal = principals.current();
        String decision;
        try {
            decision = ConfirmationLifecycle.normalizeDecision(request.decision());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return engine.decideOutOfBand(token, principal, decision);
    }
}
