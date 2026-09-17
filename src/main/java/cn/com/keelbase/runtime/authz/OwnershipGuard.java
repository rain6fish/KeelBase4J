// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import cn.com.keelbase.protocol.PermissionDecision;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.ScopeFilter;
import cn.com.keelbase.runtime.scope.ScopedRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single place access to a row is enforced: the coarse gate, then the row gate.
 *
 * <p>The two answer different questions and are kept apart (the reference states the same split):
 * {@link PermissionAuthorizer} answers "may this action on this subject happen at all", and the
 * {@link ScopeFilter} answers "which rows". Inlining a row set into the decision would make a
 * capability depend on the data, and would push new range values into the frozen
 * {@code permission-capability-list.scope} enum, which is exactly {@code all} / {@code own}.
 *
 * <p>Nothing here comes from a role check: the coarse gate is the frozen {@code permission-decision},
 * and the row gate is the data range the caller's role ranges at — where {@code own} is simply that
 * range at its tightest. So "a manager may read any customer, a user only their own" is a consequence
 * of the contracts rather than something hardcoded here.
 */
@Component
public class OwnershipGuard {

    private final PermissionAuthorizer authorizer;
    private final ScopeFilter scopes;

    public OwnershipGuard(PermissionAuthorizer authorizer, ScopeFilter scopes) {
        this.authorizer = authorizer;
        this.scopes = scopes;
    }

    /**
     * Throw 403 unless {@code principal} may take {@code action} on {@code subject} at all — the
     * coarse gate on its own, for a caller that has no row to judge.
     */
    public void requireAction(Principal principal, String subject, String action) {
        PermissionDecision decision = authorizer.decide(principal, action, subject);
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /**
     * Throw 403 unless {@code principal} may take {@code action} on this particular row.
     *
     * <p>For an entity that is not scope-filterable the row half does not apply and the caller keeps
     * whatever condition it has — the guard does not quietly turn that into "any row", which is the
     * one direction this must never move.
     *
     * @param action the action being taken on the row (e.g. {@code read})
     */
    public void requireAccess(Principal principal, ScopedRow row, String subject, String action) {
        requireAction(principal, subject, action);
        if (ScopeFilter.filterable(subject)
                && !scopes.covers(row, principal, scopes.levelFor(principal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "row is outside the data range of this caller");
        }
    }
}
