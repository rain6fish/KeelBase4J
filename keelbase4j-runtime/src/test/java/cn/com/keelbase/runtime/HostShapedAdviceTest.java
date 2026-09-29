// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.Order;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * S9 — a host's own exception handling must not be able to turn this runtime's refusal into a success.
 *
 * <p>An embedded host brings an exception handler of its own, and the one RuoYi brings catches
 * {@code RuntimeException} — a supertype of both exceptions this runtime refuses with. Spring picks
 * between advices by order, so a host advice that comes first does not merely answer alongside ours,
 * it <em>takes the refusal</em>: the host writes its own success envelope, {@code ApiResponseAdvice}
 * then wraps that (its exemption is decided by who produced the body, and this body is not ours), and
 * the caller reads HTTP 200 with the refusal nested inside {@code data}. The refusal is real; the
 * contract is not what arrives.
 *
 * <p>This is the failure {@code ApiResponseAdvice}'s javadoc says it exists to prevent — a 403 turning
 * into a 200 with the refusal buried in {@code data} — entered through the one door it does not cover,
 * because the body is shaped by someone else. Nothing in the runtime's own context can see it: with a
 * single advice the contract holds, which is why this test carries a second one. What it pins is what
 * a caller can rely on: whatever else is in the process, a refusal arrives as a refusal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class HostShapedAdviceTest {

    private static final String REFUSED = "/probe/rule-denial";

    /**
     * The host's global handler, in miniature — broad exception types, catching what this runtime
     * refuses with.
     *
     * <p>It declares an order, and it must: measured both ways, the defect is decided by whichever
     * advice Spring reaches first, so an unordered copy of the host lands on either outcome depending
     * on registration order — in this repository's own context it happened to favour us and the
     * refusal came through. A guard that can pass by luck is not a guard, and a host is entitled to
     * declare an order ({@code @Order(0)} is ahead of anything unordered), so the worst case is the
     * one to hold: an earlier host advice must not be able to take our refusal.
     */
    @TestConfiguration
    static class HostGlobalHandler {

        @RestControllerAdvice
        @Order(0)
        static class HostShaped {

            @ExceptionHandler(RuntimeException.class)
            ResponseEntity<Map<String, Object>> anything(RuntimeException failure) {
                return ResponseEntity.ok(Map.of(
                        "code", 200,
                        "data", Map.of("msg", String.valueOf(failure.getMessage()), "code", 500)));
            }
        }
    }

    /**
     * A refusal no shipped rule path reaches: the coarse gate on a subject no role grants. Same probe
     * as the frontend-attachment test's, for the same reason — otherwise the wire shape of a rule
     * denial would be untestable until someone adds a subject that is actually refused.
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

            @GetMapping(REFUSED)
            Map<String, Object> deny() {
                guard.requireAction(principals.current(), "ProbeSubject",
                        PermissionAuthorizer.ACTION_READ);
                return Map.of("unreachable", true);
            }
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void aRefusalReachesTheCallerAsARefusalEvenWithAHostHandlerPresent() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser("alice", delegationSecret));

        ResponseEntity<Map> response = rest.exchange(URI.create(REFUSED), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertNotEquals(200, response.getStatusCode().value(),
                "a refusal must not arrive as a success: " + response.getBody());
        assertEquals(403, response.getStatusCode().value(), "body was " + response.getBody());
        assertEquals(403, ((Number) response.getBody().get("code")).intValue(),
                "and the envelope agrees with the status, rather than carrying the refusal inside data");
    }
}
