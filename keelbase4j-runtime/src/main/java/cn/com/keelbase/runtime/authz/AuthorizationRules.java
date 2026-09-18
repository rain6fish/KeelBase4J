// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The authorization rule source — who may do what, before any decision is taken.
 *
 * <p>This is delivery **tier A** (main repo {@code docs/authorization-architecture.md} §9): the rules
 * a project declares, not rows a runtime manages. There is deliberately no {@code roles} /
 * {@code permissions} table — tier A gets page/button/row capability without one. Tier B replaces
 * this bean with a table-backed source; the decision function
 * ({@link PermissionAuthorizer}) does not change, because it reads rules only through
 * {@link #rulesFor(String)}.
 *
 * <p>Rule subjects are entity names; {@code ownerField} names the column holding the row's owner,
 * and {@code null} means the grant carries no row-level restriction. A rule on the wildcard subject
 * grants everything, which is how the contract's {@code admin} role is expressed.
 */
@Component
public class AuthorizationRules {

    /** A declared grant: act on {@code subject}, with an ownership condition when {@code ownerField} is set. */
    public record Rule(String subject, String action, String ownerField) {
    }

    private final Map<String, List<Rule>> byRole;

    /** A rule source assembled by an alternative tier (B: table-backed) without changing the decision function. */
    public AuthorizationRules(Map<String, List<Rule>> byRole) {
        this.byRole = Map.copyOf(byRole);
    }

    /** The tier-A rules this runtime declares. */
    public AuthorizationRules() {
        this(Map.of(
                PermissionCapabilityList.ROLE_ADMIN, List.of(
                        new Rule(PermissionCapabilityList.SUBJECT_ALL, PermissionCapabilityList.MANAGE, null)),
                PermissionCapabilityList.ROLE_USER, List.of(
                        new Rule("Customer", PermissionCapabilityList.MANAGE, "ownerUserId"),
                        new Rule("FollowUp", PermissionCapabilityList.MANAGE, "userId"))));
    }

    public List<Rule> rulesFor(String role) {
        return byRole.getOrDefault(role, List.of());
    }
}
