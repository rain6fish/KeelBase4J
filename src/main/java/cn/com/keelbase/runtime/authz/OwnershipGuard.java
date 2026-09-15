// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.protocol.PermissionDecision;
import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Row-level enforcement — the {@code own} half of the authorization model, enforced in the runtime
 * rather than described in a prompt.
 *
 * <p>The decision does not come from a role check here: it comes from
 * {@link PermissionAuthorizer}, i.e. from the same frozen {@code permission-decision} /
 * {@code permission-capability-list} semantics any other KeelBase runtime produces. The scope
 * ({@code all} vs {@code own}) says whether the row's owner has to match, so "a manager may read any
 * customer, a user only their own" is derived from the contract, not hardcoded.
 */
@Component
public class OwnershipGuard {

    private final PermissionAuthorizer authorizer;

    public OwnershipGuard(PermissionAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * Throw 403 unless {@code principal} may act on a {@code subject} row owned by {@code ownerUserId}.
     *
     * @param action the action being taken on the row (e.g. {@code read})
     */
    public void requireAccess(Principal principal, String subject, String action, String ownerUserId) {
        PermissionDecision decision = authorizer.decide(principal, action, subject);
        if (!decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
        PermissionCapabilityList.Resource capability = authorizer.capabilityFor(principal, subject);
        boolean ownScope = capability != null && PermissionCapabilityList.SCOPE_OWN.equals(capability.scope());
        if (ownScope && !principal.userId().equals(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your resource");
        }
    }
}
