// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Irreversible tool (R5 → blocked) — it exists so the block branch is reachable and demonstrable.
 *
 * <p>Every gate above R4 is a claim about a path; without a tool declaring R5, the runtime's block
 * branch is code nothing can exercise. Deleting a project is irreversible, so it is the honest
 * example, and the gate refuses it on the risk level alone: a governance policy cannot widen an R5
 * tool, and {@link #execute} is never reached.
 *
 * <p>Its result type is declared so the inventory counts it as a write rather than as a read. It is
 * still not locally compensable — an irreversible action has nothing to compensate — and the health
 * report deliberately does not warn about that, because a write that can never run has no side effect
 * to leave uncompensated.
 *
 * <p>不可逆工具（R5 → 阻断）—— 它存在是为了让阻断分支**可达、可演示**。
 *
 * <p>R4 以上的每一道门都是关于一条路径的主张；没有一个声明 R5 的工具，运行时的阻断分支就是一段没有任何东西
 * 走得到的代码。删除项目是不可逆的，故它是诚实的例子，而闸**按风险级**拒绝它：治理策略放宽不了 R5 工具，
 * {@link #execute} 永远到不了。
 *
 * <p>它声明了结果类型，好让清单把它算成**写**而不是读。它仍然**不可本地补偿** —— 不可逆动作没有什么可补偿
 * —— 而健康报告**刻意不**为此告警：一次永远跑不了的写，没有会留下的未补偿副作用。
 */
@Component
public class DeleteProjectTool implements AiTool {

    @Override
    public String name() {
        return "delete_project";
    }

    @Override
    public String description() {
        return "Delete a project permanently (irreversible; the risk policy blocks it).";
    }

    @Override
    public String riskLevel() {
        return "R5";
    }

    @Override
    public String resultType() {
        return "pm_project";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("projectId", ToolParameter.NUMBER,
                "the project to delete"));
    }

    /**
     * Unreachable by construction: {@code GovernanceService} decides R5 before the policy is read and
     * the engine never executes a blocked call. Refusing here as well keeps the contract honest if a
     * future path ever asks.
     *
     * 按构造不可达：{@code GovernanceService} 在读策略之前就定下 R5，而引擎从不执行被阻断的调用。这里也
     * 拒绝，是为了万一将来有路径问到它时，契约仍然诚实。
     */
    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        return ToolResult.fail("delete_project is R5 and is never executed; the gate blocks it");
    }
}
