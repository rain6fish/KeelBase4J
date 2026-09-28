// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import cn.com.keelbase.protocol.OrgMembershipScope;

/**
 * The acting identity — the runtime's projection of the main repo's frozen identity contracts.
 * The whole trust loop is scoped to this identity.
 *
 * <p>It carries the wire-shaped facts the contracts define: {@link #subject()} (the
 * {@code delegation-token-claims} {@code sub} — an OIDC subject or {@code local:<userId>}),
 * {@link #role()} (the {@code permission-capability-list} role vocabulary) and {@link #org()}
 * (the {@code org-membership-scope} descriptor, when the deployment knows one).
 *
 * @param role contract role vocabulary: {@code user} or {@code admin}. The spike's header carrier
 *             also accepts {@code manager} as an alias for {@code admin} — tier A has no RBAC, so
 *             a business role maps onto the contract's role; anything unrecognised is {@code user}.
 */
public record Principal(String userId, String role, String oidcSubject, OrgMembershipScope org) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";

    /** The spike's business-role alias for the contract's {@code admin} role. */
    public static final String ROLE_ALIAS_MANAGER = "manager";

    /** An identity without an SSO subject or organization scope. */
    public Principal(String userId, String role) {
        this(userId, role, null, null);
    }

    public Principal {
        role = ROLE_ADMIN.equals(role) || ROLE_ALIAS_MANAGER.equals(role) ? ROLE_ADMIN : ROLE_USER;
    }

    /** The unified identity mapping key (protocol §3.2 {@code sub}). */
    public String subject() {
        return oidcSubject != null ? oidcSubject : "local:" + userId;
    }

    public boolean isManager() {
        return ROLE_ADMIN.equals(role);
    }
}
