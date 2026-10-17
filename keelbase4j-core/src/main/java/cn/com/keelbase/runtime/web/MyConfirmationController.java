// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.engine.OutOfBandDecision;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator's own confirmations: what is waiting on them, and their decision taken outside the
 * conversation (ADR-0015, ADR-0016).
 *
 * <p>This is the service side of the Action Center, and it is one route family rather than an extension
 * of {@code /ai/confirmations/{token}} because the <em>guards</em> differ, not the transitions: there,
 * deciding something already decided is a conflict; here it is a no-op. The list exists so that a
 * confirmation can be found without having been handed its token — and it answers the frozen
 * {@code my-confirmation-item} shape, so a console that reads the reference can read this.
 *
 * <p><b>What this family still does not serve</b>: the operator's side-effect list
 * ({@code /ai/my/tool-effects}) and the conversation trace. Those are other surfaces, and their absence
 * is why the Action Center's pages get part of what they ask for here and not all of it.
 */
@RestController
public class MyConfirmationController {

    private final CurrentPrincipal principals;
    private final GovernedExecutionEngine engine;
    private final ConfirmationStore confirmations;
    private final ToolRegistry tools;

    public MyConfirmationController(CurrentPrincipal principals, GovernedExecutionEngine engine,
                                    ConfirmationStore confirmations, ToolRegistry tools) {
        this.principals = principals;
        this.engine = engine;
        this.confirmations = confirmations;
        this.tools = tools;
    }

    /**
     * This operator's own confirmation records, newest first (ADR-0016). The caller's own rows only —
     * whose rows these are is part of the question, not a filter a caller could lift.
     */
    @GetMapping("/ai/my/confirmations")
    public List<Map<String, Object>> mine(@RequestParam(required = false) String status) {
        Principal principal = principals.current();
        Instant now = Instant.now();
        long offlineTtlMillis = ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS;
        return confirmations.mine(principal.userId(), status, now, offlineTtlMillis).stream()
                .map(row -> ConfirmationItemView.item(row, now, offlineTtlMillis, tools))
                .toList();
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
