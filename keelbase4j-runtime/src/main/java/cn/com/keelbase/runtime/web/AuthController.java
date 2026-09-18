// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The identity surface: what the caller may do.
 *
 * <p>{@code GET /auth/me/permissions} answers in the frozen {@code permission-capability-list}
 * contract — the same path and the same shape the reference serves (main repo PC-1). This is the
 * single capability source a frontend keys page/menu/button visibility off
 * ({@code docs/authorization-architecture.md} §9), so serving it here is what keeps one frontend
 * usable against either runtime (Rev-8).
 *
 * <p>"Me" is the authenticated caller, so there is nothing to pass in. The answer is about whoever
 * the token proved they are — never about a caller the request could simply name.
 */
@RestController
public class AuthController {

    private final CurrentPrincipal principals;
    private final PermissionAuthorizer authorizer;

    public AuthController(CurrentPrincipal principals, PermissionAuthorizer authorizer) {
        this.principals = principals;
        this.authorizer = authorizer;
    }

    @GetMapping("/auth/me/permissions")
    public Map<String, Object> myPermissions() {
        return authorizer.describe(principals.current()).toWire();
    }
}
