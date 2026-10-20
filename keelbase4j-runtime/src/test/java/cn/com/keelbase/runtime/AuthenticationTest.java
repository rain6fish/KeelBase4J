// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.PermissionCapabilityList;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * JV-9 — authentication at the request entry.
 *
 * <p>What this has to show is that the caller's identity is <b>proved</b>, not asserted. The case
 * that used to pass and must now fail is the last one: a caller sending an admin role header while
 * holding an ordinary user's token. Under the header adapter that request was an administrator,
 * because the role came from the caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class AuthenticationTest {

    private static final String SELF = "/auth/me/permissions";

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void noTokenIsRefused() {
        assertEquals(401, get(headers -> {
        }), "an unauthenticated request must not reach a governed endpoint");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRefused() {
        assertEquals(401, get(headers -> headers.setBearerAuth(
                TestTokens.forUser("alice", "0123456789012345678901234567890123456789"))));
    }

    @Test
    void anExpiredTokenIsRefused() {
        assertEquals(401, get(headers -> headers.setBearerAuth(TestTokens.forSubject(
                "local:alice", null, delegationSecret, TestTokens.AUDIENCE, -60))));
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() {
        assertEquals(401, get(headers -> headers.setBearerAuth(TestTokens.forSubject(
                "local:alice", null, delegationSecret, "some-other-system", 3600))));
    }

    @Test
    void aValidTokenForAnUnknownSubjectIsRefused() {
        assertEquals(401, get(headers -> headers.setBearerAuth(
                TestTokens.forUser("nobody-this-deployment-knows", delegationSecret))),
                "a token can prove a subject; it cannot make this deployment know one");
    }

    @Test
    void aMalformedTokenIsRefused() {
        assertEquals(401, get(headers -> headers.setBearerAuth("not.a.jwt")));
    }

    /**
     * Every refusal names the check that refused and what the caller can do — the 401 is no longer
     * one sentence for five different causes.
     *
     * <p>The pair is in {@link #aSuccessfulCallCarriesNoFailureFields}: the same endpoint, a token
     * that verifies, and none of these keys appear.
     *
     * 每一种拒绝都点名是哪道检查拒的、以及调用方能做什么 —— 401 不再是五种不同原因共用一句话。
     *
     * <p>成对的那一半在 {@link #aSuccessfulCallCarriesNoFailureFields}：同一个端点、一枚验得过的令牌，
     * 这些键一个都不出现。
     */
    @Test
    void everyAuthenticationRefusalSaysWhatWhyAndNextStep() {
        Map<String, Object> missing = failureBody(headers -> {
        });
        assertRefusal(missing, "AUTH_MISSING_TOKEN", "未携带委托令牌");

        // "not.a.jwt" is three segments and fails at the signature; a token that is not three
        // segments fails earlier, at the format check. Both are shown because they are different
        // causes and used to read the same.
        //
        // "not.a.jwt" 是三段，会死在验签；不是三段的那枚更早死在格式检查。两枚都展示，因为它们是不同原因，
        // 此前却读起来一样。
        Map<String, Object> malformed = failureBody(
                headers -> headers.setBearerAuth("not-a-jwt"));
        assertRefusal(malformed, "AUTH_MALFORMED_TOKEN", "格式不合法");
        Map<String, Object> wrongSignatureShape = failureBody(
                headers -> headers.setBearerAuth("not.a.jwt"));
        assertRefusal(wrongSignatureShape, "AUTH_SIGNATURE_MISMATCH", "验签失败");

        Map<String, Object> wrongSecret = failureBody(headers -> headers.setBearerAuth(
                TestTokens.forUser("alice", "0123456789012345678901234567890123456789")));
        assertRefusal(wrongSecret, "AUTH_SIGNATURE_MISMATCH", "验签失败");
        assertFalse(String.valueOf(wrongSecret).contains(delegationSecret),
                "a refusal must never echo the deployment's secret");
        assertFalse(String.valueOf(wrongSecret).contains(
                        "0123456789012345678901234567890123456789"),
                "a refusal must never echo the presented secret either");

        Map<String, Object> expired = failureBody(headers -> headers.setBearerAuth(
                TestTokens.forSubject("local:alice", null, delegationSecret,
                        TestTokens.AUDIENCE, -60)));
        assertRefusal(expired, "AUTH_TOKEN_EXPIRED", "已过期");
        Map<String, Object> expiredWhy = explanation(expired);
        assertNotNull(expiredWhy.get("expiredAt"), "the refusal quotes the expiry it compared");
        assertNotNull(expiredWhy.get("now"), "and the clock it compared against");

        Map<String, Object> wrongAudience = failureBody(headers -> headers.setBearerAuth(
                TestTokens.forSubject("local:alice", null, delegationSecret,
                        "some-other-system", 3600)));
        assertRefusal(wrongAudience, "AUTH_AUDIENCE_MISMATCH", "audience 与本部署不符");
        Map<String, Object> audienceWhy = explanation(wrongAudience);
        assertEquals("keelbase4j", audienceWhy.get("expectedAudience"),
                "the refusal quotes the audience this deployment expects");
        assertEquals("some-other-system", audienceWhy.get("actualAudience"),
                "and the audience the token actually carries");
        assertTrue(String.valueOf(wrongAudience.get("nextStep")).contains("keelbase4j")
                        && String.valueOf(wrongAudience.get("nextStep")).contains("some-other-system"),
                "the next step names both values so the caller can reconcile them");

        Map<String, Object> unknownSubject = failureBody(headers -> headers.setBearerAuth(
                TestTokens.forUser("nobody-this-deployment-knows", delegationSecret)));
        assertRefusal(unknownSubject, "AUTH_UNKNOWN_SUBJECT", "本部署不认识该主体");
        assertEquals("local:nobody-this-deployment-knows",
                explanation(unknownSubject).get("subject"),
                "a valid token whose subject is unknown is not reported as a missing token");
    }

    /** The success half: the same endpoint, a verifiable token, none of the failure fields. */
    @Test
    void aSuccessfulCallCarriesNoFailureFields() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser("alice", delegationSecret));
        ResponseEntity<Map> response = rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNull(body.get("errorCode"), "a success claims no failure code");
        assertNull(body.get("reason"), "and no reason");
        assertNull(body.get("impact"), "and no impact");
        assertNull(body.get("nextStep"), "and no next step");
        assertNull(body.get("explanation"), "and no explanation");
    }

    /**
     * The header channel is gone. Under the adapter these three headers <em>were</em> the identity —
     * this exact request was an administrator named carol.
     */
    @Test
    void identityHeadersAloneNoLongerAuthenticate() {
        assertEquals(401, get(headers -> {
            headers.set("X-User-Id", "carol");
            headers.set("X-User-Role", "admin");
            headers.set("X-Oidc-Sub", "oidc|42");
        }), "identity headers cannot stand in for a token");
    }

    /**
     * The escalation this replaces: the caller announces a role — and even names another user —
     * when the runtime never granted either.
     *
     * <p>Under the header adapter this returned the admin capability list for carol, because both
     * identity and role were read straight off the request. Now the subject comes from the verified
     * token and the role from the local directory, so the caller's claims change nothing.
     */
    @Test
    void aCallerCannotGrantItselfARoleOrNameAnotherUser() {
        ResponseEntity<Map> asClaimedAdmin = rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(headersFor("bob", "admin")), Map.class);
        ResponseEntity<Map> plainly = rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(headersFor("bob", null)), Map.class);

        assertEquals(200, asClaimedAdmin.getStatusCode().value(), "bob is a valid user either way");
        Map<String, Object> claimedAdminData = Envelopes.data(asClaimedAdmin.getBody());
        Map<String, Object> plainlyData = Envelopes.data(plainly.getBody());
        assertEquals(PermissionCapabilityList.ROLE_USER, claimedAdminData.get("role"),
                "claiming admin in a header must not make the caller an admin");
        assertEquals(plainlyData.get("role"), claimedAdminData.get("role"),
                "and it must make no difference at all to the answer");
        assertNotEquals(PermissionCapabilityList.ROLE_ADMIN, claimedAdminData.get("role"));
    }

    /** bob's own token, plus — if asked for — a role the caller would like to have. */
    private HttpHeaders headersFor(String userId, String claimedRole) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        if (claimedRole != null) {
            headers.set("X-User-Role", claimedRole);
            headers.set("X-User-Id", "carol");
        }
        return headers;
    }

    /** Status of a GET with whatever the caller customised, and nothing else. */
    private int get(java.util.function.Consumer<HttpHeaders> customise) {
        HttpHeaders headers = new HttpHeaders();
        customise.accept(headers);
        return rest.exchange(URI.create(SELF), HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                .getStatusCode().value();
    }

    /** The parsed 401 body for a request that must be refused. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> failureBody(java.util.function.Consumer<HttpHeaders> customise) {
        HttpHeaders headers = new HttpHeaders();
        customise.accept(headers);
        ResponseEntity<String> response = rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(401, response.getStatusCode().value(), "the request must be refused: " + response);
        return (Map<String, Object>) Json.parse(response.getBody());
    }

    /** The three pieces every refusal carries, plus the structured explanation of the check. */
    private void assertRefusal(Map<String, Object> body, String code, String causeFragment) {
        assertEquals(code, body.get("errorCode"), "the failure names its check: " + body);
        String whatHappened = String.valueOf(body.get("message")) + " "
                + String.valueOf(body.get("reason"));
        assertTrue(whatHappened.contains(causeFragment),
                "the message and reason must explain this cause: " + whatHappened);
        assertNotNull(body.get("nextStep"), "every refusal says what to do next");
        assertNotNull(body.get("impact"), "and what did not happen");
        Map<String, Object> explanation = explanation(body);
        assertEquals("authentication", explanation.get("stage"));
        assertEquals(code, explanation.get("code"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> explanation(Map<String, Object> body) {
        Object explanation = body.get("explanation");
        assertTrue(explanation instanceof Map, "a refusal carries a structured explanation: " + body);
        return (Map<String, Object>) explanation;
    }
}
