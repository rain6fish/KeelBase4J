// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.governance.GateDecision;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import cn.com.keelbase.runtime.governance.GovernanceService;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tool inventory: what this runtime can be asked to do, and what governance says about each tool.
 *
 * <p>The administrators' and audit readers' list, answering the frozen {@code ai-tool-inventory} for
 * every registered tool.
 * The governance half is not restated here — it is <em>asked</em>: {@code gateMode}, and the two flags
 * derived from it, all come from the same {@link GovernanceService#decide} call the engine itself makes,
 * so a tool cannot be described one way and gated another.
 *
 * <p><b>{@code enabled} and {@code allowedRoles} come from the governance policy</b> — the same per-tool
 * override store the gate reads the policy's switches from — so the list and the gate cannot disagree
 * about whether a tool is on or who may call it. With no policy written they answer their defaults
 * ({@code true} and an empty list), which is what "nothing was declared" looks like here.
 *
 * <p><b>{@code permissions} answers only what a tool actually declares.</b> A tool that declares
 * {@link AiTool#adminOnly()} gets {@code {"adminOnly": true}}; one that declares nothing gets
 * {@code null}, because the object does not demand it and inventing a permission would put a setting
 * on a console nobody set. The value comes from the same declaration the gate reads, so the list and
 * the gate cannot disagree about who may call a tool.
 *
 * <p>**工具清单**：这个运行时可以被要求做什么，以及治理对每个工具怎么说。
 *
 * <p>这是给管理员与审计读者的列表，对**每一个已注册工具**作答冻结的 {@code ai-tool-inventory}。治理那一半在这里**不复述**
 * —— 它是**去问来的**：{@code gateMode} 与由它派生的两个标志，都来自引擎自己用的那次
 * {@link GovernanceService#decide} 调用，于是一个工具**不可能**被描述成一种样子、却被按另一种样子把关。
 *
 * <p><b>{@code enabled} 与 {@code allowedRoles} 取自治理策略</b> —— 就是那道闸读取策略开关的**同一个逐工具覆盖存储**
 * —— 故这张清单与那道闸**不可能**对「工具开没开、谁能调」各执一词。未写过策略时它们答默认值（{@code true} 与空表），
 * 那就是「什么都没声明」在这里的样子。
 *
 * <p><b>{@code permissions} 只回答工具**真声明**的东西。</b> 声明了 {@link AiTool#adminOnly()} 的工具得到
 * {@code {"adminOnly": true}}；什么都没声明的得到 {@code null} —— 该对象**不要求**它，而编一项权限等于把一项
 * **没人设过**的设置显示到控制台上。这个值取自**闸门读的同一份声明**，故清单与闸门不会对「谁能调它」各执一词。
 */
@RestController
public class ToolInventoryController {

    private final ToolRegistry tools;
    private final GovernanceService governance;
    private final GovernancePolicyStore policy;
    private final CurrentPrincipal principals;
    private final OwnershipGuard guard;

    public ToolInventoryController(ToolRegistry tools, GovernanceService governance,
                                   GovernancePolicyStore policy, CurrentPrincipal principals,
                                   OwnershipGuard guard) {
        this.tools = tools;
        this.governance = governance;
        this.policy = policy;
        this.principals = principals;
        this.guard = guard;
    }

    @GetMapping("/ai/tools")
    public List<Map<String, Object>> inventory() {
        Principal caller = principals.current();
        guard.requireAction(caller, GovernanceReadSubjects.TOOL_INVENTORY,
                PermissionAuthorizer.ACTION_READ);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiTool tool : tools.all()) {
            rows.add(row(tool, caller));
        }
        return rows;
    }

    private Map<String, Object> row(AiTool tool, Principal caller) {
        GateDecision decision = governance.decide(tool, caller);
        String mode = switch (decision) {
            case ALLOW -> "auto";
            case CONFIRM -> "confirm";
            case REQUIRE_APPROVAL -> "approval";
            case BLOCK -> "blocked";
        };
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (ToolParameter parameter : tool.parameters()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", parameter.name());
            p.put("type", parameter.type());
            p.put("required", parameter.required());
            parameters.add(p);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", tool.name());
        row.put("description", tool.description());
        row.put("parameters", parameters);
        // The switch and the role list come from the policy the gate itself reads, so a tool the policy
        // disabled is not shown as enabled here; with no policy written both answer their defaults.
        //
        // 开关与角色表取自**那道闸自己读的**那份策略，故被策略禁用的工具在这里**不会**显示成开着；未写过策略时
        // 两者都答默认值。
        Map<String, Object> override = policy.overrideFor(tool.name());
        row.put("enabled", override.getOrDefault("enabled", true));
        // Read off the effective gate rather than off the declaration, the way the reference does: a
        // console showing "needs confirmation" should show it because that is what will happen.
        //
        // 从**生效的闸**读出、而不是从声明读出 —— 与参照实现一致：控制台显示「需要确认」，就该因为**那正是会
        // 发生的事**。
        row.put("requiresConfirmation", decision == GateDecision.CONFIRM
                || decision == GateDecision.REQUIRE_APPROVAL);
        row.put("requiresApproval", decision == GateDecision.REQUIRE_APPROVAL);
        row.put("gateMode", mode);
        row.put("allowedRoles", override.getOrDefault("allowedRoles", List.of()));
        row.put("permissions", tool.adminOnly() ? Map.of("adminOnly", true) : null);
        row.put("riskLevel", tool.riskLevel());
        row.put("riskStrategy", RiskLevel.strategy(tool.riskLevel()));
        row.put("revokeClass", tool.revokeClass());
        return row;
    }
}
