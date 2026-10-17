// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolve a pending confirmation: approve (then execute) or decline (nothing written).
 *
 * <p>Whoever approves is the authenticated caller — the running engine checks the confirmation was
 * raised for that same principal, so a valid token cannot be used to approve someone else's pending
 * write.
 *
 * <p>Two more entries exist for the mode where the caller is deliberately <em>not</em> that person —
 * a high-impact action answered by a second one (ADR-0018). They are separate paths rather than a
 * flag on this one because they answer a different question, and the engine keeps them apart the same
 * way: what decides which rows a path may move is the row's mode, not what the caller passed.
 *
 * <p>另外两个入口服务于「调用者**故意不是**那一个操作者」的模式——由第二个人回答的高影响动作
 * （ADR-0018）。它们是**独立的路径**而不是本条上的一个开关，因为它们回答的是**另一个问题**；引擎那边
 * 也以同样的方式把两者分开：决定一条路径能移动哪些行的，是**那一行的 mode**，不是调用方传了什么。
 */
@RestController
public class ConfirmationController {

    /** The console pages on this list, so a reader cannot ask for the whole table by asking nicely. */
    private static final int MAX_LIMIT = 100;

    private final CurrentPrincipal principals;
    private final GovernedExecutionEngine engine;
    private final ConfirmationStore confirmations;
    private final ToolRegistry tools;
    private final OwnershipGuard guard;

    public ConfirmationController(CurrentPrincipal principals, GovernedExecutionEngine engine,
                                  ConfirmationStore confirmations, ToolRegistry tools,
                                  OwnershipGuard guard) {
        this.principals = principals;
        this.engine = engine;
        this.confirmations = confirmations;
        this.tools = tools;
        this.guard = guard;
    }

    /**
     * The whole confirmation queue, read-only — the face JV-63 adds for an audit reader.
     *
     * <p>It answers the same wire projection as {@code /ai/my/confirmations} (one projection, so the
     * two can never disagree about what a confirmation looks like), but over every caller's rows and
     * without the caller's own window filtering: a reader inspecting the queue wants it as it stands.
     * Reading moves nothing. Approving and executing are separate entries that keep their own guards,
     * and an audit reader holds no write capability, so those answer 403 by construction (B8(a)).
     *
     * <p>全部确认队列，**只读** —— JV-63 为审计读者新增的那一面。
     *
     * <p>它答与 `/ai/my/confirmations` **同一份** wire 投影（只有一份，故两者不可能对「一条确认长什么样」
     * 各执一词），但覆盖**所有调用方**的行、且不施加调用方自己的窗口：一个检视队列的读者，要的就是它的**现状**。
     * **读它不移动任何东西**。审批与执行是各自保留守卫的独立入口，而审计读者一项写能力都没有，故那些入口按构造
     * 答 403（B8(a)）。
     */
    @GetMapping("/ai/confirmations")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        guard.requireAction(principals.current(), GovernanceReadSubjects.CONFIRMATION,
                PermissionAuthorizer.ACTION_READ);
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        int index = Math.max(1, page) - 1;
        Instant now = Instant.now();
        long offlineTtlMillis = ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS;
        Page<ConfirmationRequest> found = confirmations.all(PageRequest.of(index, size));
        List<Map<String, Object>> items = found.getContent().stream()
                .map(row -> ConfirmationItemView.item(row, now, offlineTtlMillis, tools))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", found.getTotalElements());
        body.put("page", index + 1);
        body.put("limit", size);
        body.put("items", items);
        return body;
    }

    @PostMapping("/ai/confirmations/{token}")
    public ExecutionOutcome decide(@PathVariable String token, @RequestBody DecisionRequest request) {
        Principal principal = principals.current();
        String decision = decisionOf(request);
        if (ConfirmationLifecycle.APPROVE.equals(decision)) {
            return engine.approve(token, principal);
        }
        return engine.decline(token, principal);
    }

    /**
     * Answer an approval confirmation — the second person a high-impact action waits for (ADR-0018).
     * Administrators only.
     *
     * <p>Approving is refused when the caller is the row's initiator; declining is not, because
     * withdrawing one's own request is not a self-approval.
     *
     * <p>**回答一条审批确认**——高影响动作所等的**第二个人**（ADR-0018）。**仅限管理员**。
     *
     * <p>调用者如果是该行的发起人，**批准**被拒；**拒绝**不受限——撤回自己的请求不是自批。
     */
    @PostMapping("/ai/confirmations/{token}/approve-by")
    public ExecutionOutcome approveBy(@PathVariable String token, @RequestBody DecisionRequest request) {
        return engine.decideApproval(token, requireManager(), decisionOf(request));
    }

    /**
     * Run an approved-but-not-succeeded confirmation again — the entry an attempt that died without
     * recording a result needs (ADR-0018). Administrators only.
     *
     * <p>把一条「已批准但未成功」的确认**再跑一次**——一次没记下结果就死掉的尝试所需要的那个入口
     * （ADR-0018）。**仅限管理员**。
     */
    @PostMapping("/ai/confirmations/{token}/retry-execution")
    public ExecutionOutcome retryExecution(@PathVariable String token) {
        return engine.retryExecution(token, requireManager());
    }

    /**
     * Answering somebody else's high-impact action is an administrative act.
     *
     * <p>回答别人的高影响动作是一种管理行为。
     */
    private Principal requireManager() {
        Principal principal = principals.current();
        if (!principal.isManager()) {
            throw new AccessDeniedException("answering an approval is for administrators");
        }
        return principal;
    }

    private static String decisionOf(DecisionRequest request) {
        try {
            return ConfirmationLifecycle.normalizeDecision(request.decision());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
