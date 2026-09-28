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
