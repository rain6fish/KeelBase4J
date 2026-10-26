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
import cn.com.keelbase.runtime.tool.ToolDeclaration;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** The risk levels whose calls count as actions rather than reads. / 计为动作而非只读的风险级。 */
    private static final Set<String> WRITE_LEVELS = Set.of("R2", "R3", "R4", "R5");

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

        // One list feeds both the structured diagnostics and the flat errors/warnings: derived, not
        // written twice. A finding that is not in this list is not reported anywhere.
        //
        // 一份列表同时喂结构化诊断与扁平 errors/warnings：派生出来的，不是写两遍。不在这份列表里的发现，
        // 哪里都不会报。
        List<IntegrationDiagnostic> diagnostics = new ArrayList<>();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("provenance", provenance());
        report.put("tools", toolsBlock(diagnostics));
        report.put("confirmation", confirmationBlock(caller, diagnostics));
        report.put("audit", auditBlock(diagnostics));
        report.put("delegation", delegationBlock(diagnostics));
        report.put("health", health(report, diagnostics));
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

    private Map<String, Object> toolsBlock(List<IntegrationDiagnostic> diagnostics) {
        List<AiTool> all = tools.all();
        // A host-assembled external tool declares no `resultType` (it has no result id to record) but
        // is still an action surface that can change things; counting only `resultType` would report
        // it as a read. R2 and above count as writes, so the reading does not under-report.
        //
        // 宿主装配的外部工具不声明 `resultType`（它没有可登记的结果 id），但它仍是能改变东西的动作面；只按
        // `resultType` 计数会把它报成只读。R2 及以上计为写，读数不会少报。
        long writes = all.stream()
                .filter(tool -> tool.resultType() != null || WRITE_LEVELS.contains(tool.riskLevel()))
                .count();

        Map<String, Long> distribution = new TreeMap<>();
        Map<String, String> levelByName = new LinkedHashMap<>();
        List<String> invalidLevels = new ArrayList<>();
        List<String> writeWithoutRevoke = new ArrayList<>();
        Map<String, AiTool> missingResultTypeTools = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (AiTool tool : all) {
            names.add(tool.name());
            String level = tool.riskLevel();
            levelByName.put(tool.name(), level);
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
            // A governed write without a result type is refused registration (see `ToolDeclaration`
            // and `ToolRegistry`): without one the runtime would treat it as a read, so an approved
            // execution would leave no side-effect row to audit or revoke. That is a declaration
            // gap, not a valid read.
            //
            // 受治理的写没有结果类型时会被拒绝注册（见 `ToolDeclaration` 与 `ToolRegistry`）：没有它，
            // 运行时会把这次写当只读，故一条被批准的执行留不下可审计、可撤销的副作用行。那是声明缺口，
            // 不是合法的只读。
            if (ToolDeclaration.missingResultType(tool)) {
                missingResultTypeTools.put(tool.name(), tool);
            }
        }
        // Refused tools are not in `all()` — the registry does not pretend they are available — but
        // this block must still name them, or the refusal would only be visible in the startup log.
        //
        // 被拒绝的工具不在 `all()` 里 —— 注册表不会假装它们可用 —— 但本块仍必须点名它们，否则拒绝
        // 就只出现在启动日志里。
        for (AiTool refused : tools.refusedDeclarations()) {
            missingResultTypeTools.putIfAbsent(refused.name(), refused);
        }
        List<String> missingResultTypes = List.copyOf(missingResultTypeTools.keySet());

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
        block.put("missingResultTypes", missingResultTypes);
        block.put("writeWithoutRevoke", writeWithoutRevoke);

        List<IntegrationDiagnostic> found = new ArrayList<>();
        String status;
        if (!invalidLevels.isEmpty()) {
            status = ERROR;
            for (String name : invalidLevels) {
                String level = levelByName.get(name);
                String why = level == null
                        ? "风险级缺失（`null`）；治理闸按风险级决定门控，缺失的级别无法被门控"
                        : "声明值 `" + level + "` 不在 R0–R5 内；治理闸按风险级决定门控，非法值无法被门控";
                found.add(IntegrationDiagnostic.error("tools", "TOOLS_INVALID_RISK_LEVEL", name,
                        "工具 `" + name + "` 的风险级声明非法", why,
                        "把 `riskLevel()` 改为 R0–R5 之一"));
            }
        } else if (!missingResultTypes.isEmpty()) {
            status = ERROR;
            for (AiTool tool : missingResultTypeTools.values()) {
                found.add(IntegrationDiagnostic.error("tools", "TOOLS_MISSING_RESULT_TYPE", tool.name(),
                        "工具 `" + tool.name() + "` 声明 `" + tool.riskLevel()
                                + "`，按治理是写，却没有声明 `resultType`",
                        "注册时已被拒绝：没有结果类型，运行时会把这次写当成只读，批准后执行也落不下"
                                + "可供审计或撤销的副作用行",
                        "让 `resultType()` 返回业务结果类型，并让 `execute` 返回带数字 `id` 的结果；"
                                + "若它只读，把风险级降到 R0–R2"));
            }
        } else if (all.isEmpty()) {
            status = ERROR;
            found.add(IntegrationDiagnostic.error("tools", "TOOLS_NONE_REGISTERED", null,
                    "未注册任何 AI 工具",
                    "`ToolRegistry` 为空：应用上下文里没有任何 `AiTool` bean",
                    "把工具声明为 `AiTool` 的 Spring bean，并确认组件扫描覆盖到工具所在包"));
        } else if (!writeWithoutRevoke.isEmpty()) {
            status = WARN;
            for (String name : writeWithoutRevoke) {
                found.add(IntegrationDiagnostic.warning("tools", "TOOLS_WRITE_WITHOUT_REVOKE", name,
                        "写工具 `" + name + "` 没有本地补偿",
                        "它声明了 `resultType`，但 `revokeClass` 不是 `local_compensate`，写操作不可撤销",
                        "为写工具声明 `revokeClass=\"local_compensate\"`，或确认它确实不需要撤销并接受该告警"));
            }
        } else {
            status = OK;
        }
        block.put("status", status);
        recordFindings(block, diagnostics, found);
        return block;
    }

    private Map<String, Object> confirmationBlock(Principal caller,
                                                  List<IntegrationDiagnostic> diagnostics) {
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

        List<IntegrationDiagnostic> found = new ArrayList<>();
        String status;
        if (!storeReachable) {
            status = ERROR;
            found.add(IntegrationDiagnostic.error("confirmation", "CONFIRMATION_STORE_UNREACHABLE",
                    null,
                    "确认队列不可读",
                    "读取待确认计数时数据库抛出异常，当前读不到任何待确认动作",
                    "检查数据库连通性与确认表是否存在；队列不可读时操作者无法知道有哪些动作在等人"));
        } else if (!ungated.isEmpty()) {
            status = ERROR;
            for (Map<String, Object> entry : ungated) {
                String tool = String.valueOf(entry.get("tool"));
                String level = String.valueOf(entry.get("riskLevel"));
                found.add(IntegrationDiagnostic.error("confirmation", "CONFIRMATION_UNGATED", tool,
                        "工具 `" + tool + "` 声明 `" + level + "`，实际门控是 `auto`",
                        "治理策略覆盖放宽了风险绑定：" + entry.get("reason"),
                        "检查治理策略对该工具的 `mode` / `requiresConfirmation` 覆盖；"
                                + "若要保留人工确认，恢复为 `confirm` 或 `approval`"));
            }
        } else if (declaredRequired == 0) {
            status = NOT_APPLICABLE;
            found.add(IntegrationDiagnostic.warning("confirmation", "CONFIRMATION_NONE_DECLARED", null,
                    "没有声明 R3/R4 的工具",
                    "当前注册表中没有需要人工确认的工具，因此没有门控可读",
                    "若预期有确认门控，检查工具风险级声明是否被降到 R0–R2"));
        } else {
            status = OK;
        }
        block.put("status", status);
        recordFindings(block, diagnostics, found);
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

    private Map<String, Object> auditBlock(List<IntegrationDiagnostic> diagnostics) {
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

        List<IntegrationDiagnostic> found = new ArrayList<>();
        String status;
        if ("off".equals(granularity)) {
            status = NOT_APPLICABLE;
            found.add(IntegrationDiagnostic.warning("audit", "AUDIT_GRANULARITY_OFF", null,
                    "审计粒度为 `off`",
                    "治理策略关闭了新的审计写入，因此没有新行是预期行为",
                    "如需证据链，把治理策略的 `audit.granularity` 设为 `all` 或 `write`"));
        } else if (rows == 0) {
            status = ERROR;
            found.add(IntegrationDiagnostic.error("audit", "AUDIT_NO_ROWS", null,
                    "没有任何审计行落库",
                    "`granularity=" + granularity + "`，但 `ai_audit_logs` 为 0 行",
                    "确认审计写入路径已接好（`AuditService.append`），并检查数据库写入权限"));
        } else if (Boolean.FALSE.equals(chainValid)) {
            status = ERROR;
            found.add(IntegrationDiagnostic.error("audit", "AUDIT_CHAIN_BROKEN",
                    brokenRowId == null ? null : "row " + brokenRowId,
                    "审计链在行 id " + (brokenRowId == null ? "未知" : brokenRowId) + " 处断裂",
                    "该行内容与其哈希不再相符"
                            + (brokenIndex == null ? "" : "（index " + brokenIndex + "）")
                            + "；从这一行起，链无法证明未被改动",
                    "按行 id 检查该条审计记录是否被直接改动；从可信备份恢复或把该段链标记为不可信，"
                            + "不要只改哈希"));
        } else if (chainValid == null) {
            status = UNKNOWN;
            found.add(IntegrationDiagnostic.warning("audit", "AUDIT_CHAIN_UNVERIFIED", null,
                    "审计链未校验",
                    "行数 " + rows + " 超过校验上限 " + CHAIN_CHECK_BOUND,
                    "在维护窗口分段校验，或提高校验上限后重跑状态检查"));
        } else {
            status = OK;
        }
        block.put("status", status);
        recordFindings(block, diagnostics, found);
        return block;
    }

    private Map<String, Object> delegationBlock(List<IntegrationDiagnostic> diagnostics) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("provider", authenticator.getClass().getSimpleName());
        // The request carrying this report was authenticated by the same chain, so the inbound half is
        // a fact of this call rather than a promise. What it proves is that this token verified; the
        // probe below adds what the configured secret can do locally.
        //
        // 送来这份报告的请求由同一条链认证，故入站那一半是这次调用的事实、不是承诺。它证明的是**这一枚**
        // 令牌验过了；下面的探针补上配置的 secret 在本地能做什么。
        block.put("inboundVerified", true);

        List<IntegrationDiagnostic> found = new ArrayList<>();
        String status;
        if (authenticator instanceof DelegationTokenAuthenticator tokenAuthenticator) {
            DelegationTokenAuthenticator.Probe probe = tokenAuthenticator.probe();
            block.put("secretConfigured", probe.secretConfigured());
            block.put("audienceConfigured", probe.audienceConfigured());
            block.put("audience", probe.audience());
            block.put("selfTest", probe.roundTrip());
            // A refused inbound token never reaches this report: the report is only readable after one
            // verifies. Saying that is the honest boundary, and the 401 at the entry carries the
            // refusal's own diagnosis. This block reports the verifier's configuration, not the last
            // refusal.
            //
            // 被拒的入站令牌到不了这份报告：报告只有在某枚令牌验过之后才读得到。把这件事写出来才是诚实的
            // 边界，而入口的 401 带着那次拒绝自己的诊断。这个块报告的是验签器的配置，不是最近一次拒绝。
            block.put("inboundFailureVisible", false);
            block.put("verifies", List.of("signature", "iss", "aud", "exp"));
            block.put("note", "selfTest signs and verifies a locally minted token with the configured "
                    + "secret; it does not prove the upstream issuer shares that secret. "
                    + "inboundVerified reports that this caller's own token was accepted. "
                    + "inboundFailureVisible is false because a refused token cannot reach this "
                    + "report; that refusal is diagnosed at the entry.");
            if (probe.secretConfigured() && probe.audienceConfigured() && probe.roundTrip()) {
                status = OK;
            } else {
                status = ERROR;
                if (!probe.secretConfigured()) {
                    found.add(IntegrationDiagnostic.error("delegation", "DELEGATION_SECRET_BLANK",
                            "keelbase.delegation.secret",
                            "委托 secret 未配置",
                            "`keelbase.delegation.secret` 为空，无法验签任何令牌",
                            "把部署的 `keelbase.delegation.secret` 设为与签发方一致的 HMAC 密钥"));
                } else if (!probe.audienceConfigured()) {
                    found.add(IntegrationDiagnostic.error("delegation", "DELEGATION_AUDIENCE_BLANK",
                            "keelbase.delegation.audience",
                            "委托 audience 未配置",
                            "`keelbase.delegation.audience` 为空；发给空 audience 的令牌也会通过，"
                                    + "分不出一枚令牌是不是发给本系统的",
                            "把 `keelbase.delegation.audience` 设为签发方使用的目标系统标识"));
                } else {
                    found.add(IntegrationDiagnostic.error("delegation", "DELEGATION_SELF_TEST_FAILED",
                            "keelbase.delegation.secret",
                            "委托自检失败",
                            "配置的 secret 无法验证它自己签发的令牌："
                                    + (probe.reason() == null ? "往返未通过" : probe.reason()),
                            "检查 `keelbase.delegation.secret` 是否为合法 HMAC 密钥，并确认部署配置未被截断"));
                }
            }
        } else {
            block.put("secretConfigured", null);
            block.put("audienceConfigured", null);
            block.put("audience", null);
            block.put("selfTest", null);
            block.put("inboundFailureVisible", false);
            block.put("verifies", List.of());
            block.put("note", "this deployment supplies its own CallerAuthenticator; KeelBase "
                    + "delegation verification is not in use, so no delegation reading is claimed");
            status = NOT_APPLICABLE;
            found.add(IntegrationDiagnostic.warning("delegation", "DELEGATION_NOT_APPLICABLE", null,
                    "委托验签未启用",
                    "部署自带 `CallerAuthenticator`，KeelBase 委托链路不在使用中",
                    "如需 KeelBase 委托链路，移除自定义 `CallerAuthenticator`，并配置 "
                            + "`keelbase.delegation.secret` 与 `keelbase.delegation.audience`"));
        }
        block.put("status", status);
        recordFindings(block, diagnostics, found);
        return block;
    }

    private Map<String, Object> health(Map<String, Object> report,
                                       List<IntegrationDiagnostic> diagnostics) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (String dimension : List.of("tools", "confirmation", "audit", "delegation")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) report.get(dimension);
            dimensions.put(dimension, String.valueOf(block.get("status")));
        }
        boolean anyError = dimensions.containsValue(ERROR);
        boolean anyDegraded = dimensions.containsValue(WARN) || dimensions.containsValue(UNKNOWN);
        String status = anyError ? ERROR : (anyDegraded ? "degraded" : "healthy");

        List<String> errors = diagnostics.stream()
                .filter(finding -> IntegrationDiagnostic.ERROR.equals(finding.severity()))
                .map(IntegrationDiagnostic::summary)
                .toList();
        List<String> warnings = diagnostics.stream()
                .filter(finding -> IntegrationDiagnostic.WARNING.equals(finding.severity()))
                .map(IntegrationDiagnostic::summary)
                .toList();

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", status);
        health.put("summary", switch (status) {
            case ERROR -> "at least one dimension reports an error; the errors name what failed";
            case "degraded" -> "no dimension is in error, but at least one reports a warning or an "
                    + "unverified reading";
            default -> "each dimension reports a reading and none is in error";
        });
        health.put("dimensions", dimensions);
        health.put("errors", errors);
        health.put("warnings", warnings);
        // Absent on a clean deployment: a successful report does not carry empty diagnostic objects,
        // and the flat errors/warnings above are still there for readers that predate this field.
        //
        // 干净的部署里不出现：成功报告不携带空诊断对象，而上面的扁平 errors/warnings 仍然在，供早于本字段
        // 的读者使用。
        if (!diagnostics.isEmpty()) {
            health.put("diagnostics", List.copyOf(diagnostics));
        }
        health.put("checkedAt", Instant.now().toString());
        return health;
    }

    /**
     * Attach one dimension's findings to its block and to the report-wide list.
     *
     * <p>The block-level copy is what a reader of that dimension needs; the report-wide list is what
     * {@code health} derives its flat errors/warnings from. Both come from the same objects.
     *
     * <p>把一个维度的发现挂到它的块上，同时并进全报告的列表。
     *
     * <p>块级副本是该维度读者要的东西；全报告列表是 {@code health} 派生扁平 errors/warnings 的来源。
     * 两者来自同一批对象。
     */
    private static void recordFindings(Map<String, Object> block,
                                       List<IntegrationDiagnostic> diagnostics,
                                       List<IntegrationDiagnostic> found) {
        if (!found.isEmpty()) {
            block.put("diagnostics", List.copyOf(found));
        }
        diagnostics.addAll(found);
    }
}
