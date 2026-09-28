// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.protocol.DelegationToken;
import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from the delegation token it presents, and stops there.
 *
 * <p>This is the authentication half only (ADR-0004 D3: Security ≠ Trust). It answers <em>who is
 * this request</em>; what that identity may then do is decided by KeelBase's authorization
 * contracts, never here. Accordingly the authenticated token carries <b>no authorities</b>, and
 * nothing in this configuration reads them.
 *
 * <p>Verification is the frozen protocol's own {@link DelegationToken#verify} rather than a second
 * implementation of JWT checks: two verifiers would be two things to keep in step, and the protocol
 * is the one that is frozen. A token that fails verification — bad signature, wrong audience,
 * expired — simply leaves the request unauthenticated, and the chain answers 401. The reason is not
 * echoed back to the caller.
 */
@Component
public class DelegationTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final String secret;
    private final String audience;

    public DelegationTokenAuthenticationFilter(
            @Value("${keelbase.delegation.secret}") String secret,
            @Value("${keelbase.delegation.audience}") String audience) {
        this.secret = secret;
        this.audience = audience;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            DelegationToken.Result verified = DelegationToken.verify(
                    header.substring(BEARER.length()), secret, audience, Instant.now().getEpochSecond());
            if (verified.ok()) {
                Object oidcSubject = verified.payload().get("oidcSub");
                AuthenticatedSubject subject = new AuthenticatedSubject(
                        String.valueOf(verified.payload().get("sub")),
                        oidcSubject == null ? null : String.valueOf(oidcSubject));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(subject, null, List.of()));
            }
        }
        chain.doFilter(request, response);
    }
}
