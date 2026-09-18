// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

import cn.com.keelbase.runtime.identity.Principal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Which row range a role ranges at — the declared level source, delivery tier A.
 *
 * <p>The reference keeps these levels per role in a table ({@code roles.data_scope}, overridable per
 * subject). The spike declares them instead, which is the same decision made one tier earlier; a tier
 * B deployment replaces this bean and the levels become configurable without the filtering above it
 * changing a line.
 */
@Component
public class DataScopeRules {

    private final Map<String, ScopeLevel> byRole;

    /** A level source assembled by an alternative tier (B: table-backed) without changing anything else. */
    public DataScopeRules(Map<String, ScopeLevel> byRole) {
        this.byRole = Map.copyOf(byRole);
    }

    /** The tier-A levels this runtime declares. */
    public DataScopeRules() {
        this(Map.of(
                Principal.ROLE_USER, ScopeLevel.OWN,
                Principal.ROLE_ADMIN, ScopeLevel.ALL));
    }

    /**
     * The range a role gets. A role with no configured level gets {@link ScopeLevel#OWN} — the
     * tighter answer, never the wider one. A missing configuration must never be the reason someone
     * suddenly sees more.
     */
    public ScopeDescriptor forRole(String role) {
        return ScopeDescriptor.of(byRole.getOrDefault(role, ScopeLevel.OWN));
    }
}
