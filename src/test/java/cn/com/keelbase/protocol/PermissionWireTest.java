// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Conformance for the permission/identity wire carriers, against the main repo's frozen schemas:
 * {@code permission-decision}, {@code permission-capability-list}, {@code org-membership-scope} and
 * {@code authorization} (all {@code Server-NestJS/specs/protocol/schemas/}).
 *
 * <p>These contracts have no {@code *-vector.json}, so the expectations here are written out from the
 * schema — {@code required}, {@code properties}, the enums and {@code additionalProperties: false}.
 * The authoritative drift gate stays the main repo's {@code wire-schema.spec.ts}; this test's job is
 * to keep the Java carrier from drifting away from the shape it claims to be.
 *
 * <p>The reference's free text is reproduced verbatim so a decision taken on either runtime is
 * directly comparable.
 */
class PermissionWireTest {

    /** Serialize, re-parse, re-serialize — the canonical bytes must be stable. */
    private static void assertRoundTrips(Map<String, Object> wire) {
        String canonical = CanonicalJson.json(wire);
        assertEquals(canonical, CanonicalJson.json(Json.parse(canonical)),
                "wire shape must survive a JSON round trip");
    }

    @Test
    void decisionCarriesExactlyTheContractProperties() {
        PermissionDecision allowed = new PermissionDecision("read", "Customer", true,
                PermissionDecision.REASON_ALLOWED_OWN, null);

        Map<String, Object> wire = allowed.toWire();

        assertEquals(Set.of("action", "subject", "allowed", "reason", "deniedBy"), wire.keySet(),
                "additionalProperties:false — no extra keys");
        assertEquals("read", wire.get("action"));
        assertEquals("Customer", wire.get("subject"));
        assertEquals(Boolean.TRUE, wire.get("allowed"));
        assertTrue(wire.containsKey("deniedBy"), "deniedBy is required — present as null, not absent");
        assertNull(wire.get("deniedBy"));
        assertRoundTrips(wire);
    }

    @Test
    void deniedDecisionNamesTheDenyBasis() {
        PermissionDecision denied = new PermissionDecision("read", "Invoice", false,
                PermissionDecision.REASON_DENIED_USER, PermissionDecision.DENIED_BY_CASL);

        assertEquals("casl", denied.toWire().get("deniedBy"));
        assertEquals(PermissionDecision.REASON_DENIED_USER, denied.reason());
    }

    @Test
    void referenceWordingIsReproducedVerbatim() {
        assertEquals("管理员：可管理全部资源", PermissionDecision.REASON_ALLOWED_ALL);
        assertEquals("可操作（本人所有权范围，行级条件）", PermissionDecision.REASON_ALLOWED_OWN);
        assertEquals("当前策略不允许此操作", PermissionDecision.REASON_DENIED_ADMIN);
        assertEquals("需要管理员权限，或该资源不在你的可管理范围", PermissionDecision.REASON_DENIED_USER);

        assertEquals("管理员角色：可管理全部资源", PermissionCapabilityList.BASIS_ADMIN);
        assertEquals("普通用户：可管理本人拥有的资源（行级所有权条件）", PermissionCapabilityList.BASIS_USER);
        assertEquals("管理员：可管理全部资源", PermissionCapabilityList.REASON_RESOURCE_ALL);
        assertEquals("只能操作自己的数据（行级所有权条件）", PermissionCapabilityList.REASON_RESOURCE_OWN);
        assertEquals("可访问（无行级限制）", PermissionCapabilityList.REASON_RESOURCE_UNRESTRICTED);
    }

    @Test
    void capabilityListMatchesContractShape() {
        PermissionCapabilityList list = new PermissionCapabilityList(
                PermissionCapabilityList.ROLE_USER, PermissionCapabilityList.BASIS_USER,
                List.of(new PermissionCapabilityList.Resource("Customer",
                        PermissionCapabilityList.SCOPE_OWN, PermissionCapabilityList.EXPANDED_ACTIONS,
                        PermissionCapabilityList.REASON_RESOURCE_OWN)));

        Map<String, Object> wire = list.toWire();

        assertEquals(Set.of("role", "basis", "resources"), wire.keySet());
        assertTrue(PermissionCapabilityList.ROLES.contains(wire.get("role")),
                "role is the contract vocabulary user|admin");

        List<?> resources = (List<?>) wire.get("resources");
        Map<?, ?> item = (Map<?, ?>) resources.get(0);
        assertEquals(Set.of("subject", "scope", "actions", "reason"), item.keySet(),
                "resource items carry exactly these four properties");
        assertTrue(List.of("all", "own").contains(item.get("scope")), "scope enum is all|own");
        assertRoundTrips(wire);
    }

    @Test
    void manageExpandsToTheCanonicalActionSetInCanonicalOrder() {
        assertEquals(List.of("create", "read", "update", "delete"),
                PermissionCapabilityList.normalizeActions("manage"));
        assertEquals(List.of("create", "read", "update", "delete"), PermissionCapabilityList.EXPANDED_ACTIONS);
        assertEquals(List.of("read"),
                PermissionCapabilityList.normalizeActions("read"));
        assertEquals(List.of("create", "read", "update", "delete"),
                PermissionCapabilityList.normalizeActions(List.of("manage", "read")),
                "duplicates collapse and the canonical order wins");
    }

    @Test
    void orgMembershipScopeMatchesContractShape() {
        OrgMembershipScope scope = new OrgMembershipScope(
                new OrgMembershipScope.Org(7L, "Acme", null), "member", 12L, List.of("HQ", "Sales"));

        Map<String, Object> wire = scope.toWire();

        assertEquals(Set.of("org", "role", "deptId", "deptPath"), wire.keySet());
        assertTrue(OrgMembershipScope.ROLES.contains(wire.get("role")));
        Map<?, ?> org = (Map<?, ?>) wire.get("org");
        assertEquals(Set.of("id", "name", "description"), org.keySet());
        assertTrue(org.containsKey("description"), "description is emitted, null when unknown");
        assertNull(org.get("description"));
        assertRoundTrips(wire);
    }

    @Test
    void authorizationReasonsMatchContractShape() {
        AuthorizationReasons reasons = new AuthorizationReasons("wire_transfer", "R5", "block", false,
                List.of(new AuthorizationReasons.Check(GovernanceBinding.DENY_RISK_POLICY, false, "R5 refused")), null);

        Map<String, Object> wire = reasons.toWire();

        assertEquals(Set.of("tool", "riskLevel", "riskStrategy", "requiresConfirmation", "checks"), wire.keySet());
        assertEquals("R5", wire.get("riskLevel"));
        assertTrue(List.of("auto", "policy", "confirmation", "human_approval", "block").contains(wire.get("riskStrategy")),
                "riskStrategy is the frozen strategy vocabulary");
        List<?> checks = (List<?>) wire.get("checks");
        Map<?, ?> check = (Map<?, ?>) checks.get(0);
        assertEquals(Set.of("name", "ok", "note"), check.keySet());
        assertTrue(GovernanceBinding.DENY_CHECKS.contains(check.get("name")),
                "check names come from the frozen denial vocabulary");
        assertRoundTrips(wire);
    }

    @Test
    void policyIsOmittedWhenThereIsNoRevisionedPolicy() {
        AuthorizationReasons reasons = new AuthorizationReasons("t", "R1", "auto", false, List.of(), null);
        assertTrue(!reasons.toWire().containsKey("policy"), "the runtime must not invent a policy revision");

        AuthorizationReasons withPolicy = new AuthorizationReasons("t", "R1", "auto", false, List.of(),
                new AuthorizationReasons.Policy("rev-1"));
        assertEquals(Map.of("revision", "rev-1"), withPolicy.toWire().get("policy"));
    }
}
