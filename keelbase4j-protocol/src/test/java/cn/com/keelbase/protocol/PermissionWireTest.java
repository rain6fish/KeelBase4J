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
 * Conformance for the permission/identity wire carriers, against the frozen schemas in the vendored
 * contract: {@code permission-decision}, {@code permission-capability-list},
 * {@code org-membership-scope} and {@code authorization}.
 *
 * <p>The expectations are <em>read from the contract</em>, not transcribed from it. The registry
 * ({@code wire-schema-registry.json}) names the schema file for each contract, and each carrier's
 * wire shape is validated against that schema — {@code required}, {@code properties},
 * {@code additionalProperties}, recursing into nested objects, array items and {@code $ref}.
 * <b>That is the point: the contract is the source of the Java shape, not a document the shape was
 * copied from.</b> A contract change moves this test without anyone editing it.
 *
 * <p>Two things stay written here, and the boundary is worth stating rather than hiding:
 *
 * <ul>
 *   <li><b>Free text.</b> The reference's human-readable wording ({@code reason}, {@code basis})
 *       appears in the schemas only inside {@code description} — the schema constrains it to "a
 *       string". Reproducing the wording verbatim is an assertion this test has to make, because
 *       the contract cannot make it.</li>
 *   <li><b>Vocabularies the schema leaves open.</b> A check's {@code name} is typed {@code string}
 *       in the schema; that it comes from the frozen denial vocabulary is a Java-side invariant,
 *       held against {@code GovernanceBinding}.</li>
 * </ul>
 *
 * <p>One assertion got <em>looser</em> by moving to the contract, and that is deliberate:
 * {@code authorization} is declared {@code additionalProperties: true}, so the schema permits
 * properties beyond the five the runtime emits. Pinning an exact key set there would have been this
 * test's own rule, not the contract's — see {@code authorizationCarriesEverythingTheContractRequires}.
 */
class PermissionWireTest {

    // ── the vendored contract ────────────────────────────────────────────────────────────────────

    /** The registry: contract id → schema file. */
    private static Map<String, Object> registry() {
        return Vectors.map(Vectors.read("wire-schema-registry.json"));
    }

    /**
     * The frozen schema for a contract id. A top-level contract is registered under {@code objects}
     * with its version; a contract the registry classifies as a <em>support schema</em> is named
     * directly and lives in the v1 tree.
     */
    private static Map<String, Object> schemaFor(String id) {
        for (Object o : Vectors.list(registry(), "objects")) {
            Map<String, Object> entry = Vectors.map(o);
            if (id.equals(entry.get("id"))) {
                return Vectors.map(Vectors.read(
                        "schemas/" + entry.get("version") + "/" + entry.get("schema")));
            }
        }
        for (Object s : Vectors.list(registry(), "supportSchemas")) {
            if (s.equals(id + ".schema.json")) {
                return Vectors.map(Vectors.read("schemas/v1/" + s));
            }
        }
        throw new IllegalStateException("contract is not in the vendored registry: " + id);
    }

    /**
     * Validate a wire value against a frozen schema, recursively.
     *
     * <p>Deliberately small — it enforces the three things this conformance is about: every
     * {@code required} property is present; an object declared {@code additionalProperties:false}
     * carries nothing else; and a value is drawn from the schema's {@code enum}. Anything the
     * schema leaves open stays open here too.
     */
    private static void assertConforms(Object value, Map<String, Object> schema) {
        if (schema.containsKey("$ref")) {
            assertConforms(value, Vectors.map(Vectors.read("schemas/v1/" + schema.get("$ref"))));
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed && value != null) {
            assertTrue(allowed.contains(value), "value outside the frozen enum: " + value);
            return;
        }
        if (value instanceof Map<?, ?> wire) {
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> p
                    ? Vectors.map(p) : Map.of();
            if (schema.get("required") instanceof List<?> required) {
                for (Object r : required) {
                    assertTrue(wire.containsKey(r), "required property missing: " + r);
                }
            }
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                assertEquals(properties.keySet(), wire.keySet(),
                        "additionalProperties:false — the wire carries exactly the schema's properties");
            }
            for (Object key : wire.keySet()) {
                if (properties.get(key) instanceof Map<?, ?> sub) {
                    assertConforms(wire.get(key), Vectors.map(sub));
                }
            }
        } else if (value instanceof List<?> items && schema.get("items") instanceof Map<?, ?> item) {
            for (Object v : items) {
                assertConforms(v, Vectors.map(item));
            }
        }
    }

    /** Serialize, re-parse, re-serialize — the canonical bytes must be stable. */
    private static void assertRoundTrips(Map<String, Object> wire) {
        String canonical = CanonicalJson.json(wire);
        assertEquals(canonical, CanonicalJson.json(Json.parse(canonical)),
                "wire shape must survive a JSON round trip");
    }

    // ── permission-decision ──────────────────────────────────────────────────────────────────────

    @Test
    void decisionCarriesExactlyTheContractProperties() {
        PermissionDecision allowed = new PermissionDecision("read", "Customer", true,
                PermissionDecision.REASON_ALLOWED_OWN, null);

        Map<String, Object> wire = allowed.toWire();

        assertConforms(wire, schemaFor("permission-decision"));
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
        // Outside the contract's reach: the schema types these as "string", so the wording is pinned
        // here rather than derived. A decision taken on either runtime must read the same.
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

    // ── permission-capability-list ───────────────────────────────────────────────────────────────

    @Test
    void capabilityListMatchesContractShape() {
        PermissionCapabilityList list = new PermissionCapabilityList(
                PermissionCapabilityList.ROLE_USER, PermissionCapabilityList.BASIS_USER,
                List.of(new PermissionCapabilityList.Resource("Customer",
                        PermissionCapabilityList.SCOPE_OWN, PermissionCapabilityList.EXPANDED_ACTIONS,
                        PermissionCapabilityList.REASON_RESOURCE_OWN)));

        Map<String, Object> wire = list.toWire();

        assertConforms(wire, schemaFor("permission-capability-list"));
        assertTrue(PermissionCapabilityList.ROLES.contains(wire.get("role")),
                "role is the contract vocabulary user|admin");
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

    // ── org-membership-scope ─────────────────────────────────────────────────────────────────────

    @Test
    void orgMembershipScopeMatchesContractShape() {
        OrgMembershipScope scope = new OrgMembershipScope(
                new OrgMembershipScope.Org(7L, "Acme", null), "member", 12L, List.of("HQ", "Sales"));

        Map<String, Object> wire = scope.toWire();

        assertConforms(wire, schemaFor("org-membership-scope"));
        assertTrue(OrgMembershipScope.ROLES.contains(wire.get("role")));
        Map<?, ?> org = (Map<?, ?>) wire.get("org");
        assertTrue(org.containsKey("description"), "description is required — emitted as null, not absent");
        assertNull(org.get("description"));
        assertRoundTrips(wire);
    }

    // ── authorization ────────────────────────────────────────────────────────────────────────────

    @Test
    void authorizationCarriesEverythingTheContractRequires() {
        AuthorizationReasons reasons = new AuthorizationReasons("wire_transfer", "R5", "block", false,
                List.of(new AuthorizationReasons.Check(GovernanceBinding.DENY_RISK_POLICY, false, "R5 refused")), null);

        Map<String, Object> wire = reasons.toWire();

        assertConforms(wire, schemaFor("authorization"));
        assertEquals("R5", wire.get("riskLevel"), "riskLevel comes from the frozen R0–R5 enum via $ref");
        assertTrue(GovernanceBinding.DENY_CHECKS.contains(
                        ((Map<?, ?>) ((List<?>) wire.get("checks")).get(0)).get("name")),
                "check names come from the frozen denial vocabulary (a Java-side invariant — the " +
                        "schema types a check name as a plain string)");
        assertRoundTrips(wire);
    }

    @Test
    void policyIsOmittedWhenThereIsNoRevisionedPolicy() {
        AuthorizationReasons reasons = new AuthorizationReasons("t", "R1", "auto", false, List.of(), null);
        assertTrue(!reasons.toWire().containsKey("policy"), "the runtime must not invent a policy revision");

        AuthorizationReasons withPolicy = new AuthorizationReasons("t", "R1", "auto", false, List.of(),
                new AuthorizationReasons.Policy("rev-1"));
        Map<String, Object> wire = withPolicy.toWire();
        assertConforms(wire, schemaFor("authorization"));
        assertEquals(Map.of("revision", "rev-1"), wire.get("policy"));
    }

    // ── the vendored contract itself ─────────────────────────────────────────────────────────────

    @Test
    void everyContractThisTestChecksIsResolvableFromTheVendoredRegistry() {
        Set<String> contracts = Set.of(
                "permission-decision", "permission-capability-list", "org-membership-scope", "authorization");
        for (String id : contracts) {
            // schemaFor throws when a contract is not in the vendored registry — the failure this
            // test exists to make loud, because a missing contract silently reverts to a hand-written
            // expectation.
            assertTrue(schemaFor(id).containsKey("properties") || schemaFor(id).containsKey("$ref"),
                    id + " resolved to something that is not a schema");
        }
    }
}
