// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.protocol.DelegationToken;
import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpHeaders;

/**
 * The default {@link CallerAuthenticator}: the caller proves who they are with a delegation token.
 *
 * <p>Verification is the frozen protocol's own {@link DelegationToken#verify} rather than a second
 * implementation of JWT checks: two verifiers would be two things to keep in step, and the protocol is
 * the one that is frozen. A token that fails verification — bad signature, wrong audience, expired —
 * simply proves nothing, and the reason is not echoed back to the caller.
 *
 * <p>This used to be the body of a filter. It is an object rather than a filter now because the
 * question it answers is the deployment's, while the mechanics of asking it — once per request, writing
 * the answer into the security context, running at the right point in the chain — are this runtime's and
 * should not be re-implemented by whoever embeds it (see {@link CallerAuthenticator}).
 */
public class DelegationTokenAuthenticator implements CallerAuthenticator {

    private static final String BEARER = "Bearer ";

    private final String secret;
    private final String audience;

    public DelegationTokenAuthenticator(String secret, String audience) {
        this.secret = secret;
        this.audience = audience;
    }

    @Override
    public AuthenticatedSubject authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        DelegationToken.Result verified = DelegationToken.verify(
                header.substring(BEARER.length()), secret, audience, Instant.now().getEpochSecond());
        if (!verified.ok()) {
            return null;
        }
        Object oidcSubject = verified.payload().get("oidcSub");
        return new AuthenticatedSubject(
                String.valueOf(verified.payload().get("sub")),
                oidcSubject == null ? null : String.valueOf(oidcSubject));
    }
}
