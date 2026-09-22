// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a browser-hosted frontend needs from this runtime, beyond the endpoint shapes.
 *
 * <p>The golden-path judge drives this runtime from Node, and Node is not a browser: it never sends
 * a preflight, and it reads an error body for its own reasons rather than to decide what to show a
 * person. That gap hid two things until a real frontend was pointed at it — every cross-origin call
 * failed at the preflight, and a refusal reached the caller as "forbidden" with the reason the
 * runtime had already computed stripped off.
 *
 * <p>Both are asserted here at the wire, because both are only observable there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class FrontendAttachmentTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:10086";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CustomerRepository customers;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /**
     * A subject no shipped rule grants, so the <em>coarse</em> gate is what refuses — the one path
     * that carries the frozen {@code casl} basis.
     *
     * <p>It needs a probe because nothing in the runtime reaches it today: both declared roles read
     * {@code Customer}, so the only denial a caller can produce over HTTP is the row gate, which
     * deliberately claims no basis. Without this the wire shape of a rule denial would go untested
     * until the day someone adds a subject that is actually refused.
     */
    @TestConfiguration
    static class RuleDenialProbe {

        @RestController
        static class ProbeController {

            private final CurrentPrincipal principals;
            private final OwnershipGuard guard;

            ProbeController(CurrentPrincipal principals, OwnershipGuard guard) {
                this.principals = principals;
                this.guard = guard;
            }

            @GetMapping("/probe/rule-denial")
            Map<String, Object> deny() {
                guard.requireAction(principals.current(), "ProbeSubject",
                        PermissionAuthorizer.ACTION_READ);
                return Map.of("unreachable", true);
            }
        }
    }

    @Test
    void aPreflightIsAnsweredWithoutAToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(FRONTEND_ORIGIN);
        headers.setAccessControlRequestMethod(HttpMethod.GET);

        ResponseEntity<Map> res = rest.exchange("/customers", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Map.class);

        assertTrue(res.getStatusCode().is2xxSuccessful(),
                "a browser's preflight must not be refused — it carries no token by construction: "
                        + res.getStatusCode());
        assertEquals(FRONTEND_ORIGIN,
                res.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "the browser only proceeds if the origin is echoed back");
    }

    @Test
    void aCrossOriginRequestIsAllowed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(FRONTEND_ORIGIN);

        ResponseEntity<Map> res = rest.exchange("/app/capabilities", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertEquals(200, res.getStatusCode().value());
        assertEquals(FRONTEND_ORIGIN,
                res.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void anUnauthenticatedCallExplainsItself() {
        ResponseEntity<Map> res = rest.exchange("/customers", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);

        assertEquals(401, res.getStatusCode().value());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(401, ((Number) body.get("code")).intValue(),
                "the failure keeps the envelope shape before anything else");
        assertNotNull(body.get("reason"), "a 401 must say why, not just that it failed");
        assertNotNull(body.get("nextStep"), "and what the caller can do about it");
    }

    @Test
    void aRowDenialStatesTheReasonWithoutClaimingABasis() {
        customers.save(new Customer("Acme Industrial", "high", "alice"));
        Long bobCustomer = customers.save(new Customer("Globex Trading", "medium", "bob")).getId();

        ResponseEntity<Map> res = rest.exchange("/ai/chat", HttpMethod.POST,
                entity("alice", Map.of("message", "分析客户风险", "customerId", bobCustomer)),
                Map.class);

        assertEquals(403, res.getStatusCode().value(), "body was " + res.getBody());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(403, ((Number) body.get("code")).intValue(),
                "a refusal must not arrive re-wrapped as a success");
        assertNotNull(body.get("reason"), "the row gate must say which range was exceeded");
        assertNull(body.get("explanation"),
                "the frozen permission-decision names no basis for the row gate, so this runtime "
                        + "must not claim one");
    }

    @Test
    void aRuleDenialCarriesTheDenyBasis() {
        ResponseEntity<Map> res = rest.exchange("/probe/rule-denial", HttpMethod.GET,
                entity("alice", null), Map.class);

        assertEquals(403, res.getStatusCode().value(), "body was " + res.getBody());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(403, ((Number) body.get("code")).intValue(),
                "a refusal must not arrive re-wrapped as a success");
        assertNotNull(body.get("nextStep"), "a refused caller gets a way forward");

        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) body.get("explanation");
        assertNotNull(explanation,
                "a rule denial must carry its basis — the frontend turns it into the guidance line");
        assertEquals("casl", explanation.get("deniedBy"));
        assertNotNull(explanation.get("reason"));
    }

    private <T> HttpEntity<T> entity(String userId, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        }
        return new HttpEntity<>(body, headers);
    }
}
