// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asks the deployment's {@link CallerAuthenticator} who the request is, and writes the answer into the
 * security context — the mechanics, once, for every deployment (ADR-0017 D6).
 *
 * <p>This is the authentication half only (ADR-0004 D3: Security ≠ Trust). It answers <em>who is this
 * request</em>; what that identity may then do is decided by KeelBase's authorization contracts, never
 * here. Accordingly the authenticated token carries <b>no authorities</b>, and nothing in the security
 * configuration reads them.
 *
 * <p>A request that proves nothing is left unauthenticated rather than refused here: the chain's
 * {@code anyRequest().authenticated()} answers it, which is one place deciding that and not two.
 *
 * <p>问部署方的 `CallerAuthenticator`「这条请求是谁」，再把答案写进 security context ——**机制只写一遍**，
 * 所有部署共用（ADR-0017 D6）。
 *
 * <p>这里只做认证那一半（ADR-0004 D3：Security ≠ Trust）。它回答**这条请求是谁**；这个身份此后能做什么，
 * 由 KeelBase 的授权契约决定、**永远不在这里**。因此认证出来的 token 不带**任何 authorities**，安全配置里
 * 也没有一处读它们。
 *
 * <p>什么都没证明的请求不在这里被拒，而是留着未认证：由链上的 `anyRequest().authenticated()` 来答——那是
 * **一处**在做这个决定，不是两处。
 */
public class CallerAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Where the refusal reason is left for the error renderer. The filter does not refuse the request
     * itself — the chain's {@code anyRequest().authenticated()} still decides that, once — but it
     * knows <em>why</em> the authenticator proved nothing, and the entry point that answers 401 is a
     * different component. The attribute is the one hand-off between them.
     *
     * <p>Request attributes survive the container's error dispatch, so {@code WireErrorController}
     * can read it when the 401 is rendered.
     *
     * 拒绝理由留给错误渲染器的地方。过滤器不自己拒绝请求 —— 链上的 `anyRequest().authenticated()`
     * 仍然只做一次那个决定 —— 但它知道认证器**为什么**什么都没证明，而答 401 的入口是另一个组件。
     * 这个属性就是两者之间唯一的一次交接。
     *
     * <p>请求属性会穿过容器的 error dispatch，故 `WireErrorController` 在渲染 401 时读得到它。
     */
    public static final String AUTHENTICATION_FAILURE_ATTRIBUTE =
            CallerAuthenticationFilter.class.getName() + ".failure";

    private final CallerAuthenticator callers;

    public CallerAuthenticationFilter(CallerAuthenticator callers) {
        this.callers = callers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CallerAuthentication authentication = callers.authenticateCaller(request);
        if (authentication.authenticated()) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            authentication.subject(), null, List.of()));
        } else if (authentication.failure() != null) {
            request.setAttribute(AUTHENTICATION_FAILURE_ATTRIBUTE, authentication.failure());
        }
        chain.doFilter(request, response);
    }
}
