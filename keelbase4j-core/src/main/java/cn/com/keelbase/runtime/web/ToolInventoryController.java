// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.governance.GateDecision;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tool inventory: what this runtime can be asked to do, and what governance says about each tool.
 *
 * <p>An administrator's list, answering the frozen {@code ai-tool-inventory} for every registered tool.
 * The governance half is not restated here — it is <em>asked</em>: {@code gateMode}, and the two flags
 * derived from it, all come from the same {@link GovernanceService#decide} call the engine itself makes,
 * so a tool cannot be described one way and gated another.
 *
 * <p><b>Three fields have no source in this runtime, and they answer their defaults rather than a
 * guess.</b> The reference fills {@code enabled}, {@code allowedRoles} and {@code permissions} from a
 * <em>governance policy</em> — a per-tool override store this runtime does not have. A registered tool
 * is therefore {@code enabled}, its role list is empty, and its permissions are {@code null}, which is
 * what "nothing was declared" looks like here. Inventing values for a store that does not exist is the
 * one thing this list must not do: a console reading it would then show a policy nobody set.
 *
 * <p>**工具清单**：这个运行时可以被要求做什么，以及治理对每个工具怎么说。
 *
 * <p>这是给管理员的列表，对**每一个已注册工具**作答冻结的 {@code ai-tool-inventory}。治理那一半在这里**不复述**
 * —— 它是**去问来的**：{@code gateMode} 与由它派生的两个标志，都来自引擎自己用的那次
 * {@link GovernanceService#decide} 调用，于是一个工具**不可能**被描述成一种样子、却被按另一种样子把关。
 *
 * <p><b>有三个字段在本运行时没有来源，它们答默认值而不是猜一个。</b> 参照实现用**治理策略**（一个本运行时没有的
 * 逐工具覆盖存储）来填 {@code enabled} / {@code allowedRoles} / {@code permissions}。故这里：已注册的工具就是
 * {@code enabled}、角色列表为空、权限为 {@code null} —— 那就是「什么都没声明」在这里的样子。**为一个不存在的存储
 * 编造取值**，是这张列表唯一不能做的事：读到它的控制台会因此显示一份**没人设过**的策略。
 */
@RestController
public class ToolInventoryController {

    private final ToolRegistry tools;
    private final GovernanceService governance;
    private final CurrentPrincipal principals;

    public ToolInventoryController(ToolRegistry tools, GovernanceService governance,
                                   CurrentPrincipal principals) {
        this.tools = tools;
        this.governance = governance;
        this.principals = principals;
    }

    @GetMapping("/ai/tools")
    public List<Map<String, Object>> inventory() {
        Principal caller = principals.current();
        if (!caller.isManager()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "the tool inventory is readable by an administrator");
        }
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
        row.put("enabled", true);
        // Read off the effective gate rather than off the declaration, the way the reference does: a
        // console showing "needs confirmation" should show it because that is what will happen.
        //
        // 从**生效的闸**读出、而不是从声明读出 —— 与参照实现一致：控制台显示「需要确认」，就该因为**那正是会
        // 发生的事**。
        row.put("requiresConfirmation", decision == GateDecision.CONFIRM
                || decision == GateDecision.REQUIRE_APPROVAL);
        row.put("requiresApproval", decision == GateDecision.REQUIRE_APPROVAL);
        row.put("gateMode", mode);
        row.put("allowedRoles", List.of());
        row.put("permissions", null);
        row.put("riskLevel", tool.riskLevel());
        row.put("riskStrategy", RiskLevel.strategy(tool.riskLevel()));
        row.put("revokeClass", tool.revokeClass());
        return row;
    }
}
