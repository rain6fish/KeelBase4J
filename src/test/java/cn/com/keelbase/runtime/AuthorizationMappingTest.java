// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.DelegationToken;
import cn.com.keelbase.protocol.GovernanceBinding;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.protocol.PermissionDecision;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * JV-2 — the identity seam mapped onto the main repo's frozen {@code permission-*} / identity wire
 * contracts (ADR-0004 D4).
 *
 * <p>What is asserted here is that the mapping is real rather than modelled: the runtime's row-level
 * permission is <em>derived</em> from the frozen decision, the capability list is served over the
 * wire in the frozen shape, the identity projects onto {@code sub}/{@code oidcSub}, and a refusal
 * carries the frozen structured reasons.
 *
 * <p>Not covered, deliberately: authentication itself (Spring Security's half — see
 * {@code docs/authorization-architecture.md} §7.1) and organization-tree data ranges (delivery
 * tier B). Row scope here is {@code own}, tier A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthorizationMappingTest {

    private static final Principal ALICE = new Principal("alice", "user");
    private static final Principal MANAGER = new Principal("carol", "manager");

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Autowired
    PermissionAuthorizer authorizer;

    @Autowired
    OwnershipGuard guard;

    @Autowired
    GovernedExecutionEngine engine;

    @TestConfiguration
    static class Probe {

        /** An irreversible external action — the R5 tool the spike otherwise has no reason to hold. */
        @Bean
        AiTool wireTransferTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "wire_transfer";
                }

                @Override
                public String description() {
                    return "test double: an irreversible external action";
                }

                @Override
                public String riskLevel() {
                    return "R5";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(null);
                }
            };
        }
    }

    @Test
    void decisionReproducesTheReferenceSemantics() {
        PermissionDecision own = authorizer.decide(ALICE, "read", "Customer");
        assertTrue(own.allowed());
        assertEquals(PermissionDecision.REASON_ALLOWED_OWN, own.reason());
        assertNull(own.deniedBy(), "an allowed decision names no deny basis");

        PermissionDecision denied = authorizer.decide(ALICE, "read", "Invoice");
        assertFalse(denied.allowed());
        assertEquals(PermissionDecision.REASON_DENIED_USER, denied.reason());
        assertEquals(PermissionDecision.DENIED_BY_CASL, denied.deniedBy());

        // A wildcard grant answers any subject; the wording follows the reference, which names the
        // all-subject case and otherwise reports the own-ownership phrasing.
        assertEquals(PermissionDecision.REASON_ALLOWED_ALL, authorizer.decide(MANAGER, "read", "all").reason());
        assertEquals(PermissionDecision.REASON_ALLOWED_OWN, authorizer.decide(MANAGER, "read", "Invoice").reason());
    }

    @Test
    void adminDeniedByANarrowerRuleSourceUsesTheAdminWording() {
        PermissionAuthorizer restricted = new PermissionAuthorizer(new AuthorizationRules(Map.of(
                PermissionCapabilityList.ROLE_ADMIN,
                List.of(new AuthorizationRules.Rule("Customer", PermissionCapabilityList.MANAGE, null)))));

        PermissionDecision denied = restricted.decide(new Principal("root", "admin"), "read", "Invoice");

        assertFalse(denied.allowed());
        assertEquals(PermissionDecision.REASON_DENIED_ADMIN, denied.reason());
    }

    @Test
    void capabilityListReproducesTheReferenceShape() {
        PermissionCapabilityList user = authorizer.describe(ALICE);
        assertEquals(PermissionCapabilityList.ROLE_USER, user.role());
        assertEquals(PermissionCapabilityList.BASIS_USER, user.basis());
        assertEquals(List.of("Customer", "FollowUp"),
                user.resources().stream().map(PermissionCapabilityList.Resource::subject).toList(),
                "resources are sorted by subject");
        for (PermissionCapabilityList.Resource resource : user.resources()) {
            assertEquals(PermissionCapabilityList.SCOPE_OWN, resource.scope());
            assertEquals(PermissionCapabilityList.EXPANDED_ACTIONS, resource.actions());
            assertEquals(PermissionCapabilityList.REASON_RESOURCE_OWN, resource.reason());
        }

        PermissionCapabilityList admin = authorizer.describe(MANAGER);
        assertEquals(PermissionCapabilityList.ROLE_ADMIN, admin.role());
        assertEquals(PermissionCapabilityList.BASIS_ADMIN, admin.basis());
        assertEquals(1, admin.resources().size(), "the wildcard grant is one resource");
        assertEquals(PermissionCapabilityList.SUBJECT_ALL, admin.resources().get(0).subject());
        assertEquals(PermissionCapabilityList.SCOPE_ALL, admin.resources().get(0).scope());
    }

    @Test
    void identityProjectsOntoTheFrozenIdentityContracts() {
        assertEquals("local:alice", ALICE.subject(), "no SSO subject ⇒ the local identity key");

        Principal sso = new Principal("alice", "user", "oidc|42", null);
        assertEquals("oidc|42", sso.subject(), "an OIDC subject is the mapping key");

        // The key the runtime derives is the one the delegation-token contract carries.
        String token = DelegationToken.sign(
                Map.of("sub", sso.subject(), "oidcSub", "oidc|42", "aud", "erp", "iss", "keelbase"),
                1000L, 1300L, "secret");
        DelegationToken.Result verified = DelegationToken.verify(token, "secret", "erp", 1100L);
        assertTrue(verified.ok());
        assertEquals(sso.subject(), verified.payload().get("sub"));
    }

    @Test
    void rowLevelAccessIsDerivedFromTheDecisionNotFromRoleChecks() {
        assertDoesNotThrow(() -> guard.requireAccess(ALICE, "Customer", "read", "alice"));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> guard.requireAccess(ALICE, "Customer", "read", "bob"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(),
                "own scope + a foreign row ⇒ refused");
        assertEquals(PermissionDecision.REASON_DENIED_USER,
                authorizer.decide(ALICE, "read", "Invoice").reason());

        assertDoesNotThrow(() -> guard.requireAccess(MANAGER, "Customer", "read", "bob"),
                "scope=all from the wildcard grant reaches any row");
    }

    @Test
    void roleVocabularyIsNormalisedToTheContract() {
        assertEquals(PermissionCapabilityList.ROLE_ADMIN, MANAGER.role(),
                "the spike's manager alias maps onto the contract's admin role");
        assertTrue(MANAGER.isManager());
        assertEquals(PermissionCapabilityList.ROLE_USER, new Principal("x", "something-else").role(),
                "an unrecognised role is a plain user, never an accidental grant");
    }

    @Test
    void blockedCallCarriesTheFrozenAuthorizationReasons() {
        ExecutionOutcome outcome = engine.execute("wire_transfer", Map.of(), ALICE);

        assertEquals("blocked", outcome.status());
        Map<?, ?> wire = (Map<?, ?>) outcome.data();
        assertEquals("wire_transfer", wire.get("tool"));
        assertEquals("R5", wire.get("riskLevel"));
        assertEquals("block", wire.get("riskStrategy"));
        assertEquals(Boolean.FALSE, wire.get("requiresConfirmation"));
        Map<?, ?> check = (Map<?, ?>) ((List<?>) wire.get("checks")).get(0);
        assertEquals(GovernanceBinding.DENY_RISK_POLICY, check.get("name"));
        assertEquals(Boolean.FALSE, check.get("ok"));
    }

    @Test
    void capabilityEndpointServesTheFrozenContract() {
        ResponseEntity<Map> user = rest.exchange("/auth/me/permissions", HttpMethod.GET,
                entity("alice", null), Map.class);
        assertEquals(200, user.getStatusCode().value());
        assertEquals(Set.of("role", "basis", "resources"), user.getBody().keySet());
        assertEquals(PermissionCapabilityList.ROLE_USER, user.getBody().get("role"));
        assertEquals(PermissionCapabilityList.BASIS_USER, user.getBody().get("basis"));

        // carol's role is not asserted by the caller; it is what this deployment maps her to.
        ResponseEntity<Map> admin = rest.exchange("/auth/me/permissions", HttpMethod.GET,
                entity("carol", null), Map.class);
        assertEquals(200, admin.getStatusCode().value());
        assertEquals(PermissionCapabilityList.ROLE_ADMIN, admin.getBody().get("role"));

        ResponseEntity<Map> anonymous = rest.exchange("/auth/me/permissions", HttpMethod.GET,
                entity(null, null), Map.class);
        assertEquals(401, anonymous.getStatusCode().value(), "nothing runs anonymously");
    }

    @Test
    void aTokenCarryingAnOidcSubjectStillResolvesToTheLocalIdentity() {
        ResponseEntity<Map> res = rest.exchange("/auth/me/permissions", HttpMethod.GET,
                entity("alice", "oidc|42", null), Map.class);
        assertEquals(200, res.getStatusCode().value(),
                "an SSO subject on the token does not change the decision shape");
    }

    /** A request as that user; {@code userId == null} sends no token at all. */
    private HttpEntity<Object> entity(String userId, Object body) {
        return entity(userId, null, body);
    }

    /** A request whose token also carries an SSO subject. */
    private HttpEntity<Object> entity(String userId, String oidcSubject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.setBearerAuth(TestTokens.forSubject(
                    "local:" + userId, oidcSubject, delegationSecret));
        }
        return new HttpEntity<>(body, headers);
    }
}
