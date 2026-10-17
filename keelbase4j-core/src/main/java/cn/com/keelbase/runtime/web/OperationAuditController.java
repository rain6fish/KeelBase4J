// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.audit.OperationAuditLog;
import cn.com.keelbase.runtime.audit.OperationAuditLogRepository;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operations audit: what was done to the business, newest first — administrators or an audit
 * reader.
 *
 * <p>This is the console's operations page, and it reads a different chain from the decision trace
 * beside it: the rows here are business writes, including the ones no AI took part in, while the AI
 * audit records what a tool call came to. The two are separate on purpose and are not joined by
 * anything — see the object's row in {@code docs/wire-object-endpoints.md} for what each one carries.
 *
 * <p>The items answer the frozen {@code operation-audit-log-row} with the fields this runtime sources.
 * What is missing from it — the request body, the field-level change diff, the feature names, the two
 * hash columns — is absent because nothing here produces it, and each is named on that same row rather
 * than left for a reader to wonder about. Nothing is padded with a placeholder: a null the object does
 * not declare is not written at all.
 *
 * <p>**操作审计**：对**业务**做了什么，最新的在前 —— 给管理员或审计读者。
 *
 * <p>这是控制台的操作审计页，它读的链与旁边那条决策轨迹**不同**：这里的行是**业务写**，包括**没有 AI 参与**的那些；
 * 而 AI 审计记的是「一次工具调用最后怎么了」。两条**刻意分开**、**没有任何东西把它们连起来** —— 各自携带什么，见
 * `docs/wire-object-endpoints.md` 里该对象那一行。
 *
 * <p>这些项按冻结的 `operation-audit-log-row` 作答，用的是**本运行时取得到**的字段。其中缺的那些 —— 请求正文、
 * 字段级变更差异、功能名、两个哈希列 —— 之所以缺，是因为这里**没有东西产出它们**；每一条都点名在同一行里，而不是
 * 让读的人去猜。**不拿占位值凑数**：该对象没有声明的 null，**一个都不写**。
 */
@RestController
public class OperationAuditController {

    private static final int MAX_LIMIT = 100;

    private final OperationAuditLogRepository logs;
    private final CurrentPrincipal principals;
    private final OwnershipGuard guard;

    public OperationAuditController(OperationAuditLogRepository logs, CurrentPrincipal principals,
                                    OwnershipGuard guard) {
        this.logs = logs;
        this.principals = principals;
        this.guard = guard;
    }

    @GetMapping("/audit/operations/logs")
    public Map<String, Object> logs(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        guard.requireAction(principals.current(), GovernanceReadSubjects.OPERATION_AUDIT,
                PermissionAuthorizer.ACTION_READ);
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        int index = Math.max(1, page) - 1;
        Page<OperationAuditLog> found = logs.findAllByOrderByIdDesc(PageRequest.of(index, size));
        List<Map<String, Object>> items = found.getContent().stream()
                .map(OperationAuditController::view)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", found.getTotalElements());
        body.put("page", index + 1);
        body.put("limit", size);
        body.put("items", items);
        return body;
    }

    private static Map<String, Object> view(OperationAuditLog log) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", log.getId());
        row.put("userId", log.getUserId());
        row.put("action", log.getAction());
        row.put("method", log.getMethod());
        row.put("path", log.getPath());
        row.put("targetId", log.getTargetId());
        row.put("ip", log.getIp());
        row.put("userAgent", log.getUserAgent());
        row.put("statusCode", log.getStatusCode());
        row.put("createdAt", log.getCreatedAt());
        return row;
    }
}
