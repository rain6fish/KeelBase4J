// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The identity surface: who the caller is, and what they may do.
 *
 * <p>{@code GET /auth/me/permissions} answers in the frozen {@code permission-capability-list}
 * contract — the same path and the same shape the reference serves (main repo PC-1). This is the
 * single capability source a frontend keys page/menu/button visibility off
 * ({@code docs/authorization-architecture.md} §9), so serving it here is what keeps one frontend
 * usable against either runtime (Rev-8).
 *
 * <p>The other three answer a simpler question — <em>who is this</em> — which a frontend has to
 * settle before it can use the answer above. They are not in the frozen wire registry: the frozen
 * contracts describe the governance surface, not a deployment's login-page furniture. What fixes
 * their shape is the frontend that consumes them (runtime-neutral by FE-1) together with the
 * reference that consumes the same one; where that shape asks for something this runtime does not
 * hold, the answer served here is the honest one rather than a convenient one.
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

    /**
     * Who the caller is, as this deployment knows them.
     *
     * <p>A frontend asks this once, at the session point, and keys the whole shell off the answer —
     * which surface, which navigation, what the caller may see. Without it a runtime cannot be
     * talked to by the console at all: the guard holds a token, asks whose it is, and reads "no
     * answer" as anonymous, so the login page is as far as anyone gets. CORS alone unblocks the
     * transport and still leaves this wall standing.
     *
     * <p>It answers with {@code username} and {@code role} because those are what this deployment's
     * identity directory actually holds — tier A is declared identities, no user table
     * ({@link cn.com.keelbase.runtime.identity.LocalIdentities}). The console's wider user shape
     * also names a numeric id and an email; neither exists here, and manufacturing one would hand
     * out an identifier the runtime cannot honour — a number no other endpoint accepts, or an
     * address nothing delivers to. The console reads neither on this path: its only use of the id
     * is an equality check against an id from another API, which falls through harmlessly.
     */
    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        Principal principal = principals.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", principal.userId());
        body.put("role", principal.role());
        return body;
    }

    /**
     * The OAuth providers this deployment offers — which is none, and that is the answer.
     *
     * <p>The console reads this while rendering the login page, to decide which federated buttons
     * exist. Answering is therefore not optional even when the answer is empty: a refusal is an
     * error the page has to swallow, while an empty list is a fact it renders as no buttons.
     *
     * <p>Empty rather than absent because this runtime delegates identity to the deployment
     * (ADR-0004 D4) and ships no provider registry. "None enabled" is a true description of it, not
     * a stub standing in for behaviour that would otherwise happen.
     */
    @GetMapping("/auth/oauth/providers")
    public Map<String, Object> oauthProviders() {
        return Map.of("enabledProviders", List.of(), "providers", List.of(), "groups", Map.of());
    }

    /**
     * The login page's visit ping, answered {@code ok: false} on purpose.
     *
     * <p>The reference records the caller's address, platform and browser to a dedicated file for
     * security review. This runtime has no such sink, and {@code ok: true} would claim a record that
     * does not exist. The honest field costs nothing: the console sends this fire-and-forget and
     * ignores the result.
     *
     * <p>Implemented at all so the login page stops collecting a 401 it has to swallow — a
     * deployment difference is not the same thing as an absent endpoint, and from outside the two
     * should not look alike.
     */
    @PostMapping("/auth/login-stats")
    public Map<String, Object> loginStats() {
        return Map.of("ok", false);
    }
}
