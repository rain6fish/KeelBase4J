// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read tool (R1 → auto). Deterministic risk analysis — no model dependency, so the trust loop is
 * reproducible. Data is scoped to the caller by the ownership guard.
 */
@Component
public class AnalyzeCustomerRiskTool implements AiTool {

    private final CustomerRepository customers;
    private final OwnershipGuard guard;

    public AnalyzeCustomerRiskTool(CustomerRepository customers, OwnershipGuard guard) {
        this.customers = customers;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "analyze_customer_risk";
    }

    @Override
    public String description() {
        return "Analyze the risk level of a customer (read-only).";
    }

    @Override
    public String riskLevel() {
        return "R1";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        Long customerId = asLong(args.get("customerId"));
        // The tool boundary does not trust the caller's arguments — not even a model planner's, whose
        // output is a proposal, not a guarantee, however firmly the tool schema marks a field required.
        // Without this, a missing or unparseable id reaches findById(null), which Spring Data answers
        // with an exception — and an exception here surfaces as a 500. A tool that was handed nonsense
        // is a governed outcome (a failure the caller can read and act on), not a server fault.
        if (customerId == null) {
            return ToolResult.fail("customerId is required and must be a number");
        }
        Customer customer = customers.findById(customerId).orElse(null);
        if (customer == null) {
            return ToolResult.fail("customer not found: " + customerId);
        }
        guard.requireAccess(principal, customer, "Customer", PermissionAuthorizer.ACTION_READ);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", customer.getId());
        out.put("name", customer.getName());
        out.put("score", "high".equals(customer.getLevel()) ? 10 : "medium".equals(customer.getLevel()) ? 6 : 2);
        out.put("level", switch (customer.getLevel()) {
            case "high" -> "critical";
            case "medium" -> "high";
            default -> "low";
        });
        out.put("reasons", List.of("declared level: " + customer.getLevel()));
        return ToolResult.ok(out);
    }

    /**
     * Parse an id without ever throwing. A value that is absent <em>or</em> unparseable both come back
     * as {@code null}, so the caller has one case to handle instead of two — and {@code "the first
     * one"} from a model planner is the same kind of input as no value at all: something to refuse,
     * not something to crash on.
     */
    static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
