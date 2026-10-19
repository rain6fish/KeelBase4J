// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.PmProject;
import cn.com.keelbase.runtime.domain.PmProjectRepository;
import cn.com.keelbase.runtime.domain.PmTask;
import cn.com.keelbase.runtime.domain.PmTaskRepository;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read tool (R1 → auto) — the PM reference project's deadline-risk analysis.
 *
 * <p>Deterministic on purpose, like the CRM read tool: the trust loop is reproducible without a
 * model, and a test can assert the exact reading rather than whatever a provider said today. The
 * declared risk level and the task dates are the only inputs.
 *
 * <p>读工具（R1 → 自动）—— PM 参考项目的延期风险分析。
 *
 * <p>刻意做成确定性的，与 CRM 的读工具一样：信任闭环不依赖模型也能复现，测试断言的是**确切读数**、而不是
 * 某个 provider 今天说了什么。输入只有声明的风险级与任务日期。
 */
@Component
public class AnalyzeProjectRiskTool implements AiTool {

    private static final int DUE_SOON_DAYS = 7;

    private final PmProjectRepository projects;
    private final PmTaskRepository tasks;
    private final OwnershipGuard guard;

    public AnalyzeProjectRiskTool(PmProjectRepository projects, PmTaskRepository tasks,
                                  OwnershipGuard guard) {
        this.projects = projects;
        this.tasks = tasks;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "analyze_project_risk";
    }

    @Override
    public String description() {
        return "Analyze the deadline risk of a project.";
    }

    @Override
    public String riskLevel() {
        return "R1";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("projectId", ToolParameter.NUMBER,
                "the project whose deadline risk is being assessed"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        Long projectId = AnalyzeCustomerRiskTool.asLong(args.get("projectId"));
        if (projectId == null) {
            return ToolResult.fail("projectId is required and must be a number");
        }
        PmProject project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return ToolResult.fail("project not found: " + projectId);
        }
        guard.requireAccess(principal, project, "PmProject", PermissionAuthorizer.ACTION_READ);

        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(DUE_SOON_DAYS);
        int open = 0;
        int overdue = 0;
        int dueSoon = 0;
        for (PmTask task : tasks.findByProjectIdAndDeletedAtIsNull(projectId)) {
            if (!"open".equals(task.getStatus())) {
                continue;
            }
            open++;
            LocalDate due = task.getDueDate();
            if (due == null) {
                continue;
            }
            if (due.isBefore(today)) {
                overdue++;
            } else if (!due.isAfter(soon)) {
                dueSoon++;
            }
        }

        int score = baseScore(project.getRiskLevel()) + overdue * 3 + dueSoon;
        String level = score >= 10 ? "critical" : score >= 5 ? "high" : "low";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getId());
        out.put("name", project.getName());
        out.put("score", score);
        out.put("level", level);
        out.put("openTasks", open);
        out.put("overdueTasks", overdue);
        out.put("dueSoonTasks", dueSoon);
        out.put("reasons", List.of(
                "declared risk level: " + project.getRiskLevel(),
                overdue + " open task(s) overdue",
                dueSoon + " open task(s) due within " + DUE_SOON_DAYS + " days"));
        return ToolResult.ok(out);
    }

    private static int baseScore(String riskLevel) {
        return switch (riskLevel == null ? "" : riskLevel) {
            case "high" -> 10;
            case "medium" -> 6;
            default -> 2;
        };
    }
}
