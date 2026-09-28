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
 * Write tool (R3 → confirmation). Never executed before a human approves. Creates a soft-deletable
 * {@link FollowUp}, so a revoke can compensate it locally ({@code local_compensate}).
 */
@Component
public class CreateFollowUpTool implements AiTool {

    private final CustomerRepository customers;
    private final FollowUpRepository followUps;
    private final OwnershipGuard guard;

    public CreateFollowUpTool(
            CustomerRepository customers, FollowUpRepository followUps, OwnershipGuard guard) {
        this.customers = customers;
        this.followUps = followUps;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "create_followup";
    }

    @Override
    public String description() {
        return "Create a follow-up note on a customer (requires confirmation).";
    }

    @Override
    public String riskLevel() {
        return "R3";
    }

    @Override
    public String resultType() {
        return "follow_up";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        Long customerId = AnalyzeCustomerRiskTool.asLong(args.get("customerId"));
        String note = args.get("note") == null ? "" : String.valueOf(args.get("note"));
        String dueDate = args.get("dueDate") == null ? null : String.valueOf(args.get("dueDate"));

        // Same boundary rule as the read tool: a planner's arguments are a proposal, not a guarantee.
        // findById(null) would throw and surface as a 500; a refused write is a governed outcome.
        if (customerId == null) {
            return ToolResult.fail("customerId is required and must be a number");
        }
        Customer customer = customers.findById(customerId).orElse(null);
        if (customer == null) {
            return ToolResult.fail("customer not found: " + customerId);
        }
        guard.requireAccess(principal, customer, "Customer", PermissionAuthorizer.ACTION_READ);

        FollowUp saved = followUps.save(new FollowUp(customerId, note, dueDate, principal.userId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("customerId", saved.getCustomerId());
        out.put("note", saved.getNote());
        return ToolResult.ok(out);
    }
}
