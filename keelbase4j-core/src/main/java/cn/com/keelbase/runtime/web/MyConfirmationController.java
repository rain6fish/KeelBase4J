// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.engine.OutOfBandDecision;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
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
                .map(row -> item(row, now, offlineTtlMillis))
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

    /**
     * One record in the shape the console's own model requires ({@code my-confirmation-item} v2).
     *
     * <p>A {@link LinkedHashMap} rather than a typed carrier, for the reason the effect list uses one:
     * several of these fields are legitimately null and must still be <em>present</em>.
     *
     * <p>Fields this runtime has no concept for are present and null — a {@code summary} needs the
     * presentation layer the reference has and this runtime does not, an {@code impact} preview
     * likewise, and a {@code run} batch belongs to a mode this runtime never produces. {@code expiresAt}
     * is the exception: the contract gives it to pending rows and omits it on terminal ones, and it is
     * not nullable there, so it is left out rather than sent as null.
     *
     * <p>{@code mode} is always {@code immediate}: this runtime creates R3 confirmations, the operator's
     * own, and inventing an approval or run mode it cannot produce would be a shape nothing backs.
     */
    private Map<String, Object> item(ConfirmationRequest row, Instant now, long offlineTtlMillis) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("token", row.getToken());
        item.put("toolName", row.getToolName());
        item.put("summary", null);
        item.put("arguments", parseArgs(row.getArgsJson()));
        item.put("mode", "immediate");
        item.put("riskLevel", row.getRiskLevel());
        item.put("status", row.getStatus());
        item.put("impact", null);
        item.put("revokeClass", revokeClassOf(row));
        item.put("run", null);
        item.put("createdAt", row.getCreatedAt().toString());
        item.put("decidedAt", row.getDecidedAt() == null ? null : row.getDecidedAt().toString());
        if (ConfirmationLifecycle.PENDING.equals(row.getStatus())) {
            item.put("expiresAt", row.getCreatedAt().plusMillis(offlineTtlMillis).toString());
        }
        item.put("executionState", ExecutionAxis.derive(row, now));
        item.put("executedAt", row.getExecutedAt() == null ? null : row.getExecutedAt().toString());
        item.put("executionError", row.getExecutionError());
        return item;
    }

    /**
     * The tool's own revoke class, or null when this deployment no longer declares that tool — the
     * contract allows either, and guessing on a row whose tool is gone is not an option.
     */
    private String revokeClassOf(ConfirmationRequest row) {
        return tools.all().stream()
                .filter(tool -> tool.name().equals(row.getToolName()))
                .map(AiTool::revokeClass)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String argsJson) {
        return (Map<String, Object>) Json.parse(argsJson);
    }
}
