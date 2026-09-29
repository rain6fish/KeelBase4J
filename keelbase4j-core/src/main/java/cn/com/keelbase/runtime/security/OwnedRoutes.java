// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The requests this runtime's own controllers serve — derived, not listed.
 *
 * <p>The chain this scopes has one question to answer: <em>is this request mine to govern?</em> A
 * hand-written list of prefixes answers it wrongly in both directions. Too wide, and it claims paths
 * a host owns. Too narrow, and a route this runtime serves is silently left unauthenticated — a hole
 * rather than a slip, and one no reviewer would see. So the answer is derived from the controllers
 * themselves: the paths they declare, filtered to this runtime's own package. Declare a controller
 * and its routes are governed; a deployment's controllers live elsewhere and are not, which is
 * precisely the boundary the embedded case needs.
 *
 * <p>Resolution is deferred to the first request and cached once it yields something. The chain is
 * built before the MVC handler mappings may be ready, and caching an <em>empty</em> answer would pin
 * the runtime into governing nothing at all — so an empty answer is not cached, it is retried, and
 * the retry costs nothing until it succeeds: a request no handler claims is not ours, and a request
 * that reaches a controller is proof the mapping was there.
 *
 * <p>本条链治理的请求 = **本运行时自己的控制器所服务的请求** —— 靠**推导**，不是靠清单。
 *
 * <p>由它划范围的这条链只需要回答一个问题：**这条请求归我治理吗？** 手写前缀清单在两个方向上都会答错
 * —— 写宽了会认领宿主的路径；写窄了会让本运行时自己的某条路由**静默地**失去认证，那是洞而不是疏漏，而且
 * 评审时看不出来。故答案从控制器本身推导：它们声明的路径，按本运行时自己的包过滤。声明一个控制器，其路由
 * 即受治理；部署自己的控制器住在别的包里、不受本链治理 —— 这正是嵌入形态需要的那条边界。
 *
 * <p>解析推迟到首个请求、并在**有结果时**才缓存。链的构建早于 MVC 处理器映射就绪，而把「什么都没有」缓存
 * 下来会把运行时**永久钉在「什么都不治理」**上 —— 故空结果不缓存、下次重试，且它在成功之前不花代价：没有
 * 处理器认领的请求本就不是我们的，而能到达控制器就证明映射已经在了。
 */
final class OwnedRoutes implements RequestMatcher {

    /** Where this runtime's controllers live. Everything under it is ours to govern. */
    private static final String RUNTIME_PACKAGE = "cn.com.keelbase.runtime";

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    /** Built once from what the controllers declare; never an empty answer (see the class javadoc). */
    private volatile RequestMatcher routes;

    OwnedRoutes(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        RequestMatcher resolved = routes();
        return resolved != null && resolved.matches(request);
    }

    private RequestMatcher routes() {
        RequestMatcher built = routes;
        if (built != null) {
            return built;
        }
        RequestMatcher candidate = build();
        if (candidate != null) {
            routes = candidate;
        }
        return candidate;
    }

    private RequestMatcher build() {
        List<RequestMatcher> owned = mappings.stream()
                .flatMap(mapping -> mapping.getHandlerMethods().entrySet().stream())
                .filter(entry -> isOurs(entry.getValue()))
                .map(Map.Entry::getKey)
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(Objects::nonNull)
                .flatMap(condition -> condition.getPatternValues().stream())
                .distinct()
                .map(pattern -> PathPatternRequestMatcher.withDefaults().matcher(pattern))
                .map(RequestMatcher.class::cast)
                .toList();
        return owned.isEmpty() ? null : new OrRequestMatcher(owned);
    }

    private static boolean isOurs(HandlerMethod handler) {
        return handler.getBeanType().getPackageName().startsWith(RUNTIME_PACKAGE);
    }
}
