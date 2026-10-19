// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.PmProject;
import cn.com.keelbase.runtime.domain.PmProjectRepository;
import cn.com.keelbase.runtime.domain.PmTask;
import cn.com.keelbase.runtime.domain.PmTaskRepository;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * High-impact write tool (R4) — a second person has to approve it (ADR-0018).
 *
 * <p>It exists so the PM reference project reaches the same mode the CRM sample does. Like the CRM
 * escalation it records the escalation as a task, which keeps the write soft-deletable and therefore
 * locally compensable; the gate is the interesting part, not the errand.
 *
 * <p>高影响写工具（R4）——要第二个人批准（ADR-0018）。
 *
 * <p>它存在是为了让 PM 参考项目也走得到 CRM 样例那个模式。与 CRM 的升级一样，它把升级记成一条任务，故这次写
 * 可软删、也就可以本地补偿；有意思的是那道门，不是这桩差事。
 */
@Component
public class EscalateProjectTool implements AiTool {

    private static final String DEFAULT_REASON = "management review";

    private final PmProjectRepository projects;
    private final PmTaskRepository tasks;
    private final OwnershipGuard guard;

    public EscalateProjectTool(PmProjectRepository projects, PmTaskRepository tasks,
                               OwnershipGuard guard) {
        this.projects = projects;
        this.tasks = tasks;
        this.guard = guard;
    }

    @Override
    public String name() {
        return "escalate_project";
    }

    @Override
    public String description() {
        return "Escalate a project to management review.";
    }

    @Override
    public String riskLevel() {
        return "R4";
    }

    @Override
    public String resultType() {
        return "pm_task";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("projectId", ToolParameter.NUMBER,
                        "the project to escalate"),
                ToolParameter.optional("reason", ToolParameter.STRING,
                        "why the project is being escalated"));
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

        String reason = args.get("reason") == null ? DEFAULT_REASON : String.valueOf(args.get("reason"));
        PmTask task = new PmTask(projectId, "Escalated for " + reason, null, principal.userId());
        task.assign(project.getOrgId(), project.getDeptId());
        PmTask saved = tasks.save(task);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("projectId", saved.getProjectId());
        out.put("title", saved.getTitle());
        return ToolResult.ok(out);
    }
}
