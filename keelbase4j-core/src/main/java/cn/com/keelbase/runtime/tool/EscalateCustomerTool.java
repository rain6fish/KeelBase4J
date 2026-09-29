// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * High-impact write tool (R4) — a second person has to approve it (ADR-0018).
 *
 * <p>It exists so the mode is <em>reachable</em>. The runtime's other two sample tools are both
 * answerable by their own caller (an R1 read executes, an R3 write waits for the operator), so a gate
 * branch for "somebody else must decide" would have been code nothing could exercise or demonstrate.
 *
 * <p>What it does is deliberately ordinary — it records an escalation as a follow-up — because the
 * gate is the interesting part, not the errand. It creates a soft-deletable follow-up, so a revoke
 * compensates it locally like any other write.
 */
@Component
public class EscalateCustomerTool implements AiTool {

    /** The note the escalation writes; the tool's whole business meaning for this sample. */
    private static final String NOTE = "Escalated for management review";

    private final CustomerRepository customers;
    private final FollowUpRepository followUps;
    private final OwnershipGuard guard;

    public EscalateCustomerTool(
            CustomerRepository customers, FollowUpRepository followUps, OwnershipGuard guard) {
        this.customers = customers;
        this.followUps = followUps;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "escalate_customer";
    }

    @Override
    public String description() {
        return "Escalate a customer to management review.";
    }

    @Override
    public String riskLevel() {
        return "R4";
    }

    @Override
    public String resultType() {
        return "follow_up";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        Long customerId = AnalyzeCustomerRiskTool.asLong(args.get("customerId"));
        if (customerId == null) {
            return ToolResult.fail("customerId is required and must be a number");
        }
        Customer customer = customers.findById(customerId).orElse(null);
        if (customer == null) {
            return ToolResult.fail("customer not found: " + customerId);
        }
        guard.requireAccess(principal, customer, "Customer", PermissionAuthorizer.ACTION_READ);

        FollowUp saved = followUps.save(new FollowUp(customerId, NOTE, null, principal.userId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("customerId", saved.getCustomerId());
        out.put("note", saved.getNote());
        return ToolResult.ok(out);
    }
}
