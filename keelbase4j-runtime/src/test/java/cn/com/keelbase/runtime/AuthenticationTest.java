// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
