// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.AuthorizationReasons;
import cn.com.keelbase.protocol.GovernanceBinding;
import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Turns a tool's declared risk level — and the governance policy in force — into a gate decision.
 *
 * <p>The risk level alone is the frozen protocol binding ({@link RiskLevel} strategy table +
 * {@link GovernanceBinding} outcome table): the same binding the vectors pin, so the Java runtime makes
 * the same decision the reference does. On top of it sit the policy's per-tool overrides, which is
 * where a deployment narrows or widens a gate without a code change — and which is why a written policy
 * is enforced here rather than merely stored.
 *
 * <p><b>The order matters, and it is the reference's.</b> R5 is decided first and the policy never sees
 * it: an irreversible or external tool is refused on its risk level, and a policy cannot relax that.
 * Then a disabled tool and an excluded role refuse; then an explicit gate mode overrides the risk
 * binding; then the legacy boolean, then the risk binding itself.
 *
 * <p>**把工具声明的风险级 —— 以及生效中的治理策略 —— 变成门控决定。**
 *
 * <p>光看风险级，就是冻结的协议绑定（`RiskLevel` 策略表 + `GovernanceBinding` 结果表）：与向量钉住的完全一致，
 * 故 Java 运行时与参照实现做出同一个决定。其上叠着策略的逐工具覆盖，那是**部署方不发版就能收窄或放宽一道闸**的地方
 * —— 也正是「写下的策略在这里被**施加**、而不是仅仅被存着」的原因。
 *
 * <p><b>顺序要紧，且用的是参照实现的顺序。</b> R5 先定，策略根本见不到它：不可逆或对外动作的工具**按风险级被拒**，
 * 策略**不能**把它放宽。然后是禁用的工具与被排除的角色被拒；再是显式的门控档位覆盖风险绑定；最后才是 legacy 布尔、
 * 再是风险绑定本身。
 */
@Service
public class GovernanceService {

    private final GovernancePolicyStore policy;

    public GovernanceService(GovernancePolicyStore policy) {
        this.policy = policy;
    }

    public GateDecision decide(AiTool tool, Principal principal) {
        String strategy = RiskLevel.strategy(tool.riskLevel());
        GovernanceBinding.GateOutcome outcome = GovernanceBinding.gate(strategy);
        if (outcome.blocked()) {
            // R5 is refused on the risk level and the policy is not consulted: a gate that decides
            // before the policy is read cannot be widened by it.
            //
            // R5 按风险级被拒、**不查策略**：在读策略之前就定下的闸，不可能被策略放宽。
            return GateDecision.BLOCK;
        }
        Map<String, Object> override = policy.overrideFor(tool.name());
        if (Boolean.FALSE.equals(override.get("enabled"))) {
            return GateDecision.BLOCK;
        }
        List<String> allowedRoles = allowedRoles(override);
        if (!allowedRoles.isEmpty() && !allowedRoles.contains(principal.role())) {
            return GateDecision.BLOCK;
        }
        String mode = gateMode(override);
        if (mode != null) {
            return switch (mode) {
                case "confirm" -> GateDecision.CONFIRM;
                case "approval" -> GateDecision.REQUIRE_APPROVAL;
                default -> GateDecision.ALLOW;
            };
        }
        if (Boolean.FALSE.equals(override.get("requiresConfirmation"))) {
            return GateDecision.ALLOW;
        }
        if (outcome.requiresApproval()) {
            return GateDecision.REQUIRE_APPROVAL;
        }
        if (outcome.requiresConfirmation() || Boolean.TRUE.equals(override.get("requiresConfirmation"))) {
            return GateDecision.CONFIRM;
        }
        return GateDecision.ALLOW;
    }

    /**
     * Why the gate reached its verdict, in the frozen {@code authorization} vocabulary — so a
     * refusal reaches the caller as structured data rather than a bare sentence.
     *
     * <p>Each check is one this runtime actually ran, so a refusal says which of the three refused it
     * — the risk level, the tool switch or the role allowlist — and a caller reading a trace can tell
     * "the policy disabled this" from "the risk level did". The governance-policy revision travels with
     * it, because a decision is taken <em>under</em> a policy and a reader should be able to say which.
     *
     * 这道闸为什么得到它的结论，用冻结的 `authorization` 词表 —— 于是一次拒绝作为**结构化数据**到达调用方，
     * 而不是一句光秃秃的话。
     *
     * <p>每条检查都是本运行时**真跑过**的那一条，故一次拒绝说得清是三者中哪一个拒的 —— 风险级、工具开关、还是
     * 角色白名单 —— 读轨迹的人分得出「是策略禁用了它」与「是风险级」。治理策略的 revision 亦随之携带，因为一次
     * 决定是在**某一份策略之下**做出的，读的人应当说得清是哪一份。
     */
    public AuthorizationReasons reasons(AiTool tool, Principal principal) {
        String strategy = RiskLevel.strategy(tool.riskLevel());
        GovernanceBinding.GateOutcome outcome = GovernanceBinding.gate(strategy);
        Map<String, Object> override = policy.overrideFor(tool.name());

        boolean enabled = !Boolean.FALSE.equals(override.get("enabled"));
        List<String> allowedRoles = allowedRoles(override);
        boolean roleAllowed = allowedRoles.isEmpty() || allowedRoles.contains(principal.role());

        List<AuthorizationReasons.Check> checks = new ArrayList<>();
        checks.add(new AuthorizationReasons.Check(
                GovernanceBinding.DENY_RISK_POLICY,
                !outcome.blocked(),
                outcome.blocked()
                        ? tool.riskLevel() + " is irreversible or external — refused before any confirmation"
                        : null));
        checks.add(new AuthorizationReasons.Check(
                GovernanceBinding.DENY_TOOL_ENABLED,
                enabled,
                enabled ? null : "disabled by the governance policy"));
        checks.add(new AuthorizationReasons.Check(
                GovernanceBinding.DENY_ROLE_ALLOWED,
                roleAllowed,
                roleAllowed ? null : "restricted to roles " + allowedRoles));

        GateDecision decision = decide(tool, principal);
        return new AuthorizationReasons(tool.name(), tool.riskLevel(), strategy,
                decision == GateDecision.CONFIRM || decision == GateDecision.REQUIRE_APPROVAL,
                checks, new AuthorizationReasons.Policy(policy.currentRevision()));
    }

    /** The explicit gate mode the policy names for a tool, or {@code null} when it names none. */
    private static String gateMode(Map<String, Object> override) {
        Object mode = override.get("mode");
        return mode == null ? null : String.valueOf(mode);
    }

    /** The role allowlist the policy names for a tool — empty means "no role restriction". */
    private static List<String> allowedRoles(Map<String, Object> override) {
        Object roles = override.get("allowedRoles");
        if (!(roles instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
