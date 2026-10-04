// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

import cn.com.keelbase.protocol.OrgMembershipScope;
import cn.com.keelbase.runtime.identity.Principal;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * The row-range half of authorization: which rows an identity may reach, once the decision function
 * has said the action is allowed at all.
 *
 * <p>Both halves of that sentence matter. The decision function ({@code PermissionAuthorizer}) answers
 * "may this action on this subject happen"; this answers "which rows". The reference keeps them apart
 * for a reason worth restating: folding a row set into the decision would make a capability depend on
 * the data, and would push new range values into the frozen {@code permission-capability-list.scope}
 * enum, which is exactly {@code all} / {@code own}.
 *
 * <p>Two rules are load-bearing and easy to get backwards:
 * <ul>
 *   <li><b>Missing facts tighten, never widen.</b> A range that needs an organization or department
 *       the caller does not have falls back to {@link ScopeLevel#OWN} — never to everything.</li>
 *   <li><b>An unregistered entity is not filtered here.</b> Its caller keeps whatever condition it
 *       had; nothing in this class quietly turns that into "all rows".</li>
 * </ul>
 */
@Component
public class ScopeFilter {

    /** Which attributes carry a filterable entity's owner / organization / department. */
    private record ScopeColumns(String owner, String org, String dept) {
    }

    /**
     * The entities that can be row-filtered and where their scope facts live. An entity absent from
     * this registry is not scope-filterable at all — see {@link #restrict}.
     */
    private static final Map<String, ScopeColumns> SCOPE_COLUMNS = Map.of(
            "Customer", new ScopeColumns("ownerUserId", "orgId", "deptId"));

    private final DataScopeRules levels;
    private final Departments departments;

    public ScopeFilter(DataScopeRules levels, Departments departments) {
        this.levels = levels;
        this.departments = departments;
    }

    /** The range this caller gets, before any degradation — the caller is the key here, not its role. */
    public ScopeDescriptor levelFor(Principal principal) {
        return levels.forRole(principal);
    }

    /** Whether this entity may be row-filtered at all. */
    public static boolean filterable(String entity) {
        return SCOPE_COLUMNS.containsKey(entity);
    }

    /**
     * The list half: a typed predicate over {@code entity}, or empty when nothing should be added —
     * either because the entity is not filterable, or because the range is {@link ScopeLevel#ALL} and
     * the coarse gate alone decides. Never a SQL string: the predicate is built through the criteria
     * API, so there is no text to inject into and the shape stays inspectable.
     */
    public <T> Optional<Specification<T>> restrict(String entity, Principal principal, ScopeDescriptor scope) {
        ScopeColumns columns = SCOPE_COLUMNS.get(entity);
        if (columns == null) {
            // Not filterable: the caller keeps its own condition. Returning "match everything" here
            // would be the one answer this must never give.
            return Optional.empty();
        }
        Facts caller = Facts.of(principal);
        ScopeLevel level = effective(scope, caller);
        if (level == ScopeLevel.ALL) {
            return Optional.empty();
        }
        Set<Long> deptIds = deptIdsFor(level, scope, caller);
        return Optional.of((root, query, cb) -> {
            Predicate owns = cb.equal(root.get(columns.owner()), caller.userId());
            return switch (level) {
                case OWN -> owns;
                case ORG -> cb.or(owns, cb.equal(root.get(columns.org()), caller.orgId()));
                case OWN_DEPT -> cb.or(owns, cb.and(
                        cb.equal(root.get(columns.org()), caller.orgId()),
                        cb.equal(root.get(columns.dept()), caller.deptId())));
                case OWN_DEPT_AND_BELOW -> cb.or(owns, cb.and(
                        cb.equal(root.get(columns.org()), caller.orgId()),
                        within(cb, root.get(columns.dept()), deptIds)));
                case CUSTOM_DEPT -> within(cb, root.get(columns.dept()), deptIds);
                case ALL -> cb.conjunction();
            };
        });
    }

    /**
     * The object half: the same range semantics over one row, so a row fetched directly is judged
     * exactly as it would have been in a list.
     */
    public boolean covers(ScopedRow row, Principal principal, ScopeDescriptor scope) {
        Facts caller = Facts.of(principal);
        Facts ofRow = new Facts(row.ownerUserId(), row.orgId(), row.deptId());
        ScopeLevel level = effective(scope, caller);
        return switch (level) {
            case ALL -> true;
            case OWN -> Objects.equals(caller.userId(), ofRow.userId());
            case ORG -> Objects.equals(caller.userId(), ofRow.userId())
                    || same(caller.orgId(), ofRow.orgId());
            case OWN_DEPT -> Objects.equals(caller.userId(), ofRow.userId())
                    || (same(caller.orgId(), ofRow.orgId()) && same(caller.deptId(), ofRow.deptId()));
            case OWN_DEPT_AND_BELOW -> Objects.equals(caller.userId(), ofRow.userId())
                    || (same(caller.orgId(), ofRow.orgId())
                        && inAnyDept(deptIdsFor(level, scope, caller), ofRow.deptId()));
            case CUSTOM_DEPT -> inAnyDept(deptIdsFor(level, scope, caller), ofRow.deptId());
        };
    }

    /**
     * Whether a row sits in one of these departments. A row with no department sits in none of them —
     * answered here rather than left to the set, because the descriptor's set is immutable and
     * {@code Set.of(...).contains(null)} throws instead of answering, which would turn an ordinary row
     * into an internal error at the one gate whose whole job is to answer.
     *
     * <p>一行是否落在这些部门里。**没有部门的行不落在任何部门里**——这一句写在这里、而不是交给集合本身，
     * 因为描述符那个集合是不可变的，而 `Set.of(...).contains(null)` **抛**而不是作答；那会把一行普通的行，
     * 变成在这道唯一以「作答」为职责的闸上的一个内部错误。
     */
    private static boolean inAnyDept(Set<Long> deptIds, Long rowDeptId) {
        return rowDeptId != null && deptIds.contains(rowDeptId);
    }

    /**
     * The range actually applied. A level that needs organization or department facts the caller does
     * not have degrades to {@link ScopeLevel#OWN}: an absence of facts must tighten a range, never
     * widen it. ({@link ScopeLevel#CUSTOM_DEPT} is not degraded — an empty department set legitimately
     * means "no rows", which is tighter still.)
     */
    private ScopeLevel effective(ScopeDescriptor scope, Facts caller) {
        return switch (scope.level()) {
            case ORG -> caller.orgId() == null ? ScopeLevel.OWN : ScopeLevel.ORG;
            case OWN_DEPT -> caller.orgId() == null || caller.deptId() == null
                    ? ScopeLevel.OWN : ScopeLevel.OWN_DEPT;
            case OWN_DEPT_AND_BELOW -> caller.orgId() == null || caller.deptId() == null
                    ? ScopeLevel.OWN : ScopeLevel.OWN_DEPT_AND_BELOW;
            default -> scope.level();
        };
    }

    /** The department set a range ranges over: the subtree for the "and below" level, the declared set otherwise. */
    private Set<Long> deptIdsFor(ScopeLevel level, ScopeDescriptor scope, Facts caller) {
        if (level == ScopeLevel.CUSTOM_DEPT) {
            return scope.deptIds();
        }
        if (level == ScopeLevel.OWN_DEPT_AND_BELOW && caller.deptId() != null) {
            return departments.subtreeIds(caller.deptId());
        }
        return Set.of();
    }

    /**
     * An {@code IN} over a department set — or, when the set is empty, a predicate that matches
     * nothing. {@code in ()} is not valid SQL, and dropping the condition instead would silently
     * widen the range to every department.
     */
    private static Predicate within(CriteriaBuilder cb, Path<Object> path, Set<Long> deptIds) {
        return deptIds.isEmpty() ? cb.disjunction() : path.in(deptIds);
    }

    /** Equal and both known — {@code null} never satisfies a fact comparison. */
    private static boolean same(Long a, Long b) {
        return a != null && a.equals(b);
    }

    /** The facts a range is decided from, for either side of the comparison. */
    private record Facts(String userId, Long orgId, Long deptId) {

        static Facts of(Principal principal) {
            OrgMembershipScope org = principal.org();
            return new Facts(
                    principal.userId(),
                    org == null || org.org() == null ? null : org.org().id(),
                    org == null ? null : org.deptId());
        }
    }
}
