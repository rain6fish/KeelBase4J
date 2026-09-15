// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The identity surface: what the caller may do.
 *
 * <p>{@code GET /auth/me/permissions} answers in the frozen {@code permission-capability-list}
 * contract — the same path and the same shape the reference serves (main repo PC-1). This is the
 * single capability source a frontend keys page/menu/button visibility off
 * ({@code docs/authorization-architecture.md} §9), so serving it here is what keeps one frontend
 * usable against either runtime (Rev-8).
 */
@RestController
public class AuthController {

    private final IdentityResolver identities;
    private final PermissionAuthorizer authorizer;

    public AuthController(IdentityResolver identities, PermissionAuthorizer authorizer) {
        this.identities = identities;
        this.authorizer = authorizer;
    }

    @GetMapping("/auth/me/permissions")
    public Map<String, Object> myPermissions(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
        Principal principal = identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
        return authorizer.describe(principal).toWire();
    }
}
