// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
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

    private final CallerAuthenticator callers;

    public CallerAuthenticationFilter(CallerAuthenticator callers) {
        this.callers = callers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuthenticatedSubject subject = callers.authenticate(request);
        if (subject != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(subject, null, List.of()));
        }
        chain.doFilter(request, response);
    }
}
