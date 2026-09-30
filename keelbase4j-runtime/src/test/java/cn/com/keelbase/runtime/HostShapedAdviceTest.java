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

    /** A path nothing is mapped to — the 404 door. 没有任何映射的路径 —— 那扇 404 的门。 */
    private static final String UNMAPPED = "/no-such-path-so-nothing-matches";

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

            /**
             * The host does not stop at {@code RuntimeException}. RuoYi declares a second handler for
             * {@code Exception}, and a path no handler matches arrives as
             * {@code NoResourceFoundException} — a {@code ServletException}, so it is this one that
             * catches it. A copy that declared only the runtime supertype would not be the host, and
             * would leave the wider door untested.
             *
             * <p>宿主并不止步于 {@code RuntimeException}。RuoYi 另有一个 {@code Exception} 的处理器，
             * 而没有处理器接手的路径以 {@code NoResourceFoundException} 到达 —— 那是 {@code
             * ServletException}，故接住它的是这一条。只声明运行时超类的副本不算宿主，也会让那扇更宽的
             * 门无人测。
             */
            @ExceptionHandler(Exception.class)
            ResponseEntity<Map<String, Object>> anythingElse(Exception failure) {
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

    /**
     * The same door, wider than refusals: a path that does not exist.
     *
     * <p>Measured against the running host on 2026-09-30, an unknown path with a valid token answers
     * HTTP 200 carrying {@code code:200} and the success message, with the real 404 buried in
     * {@code data} — the host's generic handler produces the body, and {@code ApiResponseAdvice} labels
     * it a success because the body is not ours. Ordering cannot reach this one: this runtime's advice
     * declares no handler for {@code NoResourceFoundException}, so there is no second claimant for the
     * order to rank. Covering the type is the fix, and this is its guard.
     *
     * <p>同一扇门，比拒绝更宽：一条不存在的路径。2026-09-30 在运行中的宿主上实测，带有效 token 打未知
     * 路径答 HTTP 200、带 {@code code:200} 与成功文案，真正的 404 埋在 {@code data} 里 —— 正文由宿主的
     * 泛型处理器产出，而 {@code ApiResponseAdvice} 因「正文不是我们的」把它标成成功。排序到不了这里：本
     * 运行时的 advice 对 {@code NoResourceFoundException} 未声明处理器，没有第二个主张者让排序去排。
     * 修法是**覆盖该类型**，本条即它的守卫。
     */
    @Test
    void aPathThatDoesNotExistReachesTheCallerAsNotFoundEvenWithAHostHandlerPresent() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser("alice", delegationSecret));

        ResponseEntity<Map> response = rest.exchange(URI.create(UNMAPPED), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertNotEquals(200, response.getStatusCode().value(),
                "a not-found must not arrive as a success: " + response.getBody());
        assertEquals(404, response.getStatusCode().value(), "body was " + response.getBody());
        assertEquals(404, ((Number) response.getBody().get("code")).intValue(),
                "and the envelope agrees with the status");
    }
}
