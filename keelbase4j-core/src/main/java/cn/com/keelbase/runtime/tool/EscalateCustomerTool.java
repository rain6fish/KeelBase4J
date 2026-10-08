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
import java.util.List;
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
 *
 * <p>**高影响写工具（R4）——要第二个人批准**（ADR-0018）。
 *
 * <p>它存在是为了让这个模式**可达**。本运行时另外两个示例工具都由**它们自己的调用者**拍板（R1 读直接执行、
 * R3 写等操作者本人），所以「**必须由别人来裁决**」那条门控分支会成为一段没有任何东西能走到、也演示不了的
 * 代码。
 *
 * <p>它做的事**故意很平常**——把一次升级登记成一条跟进记录——因为有意思的是那道门，不是这桩差事。它写的
 * 跟进记录可软删，所以撤销与任何其他写一样，走本地补偿。
 */
@Component
public class EscalateCustomerTool implements AiTool {

    /**
     * The note the escalation writes; the tool's whole business meaning for this sample.
     *
     * <p>升级所写的那条备注；对这个样例来说，这就是它的全部业务含义。
     */
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
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("customerId", ToolParameter.NUMBER,
                "the customer to escalate"));
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
