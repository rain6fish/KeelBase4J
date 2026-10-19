// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.PmProject;
import cn.com.keelbase.runtime.domain.PmProjectRepository;
import cn.com.keelbase.runtime.domain.PmTask;
import cn.com.keelbase.runtime.domain.PmTaskRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.ScopeFilter;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The PM reference project's read half: the projects a caller may see, and the tasks under one.
 *
 * <p>It follows the CRM controller's shape exactly — the coarse gate first, then the caller's row
 * range as a typed predicate — because the point of a second reference project is the same governance
 * loop over another domain, not another way of reading rows.
 *
 * <p>PM 参考项目的读半：调用方看得见的项目，以及某个项目下的任务。
 *
 * <p>它完全照 CRM 控制器的形状写 —— 先粗闸、再把调用方的行范围作为带类型的谓词 —— 因为第二个参考项目的意义
 * 是**同一个治理闭环换一个域**，不是另一种读行方式。
 */
@RestController
public class PmProjectController {

    private final CurrentPrincipal principals;
    private final PmProjectRepository projects;
    private final PmTaskRepository tasks;
    private final OwnershipGuard guard;
    private final ScopeFilter scopes;

    public PmProjectController(CurrentPrincipal principals, PmProjectRepository projects,
                               PmTaskRepository tasks, OwnershipGuard guard, ScopeFilter scopes) {
        this.principals = principals;
        this.projects = projects;
        this.tasks = tasks;
        this.guard = guard;
        this.scopes = scopes;
    }

    @GetMapping("/pm/projects")
    public List<PmProject> list() {
        Principal principal = principals.current();
        guard.requireAction(principal, "PmProject", PermissionAuthorizer.ACTION_READ);
        if (!ScopeFilter.filterable("PmProject")) {
            // An entity that is not scope-filterable leaves the caller to supply its own condition.
            // Seeing every row is not an acceptable stand-in for that.
            //
            // 不可按范围过滤的实体，由调用方自己提供条件；「看见全部行」不是可接受的替代。
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PmProject is not scope-filterable");
        }
        Specification<PmProject> range =
                scopes.<PmProject>restrict("PmProject", principal, scopes.levelFor(principal)).orElse(null);
        return range == null ? projects.findAll() : projects.findAll(range);
    }

    @GetMapping("/pm/projects/{id}/tasks")
    public List<PmTask> tasks(@PathVariable Long id) {
        Principal principal = principals.current();
        PmProject project = projects.findById(id).orElse(null);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found: " + id);
        }
        guard.requireAccess(principal, project, "PmProject", PermissionAuthorizer.ACTION_READ);
        return tasks.findByProjectIdAndDeletedAtIsNull(id);
    }
}
