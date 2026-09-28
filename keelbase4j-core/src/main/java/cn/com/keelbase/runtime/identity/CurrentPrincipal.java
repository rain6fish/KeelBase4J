// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where the web layer gets the acting identity.
 *
 * <p>Authentication has already happened at the request entry (Spring Security verified a
 * delegation token), so this asks the security context who the caller is. It reads no request
 * header — the client controls those, and nothing a client asserts is evidence.
 *
 * <p>Arriving here without an authenticated subject means the security configuration and this
 * runtime disagree about a request, not that a client did something wrong: an unauthenticated
 * request is answered 401 by the filter chain before any controller runs.
 */
@Component
public class CurrentPrincipal {

    private final IdentityResolver identities;

    public CurrentPrincipal(IdentityResolver identities) {
        this.identities = identities;
    }

    /** The acting principal. Throws 401 if the request somehow reached here unauthenticated. */
    public Principal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedSubject subject)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no authenticated caller");
        }
        return identities.resolve(
                IdentityEvidence.ofAuthenticatedSubject(subject.subject(), subject.oidcSubject()));
    }
}
