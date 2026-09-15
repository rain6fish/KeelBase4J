// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The spike's identity adapter: reads the caller from request headers.
 *
 * <p>{@code X-User-Id} (required), {@code X-User-Role} (default {@code user}) and an optional
 * {@code X-Oidc-Sub} standing in for an enterprise SSO subject. This is the carrier, not the
 * contract — the semantic rule the runtime depends on is "every AI operation carries an identity;
 * nothing runs anonymously", which any adapter must uphold. It does <em>not</em> carry an
 * organization scope: the spike has no organization store, so {@link Principal#org()} stays null
 * and a real adapter fills it from its own membership facts.
 */
@Component
public class HeaderIdentityResolver implements IdentityResolver {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";
    public static final String OIDC_SUBJECT_HEADER = "X-Oidc-Sub";

    @Override
    public Principal resolve(IdentityEvidence evidence) {
        String userId = evidence.attribute(IdentityEvidence.USER_ID);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing " + USER_ID_HEADER);
        }
        String role = evidence.attribute(IdentityEvidence.ROLE);
        String oidcSubject = evidence.attribute(IdentityEvidence.OIDC_SUBJECT);
        return new Principal(userId, role == null ? Principal.ROLE_USER : role, oidcSubject, null);
    }
}
