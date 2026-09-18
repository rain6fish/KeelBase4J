// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The caller's organization-membership scope — the {@code org}/{@code orgId} data-range descriptor.
 *
 * <p>Java carrier of the frozen wire contract {@code org-membership-scope.schema.json} (main repo,
 * {@code GET /org/my}). This is the identity half of the data-range semantics: business data created
 * by an org member carries {@code orgId} and is visible to fellow members; a non-member carries no
 * {@code orgId} and sees only their own.
 *
 * <p>Scope note: it describes the identity, it does not by itself widen this runtime's row-level
 * enforcement — the spike's row scope is {@code own} (delivery tier A). Org-tree ranges are tier B.
 *
 * @param role     membership role, {@code owner} / {@code admin} / {@code member}
 * @param deptId   the member's department, or {@code null}
 * @param deptPath department names root → leaf; empty when the member has no department
 */
public record OrgMembershipScope(Org org, String role, Long deptId, List<String> deptPath) {

    public static final List<String> ROLES = List.of("owner", "admin", "member");

    /** @param description nullable — the organization description may be absent at runtime */
    public record Org(long id, String name, String description) {
    }

    /** The wire shape: {@code description} is always present (null when unknown), as the contract's sample does. */
    public Map<String, Object> toWire() {
        Map<String, Object> orgWire = new LinkedHashMap<>();
        orgWire.put("id", org.id());
        orgWire.put("name", org.name());
        orgWire.put("description", org.description());

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("org", orgWire);
        wire.put("role", role);
        wire.put("deptId", deptId);
        wire.put("deptPath", deptPath);
        return wire;
    }
}
