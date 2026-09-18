// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import cn.com.keelbase.protocol.OrgMembershipScope;
import cn.com.keelbase.runtime.scope.Departments;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The default identity adapter: turns what the request entry authenticated into the runtime's
 * {@link Principal}.
 *
 * <p>It reads only the <em>verified</em> subject and looks the user, role and organization up
 * locally ({@link LocalIdentities}). That is the whole difference from the header adapter it
 * replaces: a caller can no longer state who it is, nor what it may do. It can present a token, and
 * this deployment decides what that token's subject means here.
 *
 * <p>The organization facts it projects are what a row range names a department with — see
 * {@code runtime.scope}. Until this existed, {@link Principal#org()} was always {@code null} and the
 * frozen {@code org-membership-scope} contract had nothing behind it.
 *
 * <p>An adapter for a real directory (OIDC, LDAP, Sa-Token) implements this same seam and maps its
 * verified claims onto the same frozen contracts; nothing downstream changes.
 */
@Component
public class SecurityIdentityResolver implements IdentityResolver {

    /** The contract's membership vocabulary — a different axis from the runtime's user/admin role. */
    private static final String MEMBERSHIP_ADMIN = "admin";
    private static final String MEMBERSHIP_MEMBER = "member";

    private final LocalIdentities directory;
    private final Departments departments;

    public SecurityIdentityResolver(LocalIdentities directory, Departments departments) {
        this.directory = directory;
        this.departments = departments;
    }

    @Override
    public Principal resolve(IdentityEvidence evidence) {
        String subject = evidence.attribute(IdentityEvidence.SUBJECT);
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no authenticated subject");
        }
        LocalIdentities.Entry entry = directory.lookup(subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "this deployment has no identity mapped to the presented subject"));
        return new Principal(entry.userId(), entry.role(),
                evidence.attribute(IdentityEvidence.OIDC_SUBJECT), membershipOf(entry));
    }

    /**
     * The identity's organization membership, in the frozen {@code org-membership-scope} shape.
     *
     * <p>An identity this deployment knows nothing about organizationally carries none rather than a
     * made-up one — and a range that needs an organization then tightens to the caller's own rows
     * (see {@code runtime.scope.ScopeFilter}), which is the safe direction.
     */
    private OrgMembershipScope membershipOf(LocalIdentities.Entry entry) {
        if (entry.orgId() == null) {
            return null;
        }
        return new OrgMembershipScope(
                new OrgMembershipScope.Org(entry.orgId(), entry.orgName(), null),
                // Membership role (owner/admin/member) is the contract's vocabulary; the runtime's role
                // set is user/admin, so an admin here is an admin of the organization.
                Principal.ROLE_ADMIN.equals(entry.role()) ? MEMBERSHIP_ADMIN : MEMBERSHIP_MEMBER,
                entry.deptId(),
                departments.pathNames(entry.deptId()));
    }
}
