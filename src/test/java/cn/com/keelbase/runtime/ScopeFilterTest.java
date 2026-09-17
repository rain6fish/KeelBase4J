// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.OrgMembershipScope;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.DataScopeRules;
import cn.com.keelbase.runtime.scope.Departments;
import cn.com.keelbase.runtime.scope.ScopeDescriptor;
import cn.com.keelbase.runtime.scope.ScopeFilter;
import cn.com.keelbase.runtime.scope.ScopeLevel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * JV-10 — the row range, reproduced from the reference's {@code docs/data-scope.spec.md}.
 *
 * <p>Each level is checked on its own, and so are the two rules that are easiest to get backwards:
 * missing facts must tighten a range rather than widen it, and an entity that is not scope-filterable
 * must not quietly become "every row".
 */
class ScopeFilterTest {

    /** One organization; Sales (10) with two departments under it. */
    private final ScopeFilter scopes = new ScopeFilter(new DataScopeRules(), new Departments());

    private static final String SALES_USER = "alice";   // dept 11 (under 10)
    private static final String SOUTH_USER = "bob";     // dept 12 (under 10)
    private static final String HEAD_USER = "carol";    // dept 10 (the parent)

    @Test
    void ownIsTheTightestRangeAndIgnoresOrganizationAndDepartment() {
        ScopeDescriptor own = ScopeDescriptor.of(ScopeLevel.OWN);
        assertTrue(scopes.covers(row("alice", 1L, 11L), inOrg(SALES_USER, 1L, 11L), own));
        assertFalse(scopes.covers(row("bob", 1L, 12L), inOrg(SALES_USER, 1L, 11L), own),
                "a colleague's row is not mine even inside the same department");
        assertFalse(scopes.covers(row("bob", 1L, 11L), inOrg(SALES_USER, 1L, 11L), own),
                "nor is it mine merely because it shares my department");
    }

    @Test
    void orgReachesEveryDepartmentButNotAnotherOrganization() {
        ScopeDescriptor org = ScopeDescriptor.of(ScopeLevel.ORG);
        Principal alice = inOrg(SALES_USER, 1L, 11L);
        assertTrue(scopes.covers(row("alice", 1L, 11L), alice, org), "own row");
        assertTrue(scopes.covers(row("bob", 1L, 12L), alice, org), "a colleague in another department");
        assertFalse(scopes.covers(row("dave", 2L, 21L), alice, org), "another organization entirely");
        assertFalse(scopes.covers(row("eve", null, null), alice, org),
                "a row with no organization is not in mine");
    }

    @Test
    void ownDeptReachesTheDepartmentButNotItsSiblings() {
        ScopeDescriptor ownDept = ScopeDescriptor.of(ScopeLevel.OWN_DEPT);
        Principal alice = inOrg(SALES_USER, 1L, 11L);
        assertTrue(scopes.covers(row("alice", 1L, 11L), alice, ownDept), "own row");
        assertTrue(scopes.covers(row("bob", 1L, 11L), alice, ownDept), "same department");
        assertFalse(scopes.covers(row("erin", 1L, 12L), alice, ownDept), "a sibling department");
        assertFalse(scopes.covers(row("dave", 2L, 11L), alice, ownDept),
                "the same department id in another organization is not mine");
    }

    @Test
    void ownDeptAndBelowReachesTheSubtreeButNotUpOrSideways() {
        ScopeDescriptor below = ScopeDescriptor.of(ScopeLevel.OWN_DEPT_AND_BELOW);

        Principal head = inOrg(HEAD_USER, 1L, 10L);
        assertTrue(scopes.covers(row("alice", 1L, 11L), head, below), "a department beneath mine");
        assertTrue(scopes.covers(row("bob", 1L, 12L), head, below), "and its sibling beneath mine");

        Principal leaf = inOrg(SALES_USER, 1L, 11L);
        assertFalse(scopes.covers(row("bob", 1L, 12L), leaf, below), "a sibling below is not below me");
        assertFalse(scopes.covers(row("carol", 1L, 10L), leaf, below),
                "and my parent is above me, not below");
    }

    @Test
    void customDeptReachesExactlyTheNamedDepartments() {
        ScopeDescriptor custom = ScopeDescriptor.customDept(Set.of(12L));
        Principal alice = inOrg(SALES_USER, 1L, 11L);
        assertTrue(scopes.covers(row("bob", 1L, 12L), alice, custom), "the named department");
        assertFalse(scopes.covers(row("alice", 1L, 11L), alice, custom),
                "and not even the caller's own, which is not named");
    }

    @Test
    void allIsTheOnlyRangeThatReachesEveryRow() {
        ScopeDescriptor all = ScopeDescriptor.of(ScopeLevel.ALL);
        Principal alice = inOrg(SALES_USER, 1L, 11L);
        assertTrue(scopes.covers(row("dave", 2L, 21L), alice, all));
        assertTrue(scopes.covers(row("eve", null, null), alice, all));
    }

    /**
     * The rule that is easiest to get backwards: a range needing facts the caller does not have
     * tightens to their own rows. Falling back to a wider range would turn "we know nothing about
     * this caller" into "this caller may see more".
     */
    @Test
    void missingOrganizationFactsTightenTheRangeInsteadOfWideningIt() {
        Principal noOrg = new Principal(SALES_USER, Principal.ROLE_USER, null, null);

        for (ScopeLevel level : List.of(ScopeLevel.ORG, ScopeLevel.OWN_DEPT,
                ScopeLevel.OWN_DEPT_AND_BELOW)) {
            ScopeDescriptor scope = ScopeDescriptor.of(level);
            assertTrue(scopes.covers(row("alice", 1L, 11L), noOrg, scope),
                    level + " must still reach the caller's own row");
            assertFalse(scopes.covers(row("bob", 1L, 12L), noOrg, scope),
                    level + " must not reach anyone else's row when the facts are missing");
        }
    }

    /** An identity with an organization but no department cannot be "and below" anything. */
    @Test
    void missingDepartmentTightensTheAndBelowRangeToo() {
        Principal noDept = inOrg(SALES_USER, 1L, null);
        ScopeDescriptor below = ScopeDescriptor.of(ScopeLevel.OWN_DEPT_AND_BELOW);
        assertTrue(scopes.covers(row("alice", 1L, 11L), noDept, below));
        assertFalse(scopes.covers(row("bob", 1L, 12L), noDept, below));
    }

    /** An empty custom set means "no rows" — tighter than the caller's own, never wider. */
    @Test
    void anEmptyCustomSetMatchesNothingRatherThanEverything() {
        ScopeDescriptor empty = ScopeDescriptor.customDept(Set.of());
        Principal alice = inOrg(SALES_USER, 1L, 11L);
        assertFalse(scopes.covers(row("alice", 1L, 11L), alice, empty));
        assertFalse(scopes.covers(row("bob", 1L, 12L), alice, empty));
    }

    /**
     * An entity that is not scope-filterable gets no predicate at all — the caller keeps its own
     * condition. Returning "match everything" here would be the one answer this must never give.
     */
    @Test
    void anUnregisteredEntityIsNotFilteredRatherThanLeftWideOpen() {
        assertTrue(ScopeFilter.filterable("Customer"));
        assertFalse(ScopeFilter.filterable("Invoice"));
        assertTrue(scopes.restrict("Invoice", inOrg(SALES_USER, 1L, 11L),
                ScopeDescriptor.of(ScopeLevel.OWN)).isEmpty(),
                "not filterable ⇒ no predicate, and no implied permission either");
    }

    /** A range of {@code all} adds no condition — the coarse gate is what decides there. */
    @Test
    void aRangeOfAllAddsNoRowCondition() {
        assertTrue(scopes.restrict("Customer", inOrg(SALES_USER, 1L, 11L),
                ScopeDescriptor.of(ScopeLevel.ALL)).isEmpty());
        assertTrue(scopes.restrict("Customer", inOrg(SALES_USER, 1L, 11L),
                ScopeDescriptor.of(ScopeLevel.OWN)).isPresent());
    }

    /**
     * The red line: the row range must not leak into the frozen contract. {@code
     * permission-capability-list.scope} is exactly {@code all} / {@code own}, and a served capability
     * list must only ever carry those two — a capability that named a data range would make the
     * capability depend on the data.
     */
    @Test
    void theServedCapabilityListStillOnlyEverSaysAllOrOwn() {
        // The frozen vocabulary, pinned: if a range name ever appears here the contract moved.
        assertEquals("all", PermissionCapabilityList.SCOPE_ALL);
        assertEquals("own", PermissionCapabilityList.SCOPE_OWN);

        PermissionAuthorizer authorizer = new PermissionAuthorizer(new AuthorizationRules());
        Map<String, Object> wire = authorizer.describe(inOrg(HEAD_USER, 1L, 10L)).toWire();

        List<?> resources = (List<?>) wire.get("resources");
        assertFalse(resources.isEmpty(), "the served list must actually carry something to check");
        for (Object item : resources) {
            Object scope = ((Map<?, ?>) item).get("scope");
            assertTrue(PermissionCapabilityList.SCOPE_ALL.equals(scope)
                            || PermissionCapabilityList.SCOPE_OWN.equals(scope),
                    "a served capability carried scope=" + scope + ", which is outside the contract");
        }
    }

    private static Principal inOrg(String userId, Long orgId, Long deptId) {
        return new Principal(userId, Principal.ROLE_USER, null, new OrgMembershipScope(
                new OrgMembershipScope.Org(orgId, "Acme", null), "member", deptId, List.of()));
    }

    private static Customer row(String owner, Long orgId, Long deptId) {
        Customer customer = new Customer("row of " + owner, "low", owner);
        customer.assign(orgId, deptId);
        return customer;
    }
}
