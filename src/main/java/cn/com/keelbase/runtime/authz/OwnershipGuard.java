// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import cn.com.keelbase.runtime.identity.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Row-level permission (the spike's CASL-equivalent): a regular user may access only their own
 * rows; a manager may access any. Enforced in the runtime, not described in a prompt.
 */
@Component
public class OwnershipGuard {

    /** Throw 403 unless {@code principal} may act on a row owned by {@code ownerUserId}. */
    public void requireAccess(Principal principal, String ownerUserId) {
        if (principal.isManager()) {
            return;
        }
        if (!principal.userId().equals(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your resource");
        }
    }

    /** Non-throwing variant for list filtering. */
    public boolean canAccess(Principal principal, String ownerUserId) {
        return principal.isManager() || principal.userId().equals(ownerUserId);
    }
}
