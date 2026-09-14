// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Resolve a pending confirmation: approve (then execute) or decline (nothing written). */
@RestController
public class ConfirmationController {

    private final IdentityResolver identities;
    private final GovernedExecutionEngine engine;

    public ConfirmationController(IdentityResolver identities, GovernedExecutionEngine engine) {
        this.identities = identities;
        this.engine = engine;
    }

    @PostMapping("/ai/confirmations/{token}")
    public ExecutionOutcome decide(
            @PathVariable String token,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody DecisionRequest request) {
        Principal principal = identities.resolve(userId, role);
        String decision = request.decision();
        if ("approve".equals(decision)) {
            return engine.approve(token, principal);
        }
        if ("decline".equals(decision) || "reject".equals(decision)) {
            return engine.decline(token, principal);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be approve or decline");
    }
}
