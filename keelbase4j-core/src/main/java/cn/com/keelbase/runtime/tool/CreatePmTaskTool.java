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
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Write tool (R3 → confirmation). Never executed before the operator approves. Creates a
 * soft-deletable {@link PmTask}, so a revoke compensates it locally through the compensation seam.
 *
 * <p>写工具（R3 → 确认）。操作者批准之前**绝不**执行。它创建一条可软删的 {@link PmTask}，故撤销经补偿缝
 * 在本地补偿它。
 */
@Component
public class CreatePmTaskTool implements AiTool {

    private final PmProjectRepository projects;
    private final PmTaskRepository tasks;
    private final OwnershipGuard guard;

    public CreatePmTaskTool(PmProjectRepository projects, PmTaskRepository tasks, OwnershipGuard guard) {
        this.projects = projects;
        this.tasks = tasks;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "create_pm_task";
    }

    @Override
    public String description() {
        return "Create a task on a project.";
    }

    @Override
    public String riskLevel() {
        return "R3";
    }

    @Override
    public String resultType() {
        return "pm_task";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("projectId", ToolParameter.NUMBER,
                        "the project the task belongs to"),
                ToolParameter.required("title", ToolParameter.STRING, "the task's title"),
                ToolParameter.optional("dueDate", ToolParameter.STRING,
                        "the due date, ISO-8601 (yyyy-mm-dd)"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Principal principal) {
        Long projectId = AnalyzeCustomerRiskTool.asLong(args.get("projectId"));
        String title = args.get("title") == null ? "" : String.valueOf(args.get("title")).trim();
        if (projectId == null) {
            return ToolResult.fail("projectId is required and must be a number");
        }
        if (title.isEmpty()) {
            return ToolResult.fail("title is required");
        }
        LocalDate dueDate;
        try {
            String raw = args.get("dueDate") == null ? null : String.valueOf(args.get("dueDate"));
            dueDate = raw == null || raw.isBlank() ? null : LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            return ToolResult.fail("dueDate must be an ISO-8601 date (yyyy-mm-dd)");
        }

        PmProject project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return ToolResult.fail("project not found: " + projectId);
        }
        guard.requireAccess(principal, project, "PmProject", PermissionAuthorizer.ACTION_READ);

        PmTask task = new PmTask(projectId, title, dueDate, principal.userId());
        task.assign(project.getOrgId(), project.getDeptId());
        PmTask saved = tasks.save(task);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("projectId", saved.getProjectId());
        out.put("title", saved.getTitle());
        out.put("dueDate", saved.getDueDate() == null ? null : saved.getDueDate().toString());
        return ToolResult.ok(out);
    }
}
