// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the acting {@link Principal} from request headers.
 *
 * <p>Spike stub: {@code X-User-Id} (required) and {@code X-User-Role} (default {@code user}).
 * The semantic contract — "every AI operation carries an identity; nothing runs anonymously" —
 * is what matters; the physical source of identity is a deployment concern.
 */
@Component
public class IdentityResolver {

    public Principal resolve(String userIdHeader, String roleHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-User-Id");
        }
        String role = (roleHeader == null || roleHeader.isBlank()) ? "user" : roleHeader;
        return new Principal(userIdHeader, role);
    }
}
