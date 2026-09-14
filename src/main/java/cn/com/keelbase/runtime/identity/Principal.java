// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

/**
 * The acting identity. In the spike it is resolved from request headers (see
 * {@link IdentityResolver}); a real deployment maps it from an authenticated session or a
 * delegation token. The whole trust loop is scoped to this identity.
 */
public record Principal(String userId, String role) {

    public boolean isManager() {
        return "manager".equals(role) || "admin".equals(role);
    }
}
