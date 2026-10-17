// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.audit.OperationAuditLog;
import cn.com.keelbase.runtime.audit.OperationAuditLogRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records the business writes this surface serves — the operations chain, as distinct from the AI one.
 *
 * <p>What it records is decided by what a request <em>is</em>, not by who sent it: a write method on a
 * business path. The AI surface is left out because its calls already have a chain of their own, and a
 * turn, a confirmation and a revoke are read as a decision trace rather than as business operations.
 * Two POSTs that only read are left out for the same reason — a count and a provider list are not
 * operations, whatever their method says.
 *
 * <p>The row is written after the handler has answered, so it can say how the request ended; the
 * identity has to be read before that, because a request's security context is gone by then. Failing to
 * write the row does not fail the request: the write already happened, and refusing afterwards would not
 * put the row back. That is the same best-effort boundary the reference draws, and the same one the AI
 * chain states for its own bookkeeping.
 *
 * <p>**记录本面所服务的业务写** —— 这是操作链，与 AI 那条不同。
 *
 * <p>记什么由**这个请求是什么**决定，不由**谁发的**决定：业务路径上的写方法。AI 面被排除，是因为它的调用**已经有**
 * 自己的链，而一个回合、一次确认、一次撤销读的是**决策轨迹**、不是业务操作。两个**只读的 POST** 同理排除 ——
 * 一个计数和一个 provider 列表不是操作，无论它的方法是哪个。
 *
 * <p>那一行写在处理器**答完之后**，所以它说得出这次请求**怎么结束的**；而**身份必须在之前**读，因为到那时请求的
 * 安全上下文已经没了。**写不进这一行不会让请求失败**：写已经发生了，事后拒绝也放不回那一行。这是参照实现划的同一条
 * best-effort 边界，也是 AI 那条链为自己记账时说的同一条。
 */
public class OperationAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditInterceptor.class);

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PATCH", "PUT", "DELETE");

    /** Where {@link #preHandle} leaves the caller for {@link #afterCompletion} to pick up. */
    /**
     * The request attribute the operation row reads its operator from. A handler that authenticates
     * the caller itself (the generated application's login) publishes the identity here once it has
     * one, because {@code preHandle} runs before the caller is known.
     *
     * 操作行读取操作者的请求属性。自行认证调用方的处理器（生成物的登录）在拿到身份后写在这里 ——
     * {@code preHandle} 在调用方已知之前就跑过了。
     */
    public static final String OPERATOR_ATTRIBUTE = "keelbase.operationAudit.operator";

    private final OperationAuditLogRepository logs;
    private final CurrentPrincipal principals;

    public OperationAuditInterceptor(OperationAuditLogRepository logs, CurrentPrincipal principals) {
        this.logs = logs;
        this.principals = principals;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(OPERATOR_ATTRIBUTE, operator(request));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception failure) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = request.getRequestURI();
        if (!isOperation(method, path)) {
            return;
        }
        try {
            logs.save(new OperationAuditLog(
                    clip((String) request.getAttribute(OPERATOR_ATTRIBUTE), 255),
                    clip(action(method, path), 32),
                    method,
                    clip(path, 512),
                    clip(targetId(request), 255),
                    response.getStatus(),
                    clip(request.getRemoteAddr(), 64),
                    clip(request.getHeader("User-Agent"), 512)));
        } catch (RuntimeException notAudited) {
            log.warn("operation not audited: {} {}", method, path, notAudited);
        }
    }

    /**
     * A client-controlled value must fit its column: an over-long User-Agent would otherwise make the
     * insert fail, and the catch below would drop the row while the write itself succeeded — an
     * attacker-controlled way to suppress the audit. Truncation is recorded rather than the row lost.
     *
     * 客户端可控的值必须装得进它的列：过长的 User-Agent 会让插入失败，而下面的 catch 会把那一行丢掉、
     * 而写本身已经成功 —— 那是一条可由攻击者操纵的审计抑制路径。宁可截断，也不丢整行。
     */
    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isOperation(String method, String path) {
        if (!WRITE_METHODS.contains(method)) {
            return false;
        }
        if (path.contains("/ai/")) {
            return false;
        }
        // POSTs that read, named one by one rather than by a rule: both are the console's own probes,
        // and a rule broad enough to catch them would catch writes too.
        //
        // 只读的 POST，**逐个点名**而不是定一条规则：两个都是控制台自己的探针，而要宽到能抓住它们的规则，
        // 也会抓住写。
        return !path.endsWith("/auth/login-stats") && !path.endsWith("/auth/oauth/providers");
    }

    private static String action(String method, String path) {
        if (path.endsWith("/auth/login")) {
            return "LOGIN";
        }
        if (path.endsWith("/auth/logout")) {
            return "LOGOUT";
        }
        if (path.endsWith("/upload")) {
            return "UPLOAD";
        }
        return switch (method) {
            case "POST" -> "CREATE";
            case "PATCH", "PUT" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method;
        };
    }

    /** The row the route names, where it names one — read off the handler's own template variables. */
    private static String targetId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> templates && templates.get("id") != null) {
            return String.valueOf(templates.get("id"));
        }
        return null;
    }

    private String operator(HttpServletRequest request) {
        try {
            Principal caller = principals.current();
            return caller == null ? null : caller.userId();
        } catch (ResponseStatusException anonymous) {
            // A request that carried no identity is still an operation — a login attempt before the
            // form is answered, or a call refused for having no token — and it is recorded with
            // nobody attached rather than dropped.
            //
            // **没带身份**的请求**同样是一次操作** —— 表单还没作答的登录尝试、或因没有令牌而被拒的调用 ——
            // 它被记下、只是**不挂人**，而不是被丢掉。
            return null;
        }
    }
}
