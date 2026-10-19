// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.coverage.CoverageService;
import cn.com.keelbase.runtime.coverage.CoverageStatement;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The coverage statement, read back — what a window was read from, and what reading it cannot show.
 *
 * <p>This is the host-side face of AW-3. The statement is assembled from the vendored gap registry
 * and the rows in the window; this endpoint has no opinion of its own to go stale. It is gated the
 * same way as the rest of the audit reads ({@code read AuditLog}), so an audit reader reaches it and
 * an ordinary caller does not — the same split the TypeScript console uses.
 *
 * <p>Defaults to the last 30 days when no window is given, matching the reference.
 *
 * <p>覆盖声明，读回来 —— 一个时间窗是从什么读来的，以及这么读**看不了**什么。
 *
 * <p>这是 AW-3 的**宿主侧**那一面。声明由 vendored 的缺口登记表与时间窗内的行组装而成；这个端点
 * 自己没有会陈旧的判断。它的闸与其余审计读面相同（{@code read AuditLog}），故审计读者够得到、普通调用方
 * 够不到 —— 与 TypeScript 控制台同一条分界。
 *
 * <p>不给时间窗时默认最近 30 天，与参照实现一致。
 */
@RestController
public class CoverageController {

    private final CoverageService coverage;
    private final CurrentPrincipal principals;
    private final OwnershipGuard guard;

    public CoverageController(CoverageService coverage, CurrentPrincipal principals,
                              OwnershipGuard guard) {
        this.coverage = coverage;
        this.principals = principals;
        this.guard = guard;
    }

    @GetMapping("/audit/coverage")
    public CoverageStatement coverage(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String userId) {
        requireAuditRead();
        Instant to = until == null ? Instant.now() : AuditController.instant("until", until);
        Instant from = since == null
                ? to.minus(30, ChronoUnit.DAYS)
                : AuditController.instant("since", since);
        return coverage.forScope(from, to, userId);
    }

    /**
     * The statement names what the audit chain does not hold, so it is read by the people who may
     * read the chain — administrators or a deployment-declared audit reader, never every caller.
     *
     * 这份声明说出审计链**没有**收住什么，故读它的人就是能读链的人 —— 管理员或部署方点名的审计读者，
     * 绝不是每个调用方。
     */
    private void requireAuditRead() {
        guard.requireAction(principals.current(), GovernanceReadSubjects.AUDIT_LOG,
                PermissionAuthorizer.ACTION_READ);
    }
}
