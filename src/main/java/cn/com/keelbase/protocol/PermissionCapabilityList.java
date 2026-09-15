// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explainable Authz — the principal's own capability list: what this identity may do, and on what basis.
 *
 * <p>Java carrier of the frozen wire contract {@code permission-capability-list.schema.json} (main repo,
 * {@code GET /auth/me/permissions}). It is the single capability source for page/menu/button visibility
 * (main repo {@code docs/authorization-architecture.md} §9), so both runtimes must serve the same shape.
 *
 * <p>The contract freezes the shape, the value domains ({@code role}, {@code scope}, the action set)
 * and the ordering; the <em>subject</em> set is per-application — this runtime reports its own subjects.
 *
 * @param role      contract role vocabulary: {@code user} or {@code admin}
 * @param basis     human-readable basis for the whole list
 * @param resources per-subject capability items, sorted by subject
 */
public record PermissionCapabilityList(String role, String basis, List<Resource> resources) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";
    public static final List<String> ROLES = List.of(ROLE_USER, ROLE_ADMIN);

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_OWN = "own";

    /** The canonical action order; {@code manage} is always expanded into exactly this set. */
    public static final String MANAGE = "manage";
    public static final List<String> EXPANDED_ACTIONS = List.of("create", "read", "update", "delete");

    public static final String BASIS_ADMIN = "管理员角色：可管理全部资源";
    public static final String BASIS_USER = "普通用户：可管理本人拥有的资源（行级所有权条件）";

    /** The wildcard subject: a rule granted on everything. */
    public static final String SUBJECT_ALL = "all";

    public static final String REASON_RESOURCE_ALL = "管理员：可管理全部资源";
    public static final String REASON_RESOURCE_OWN = "只能操作自己的数据（行级所有权条件）";
    public static final String REASON_RESOURCE_UNRESTRICTED = "可访问（无行级限制）";

    /**
     * One subject's capabilities.
     *
     * @param scope {@code all} when there is no row-level restriction, {@code own} when the rule
     *              carries an ownership condition
     */
    public record Resource(String subject, String scope, List<String> actions, String reason) {

        /** The wire shape of one resource item. */
        public Map<String, Object> toWire() {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("subject", subject);
            wire.put("scope", scope);
            wire.put("actions", actions);
            wire.put("reason", reason);
            return wire;
        }
    }

    /**
     * Expand declared actions to the canonical set: {@code manage} unfolds to
     * {@link #EXPANDED_ACTIONS}, duplicates collapse, and the result is ordered by canonical
     * position (an action outside the canonical set sorts ahead, as the reference's index lookup does).
     */
    public static List<String> normalizeActions(List<String> actions) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String action : actions) {
            if (MANAGE.equals(action)) {
                expanded.addAll(EXPANDED_ACTIONS);
            } else {
                expanded.add(action);
            }
        }
        List<String> ordered = new ArrayList<>(expanded);
        ordered.sort(Comparator.comparingInt(EXPANDED_ACTIONS::indexOf));
        return ordered;
    }

    /** Expand a single declared action. */
    public static List<String> normalizeActions(String action) {
        return normalizeActions(List.of(action));
    }

    /** The wire shape of the list. */
    public Map<String, Object> toWire() {
        List<Object> items = new ArrayList<>();
        for (Resource resource : resources) {
            items.add(resource.toWire());
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", role);
        wire.put("basis", basis);
        wire.put("resources", items);
        return wire;
    }
}
