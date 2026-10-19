// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.app.ApplicationProfileProperties;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.governance.GateDecision;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import cn.com.keelbase.runtime.governance.GovernanceService;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.security.CallerAuthenticator;
import cn.com.keelbase.runtime.security.DelegationTokenAuthenticator;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /keelbase/status} — this runtime's integration health, in four dimensions.
 *
 * <p>The surface answers the question an operator asks first when an application is wired into
 * KeelBase and something is not happening: <em>what did this deployment register, is its gate really in
 * force, are audit rows landing, and does the delegation chain work?</em> Each dimension reports a
 * reading and its own status; a failure names what broke instead of leaving the reader to infer it.
 *
 * <p><b>Where each reading comes from.</b> The tool counts come from {@link ToolRegistry}, the same
 * registry the engine asks for tools. The gate modes come from {@link GovernanceService#decide}, the
 * same call the execution engine makes — so this surface cannot describe a gate one way while the
 * engine applies another. The audit readings are counts and timestamps from the chain's own repository,
 * plus its verifier; the runtime has no external audit-reporting path, and the block says so rather
 * than implying one. The delegation reading is a local sign-then-verify probe (see
 * {@link DelegationTokenAuthenticator#probe()}) kept separate from the inbound token this request
 * already presented.
 *
 * <p><b>What this surface refuses to do.</b> It does not turn an unconfigured or inapplicable
 * dimension into a green one. A deployment that supplies its own {@link CallerAuthenticator} reports
 * delegation as {@code not_applicable}, not as a passing probe. A chain too large to verify within the
 * report's bound reports {@code unknown} for the chain reading, not {@code valid}. An audit
 * granularity of {@code off} reports that no new rows are expected rather than counting silence as a
 * fault. Each block carries the reading behind its status so a reader can disagree with the verdict
 * without re-deriving the data.
 *
 * <p><b>Access.</b> The endpoint is authenticated and guarded by {@code read} on
 * {@link GovernanceReadSubjects#INTEGRATION_STATUS}. It must not be public: tool names, gate modes,
 * audit timestamps and queue depth are operational facts, not a capability advertisement.
 *
 * <p>{@code GET /keelbase/status} —— 本运行时的接入健康度，四个维度。
 *
 * <p>当应用接进 KeelBase、却「什么都没发生」时，操作者最先问的问题由这个面回答：**本部署注册了什么、
 * 它的闸是不是真在生效、审计行有没有落库、委托链路能不能走通**。每个维度报一个读数与它自己的状态；
 * 失败会**点名**坏在哪，而不是让读的人自己猜。
 *
 * <p><b>每个读数从哪来。</b> 工具计数取自 {@link ToolRegistry} —— 引擎取工具用的同一个注册表。门控档位取自
 * {@link GovernanceService#decide} —— 执行引擎做的**同一次**调用，故这个面**不可能**把一道闸描述成一种样子、
 * 而引擎按另一种施加。审计读数是链自己的仓库给的条数与时刻，外加它的校验器；本运行时**没有**对外审计上报
 * 通道，那个块也**这么说**，而不是暗示有。委托读数是一个本地「先签发、再验签」探针（见
 * {@link DelegationTokenAuthenticator#probe()}），与本次请求已经出示过的入站令牌**分开**记。
 *
 * <p><b>这个面拒绝做的事。</b> 它不把一个未配置或不适用的维度变成绿色。自带 {@link CallerAuthenticator}
 * 的部署把委托报成 `not_applicable`，而不是一条通过的探针。大到超出报告上限的链，把链读数报成 `unknown`、
 * 不是 `valid`。审计粒度为 `off` 时报「不再预期新行」，而不是把安静当成故障。每个块都带着它状态背后的**读数**，
 * 故读的人可以不认同那个结论、却不必重新推导数据。
 *
 * <p><b>访问。</b> 端点是已认证的，由 {@link GovernanceReadSubjects#INTEGRATION_STATUS} 上的 `read` 把关。
 * 它**不能**公开：工具名、门控档位、审计时刻与队列深度是运维事实，不是能力广告。
 */
@RestController
public class IntegrationStatusController {

    /**
     * The chain-verification bound. The report walks the chain to say whether it holds; a deployment
     * with a very large chain must not turn one status call into an unbounded scan, so past this the
     * reading says {@code unknown} and names the bound instead of pretending to have verified it.
     *
     * 链校验上限。报告会走一遍链来说它站不站得住；链很大的部署不该让一次状态调用变成无界扫描，故超过
     * 这个数就把读数报成 `unknown`、点名那个上限，而不是假装校验过了。
     */
    private static final long CHAIN_CHECK_BOUND = 5000;

    private static final String OK = "ok";
    private static final String WARN = "warn";
    private static final String ERROR = "error";
    private static final String UNKNOWN = "unknown";
    private static final String NOT_APPLICABLE = "not_applicable";
    private static final String PENDING = "pending";

    private final ToolRegistry tools;
    private final GovernanceService governance;
    private final GovernancePolicyStore policy;
    private final ConfirmationRequestRepository confirmations;
    private final AuditService audit;
    private final AuditLogRepository auditLogs;
    private final CallerAuthenticator authenticator;
    private final CurrentPrincipal principals;
    private final OwnershipGuard guard;
    private final ApplicationProfileProperties profile;

    public IntegrationStatusController(ToolRegistry tools, GovernanceService governance,
                                       GovernancePolicyStore policy,
                                       ConfirmationRequestRepository confirmations,
                                       AuditService audit, AuditLogRepository auditLogs,
                                       CallerAuthenticator authenticator, CurrentPrincipal principals,
                                       OwnershipGuard guard, ApplicationProfileProperties profile) {
        this.tools = tools;
        this.governance = governance;
        this.policy = policy;
        this.confirmations = confirmations;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.authenticator = authenticator;
        this.principals = principals;
        this.guard = guard;
        this.profile = profile;
    }

    @GetMapping("/keelbase/status")
    public Map<String, Object> status() {
        Principal caller = principals.current();
        guard.requireAction(caller, GovernanceReadSubjects.INTEGRATION_STATUS,
                PermissionAuthorizer.ACTION_READ);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("provenance", provenance());
        report.put("tools", toolsBlock(errors, warnings));
        report.put("confirmation", confirmationBlock(caller, errors, warnings));
        report.put("audit", auditBlock(errors, warnings));
        report.put("delegation", delegationBlock(errors, warnings));
        report.put("health", health(report, errors, warnings));
        return report;
    }

    private Map<String, Object> provenance() {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("identity", "keelbase4j-runtime");
        Package pkg = IntegrationStatusController.class.getPackage();
        provenance.put("version", pkg == null ? null : pkg.getImplementationVersion());
        provenance.put("repository", "https://github.com/rain6fish/KeelBase4J");
        provenance.put("preset", profile.getPreset());
        return provenance;
    }

    private Map<String, Object> toolsBlock(List<String> errors, List<String> warnings) {
        List<AiTool> all = tools.all();
        long writes = all.stream().filter(tool -> tool.resultType() != null).count();

        Map<String, Long> distribution = new TreeMap<>();
        List<String> invalidLevels = new ArrayList<>();
        List<String> writeWithoutRevoke = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AiTool tool : all) {
            names.add(tool.name());
            String level = tool.riskLevel();
            distribution.merge(level == null ? "(null)" : level, 1L, Long::sum);
            boolean invalid = level == null || !RiskLevel.RISK_STRATEGY.containsKey(level);
            if (invalid) {
                invalidLevels.add(tool.name());
            }
            // A tool the risk policy always blocks never executes, so it has no side effect to leave
            // uncompensated. Warning about its revoke class would describe a write that cannot happen
            // — and a permanent warning is one an operator learns to ignore.
            //
            // 一个始终被风险策略阻断的工具**从不执行**，故它没有会留下的未补偿副作用。为它的撤销档位告警，
            // 等于描述一次**不可能发生**的写 —— 而一条恒久的告警，是操作者会学会忽略的那种。
            boolean alwaysBlocked = !invalid && "block".equals(RiskLevel.strategy(level));
            if (tool.resultType() != null && !"local_compensate".equals(tool.revokeClass())
                    && !alwaysBlocked) {
                writeWithoutRevoke.add(tool.name());
            }
        }

        Map<String, Object> block = new LinkedHashMap<>();
        // The runtime has no @KeelbaseTool export surface; its tools are AiTool beans. Saying which
        // registry this count came from keeps "exported" from standing in for "registered".
        //
        // 本运行时没有 @KeelbaseTool 导出面；它的工具是 AiTool bean。写明计数来自哪个注册表，免得让
        // 「导出」冒充「注册」。
        block.put("source", "ToolRegistry (AiTool beans; no @KeelbaseTool export in this runtime)");
        block.put("total", all.size());
        block.put("read", all.size() - writes);
        block.put("write", writes);
        block.put("names", names);
        block.put("riskDistribution", distribution);
        block.put("invalidRiskLevels", invalidLevels);
        block.put("writeWithoutRevoke", writeWithoutRevoke);

        String status;
        if (all.isEmpty()) {
            status = ERROR;
            errors.add("tools: no AI tools are registered");
        } else if (!invalidLevels.isEmpty()) {
            status = ERROR;
            for (String name : invalidLevels) {
                errors.add("tools: `" + name + "` declares a risk level outside R0-R5");
            }
        } else if (!writeWithoutRevoke.isEmpty()) {
            status = WARN;
            for (String name : writeWithoutRevoke) {
                warnings.add("tools: write tool `" + name + "` declares revokeClass "
                        + "`none`, so its write has no local compensation");
            }
        } else {
            status = OK;
        }
        block.put("status", status);
        return block;
    }

    private Map<String, Object> confirmationBlock(Principal caller, List<String> errors,
                                                  List<String> warnings) {
        int declaredRequired = 0;
        int confirm = 0;
        int approval = 0;
        int blocked = 0;
        int auto = 0;
        List<Map<String, Object>> ungated = new ArrayList<>();

        for (AiTool tool : tools.all()) {
            String level = tool.riskLevel();
            if (level == null || !RiskLevel.RISK_STRATEGY.containsKey(level)) {
                // An invalid level is the tools block's finding. It cannot be gated, and reporting it
                // again here would make one defect look like two dimensions.
                //
                // 非法风险级是工具块的发现。它无法被门控，在这里再报一次会让一处缺陷看起来像两个维度。
                continue;
            }
            boolean required = RiskLevel.needsConfirmation(level);
            if (required) {
                declaredRequired++;
            }
            GateDecision decision = governance.decide(tool, caller);
            switch (decision) {
                case ALLOW -> auto++;
                case CONFIRM -> confirm++;
                case REQUIRE_APPROVAL -> approval++;
                case BLOCK -> blocked++;
            }
            if (required && decision == GateDecision.ALLOW) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tool", tool.name());
                entry.put("riskLevel", level);
                entry.put("gateMode", "auto");
                entry.put("reason", overrideReason(tool.name()));
                ungated.add(entry);
            }
        }

        boolean storeReachable = true;
        long pending = 0;
        try {
            pending = confirmations.countByStatus(PENDING);
        } catch (RuntimeException unreachable) {
            storeReachable = false;
        }

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("evidence", "GovernanceService.decide (the execution engine's own gate)");
        block.put("declaredRequired", declaredRequired);
        block.put("effectiveConfirm", confirm);
        block.put("effectiveApproval", approval);
        block.put("effectiveAuto", auto);
        block.put("effectiveBlocked", blocked);
        block.put("ungated", ungated);
        block.put("pending", storeReachable ? pending : null);
        block.put("storeReachable", storeReachable);

        String status;
        if (!storeReachable) {
            status = ERROR;
            errors.add("confirmation: the confirmation store could not be read");
        } else if (!ungated.isEmpty()) {
            status = ERROR;
            for (Map<String, Object> entry : ungated) {
                errors.add("confirmation: `" + entry.get("tool") + "` declares "
                        + entry.get("riskLevel") + " but the effective gate is auto ("
                        + entry.get("reason") + ")");
            }
        } else if (declaredRequired == 0) {
            status = NOT_APPLICABLE;
            warnings.add("confirmation: no registered tool declares R3/R4, so there is no gate to read");
        } else {
            status = OK;
        }
        block.put("status", status);
        return block;
    }

    /**
     * Why an R3/R4 tool ended up at {@code auto}. The policy is the only thing that can relax the risk
     * binding, so the answer is read from the same override the gate read.
     *
     * 一个 R3/R4 工具为什么落到 `auto`。策略是唯一能放宽风险绑定的东西，故答案取自**那道闸读的同一份**
     * 覆盖。
     */
    private String overrideReason(String toolName) {
        Map<String, Object> override = policy.overrideFor(toolName);
        if (override.get("mode") != null) {
            return "policy mode=" + override.get("mode");
        }
        if (Boolean.FALSE.equals(override.get("requiresConfirmation"))) {
            return "policy requiresConfirmation=false";
        }
        return "the gate returned auto without a policy override — this is a code-level inconsistency";
    }

    private Map<String, Object> auditBlock(List<String> errors, List<String> warnings) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("sink", "local hash-chained ai_audit_logs (this runtime has no external audit reporting)");
        String granularity = policy.granularity();
        block.put("granularity", granularity);

        long rows = auditLogs.count();
        Optional<AuditLog> latest = auditLogs.findTopByOrderByIdDesc();
        block.put("rows", rows);
        block.put("rowsLast24h", auditLogs.countByCreatedAtAfter(
                Instant.now().minus(24, ChronoUnit.HOURS)));
        block.put("latestAt", latest.map(log -> log.getCreatedAt().toString()).orElse(null));
        block.put("latestId", latest.map(AuditLog::getId).orElse(null));

        Boolean chainValid = null;
        Integer chainChecked = null;
        Integer brokenIndex = null;
        Long brokenRowId = null;
        String chainCheck;
        if (rows <= CHAIN_CHECK_BOUND) {
            AuditService.Verification verification = audit.verify();
            chainValid = verification.valid();
            chainChecked = verification.checked();
            brokenIndex = verification.brokenIndex();
            for (Map<String, Object> node : verification.chain()) {
                if (Boolean.TRUE.equals(node.get("broken")) && node.get("id") instanceof Number id) {
                    brokenRowId = id.longValue();
                }
            }
            chainCheck = "checked";
        } else {
            chainCheck = "skipped: " + rows + " rows exceeds the " + CHAIN_CHECK_BOUND
                    + "-row check bound";
        }
        block.put("chainValid", chainValid);
        block.put("chainChecked", chainChecked);
        block.put("chainCheck", chainCheck);
        block.put("brokenIndex", brokenIndex);
        block.put("brokenRowId", brokenRowId);

        String status;
        if ("off".equals(granularity)) {
            status = NOT_APPLICABLE;
            warnings.add("audit: granularity is off — no new audit rows are expected");
        } else if (rows == 0) {
            status = ERROR;
            errors.add("audit: no audit rows have landed (granularity=" + granularity + ")");
        } else if (Boolean.FALSE.equals(chainValid)) {
            status = ERROR;
            errors.add("audit: the chain is broken"
                    + (brokenRowId == null ? "" : " at row id " + brokenRowId)
                    + (brokenIndex == null ? "" : " (index " + brokenIndex + ")"));
        } else if (chainValid == null) {
            status = UNKNOWN;
            warnings.add("audit: the chain was not verified — " + chainCheck);
        } else {
            status = OK;
        }
        block.put("status", status);
        return block;
    }

    private Map<String, Object> delegationBlock(List<String> errors, List<String> warnings) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("provider", authenticator.getClass().getSimpleName());
        // The request carrying this report was authenticated by the same chain, so the inbound half is
        // a fact of this call rather than a promise. What it proves is that this token verified; the
        // probe below adds what the configured secret can do locally.
        //
        // 送来这份报告的请求由同一条链认证，故入站那一半是这次调用的事实、不是承诺。它证明的是**这一枚**
        // 令牌验过了；下面的探针补上配置的 secret 在本地能做什么。
        block.put("inboundVerified", true);

        String status;
        if (authenticator instanceof DelegationTokenAuthenticator tokenAuthenticator) {
            DelegationTokenAuthenticator.Probe probe = tokenAuthenticator.probe();
            block.put("secretConfigured", probe.secretConfigured());
            block.put("audienceConfigured", probe.audienceConfigured());
            block.put("audience", probe.audience());
            block.put("selfTest", probe.roundTrip());
            block.put("note", "selfTest signs and verifies a locally minted token with the configured "
                    + "secret; it does not prove the upstream issuer shares that secret. "
                    + "inboundVerified reports that this caller's own token was accepted.");
            if (probe.secretConfigured() && probe.audienceConfigured() && probe.roundTrip()) {
                status = OK;
            } else {
                status = ERROR;
                errors.add("delegation: " + (probe.reason() == null
                        ? "the local sign-then-verify probe failed" : probe.reason()));
            }
        } else {
            block.put("secretConfigured", null);
            block.put("audienceConfigured", null);
            block.put("audience", null);
            block.put("selfTest", null);
            block.put("note", "this deployment supplies its own CallerAuthenticator; KeelBase "
                    + "delegation verification is not in use, so no delegation reading is claimed");
            status = NOT_APPLICABLE;
            warnings.add("delegation: the deployment authenticates callers itself — not applicable");
        }
        block.put("status", status);
        return block;
    }

    private Map<String, Object> health(Map<String, Object> report, List<String> errors,
                                       List<String> warnings) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (String dimension : List.of("tools", "confirmation", "audit", "delegation")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) report.get(dimension);
            dimensions.put(dimension, String.valueOf(block.get("status")));
        }
        boolean anyError = dimensions.containsValue(ERROR);
        boolean anyDegraded = dimensions.containsValue(WARN) || dimensions.containsValue(UNKNOWN);
        String status = anyError ? ERROR : (anyDegraded ? "degraded" : "healthy");

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", status);
        health.put("summary", switch (status) {
            case ERROR -> "at least one dimension reports an error; the errors name what failed";
            case "degraded" -> "no dimension is in error, but at least one reports a warning or an "
                    + "unverified reading";
            default -> "each dimension reports a reading and none is in error";
        });
        health.put("dimensions", dimensions);
        health.put("errors", List.copyOf(errors));
        health.put("warnings", List.copyOf(warnings));
        health.put("checkedAt", Instant.now().toString());
        return health;
    }
}
