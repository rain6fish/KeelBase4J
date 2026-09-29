// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.security.CallerAuthenticator;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * JV-35 — the host's identity is the deployment's, not this runtime's (ADR-0017 D6).
 *
 * <p>The shape being proved is the one an embedded deployment needs: the host authenticates its own
 * users, so it answers the two questions this runtime otherwise answers for itself — <em>how is this
 * request authenticated</em> and <em>who is that person here</em> — and the runtime stops asking for a
 * delegation token and stops consulting the tier-A directory it ships. Both answers arrive as ordinary
 * beans ({@link CallerAuthenticator}, {@link IdentityResolver}); the defaults carry
 * {@code @ConditionalOnMissingBean} and stand down.
 *
 * <p>What must <em>not</em> change is the half downstream. {@code CurrentPrincipal} and the whole
 * authorization chain are untouched by this: they still read an {@code AuthenticatedSubject} out of the
 * security context and still resolve it through the identity SPI. That is the property the first test
 * asserts from the outside — the role in the answer is the host's answer, and the request carried no
 * KeelBase token at all.
 *
 * <p>JV-35 —— **身份是部署方的，不是本运行时的**（ADR-0017 D6）。
 *
 * <p>这里要证的是嵌入式部署需要的那个形状：宿主认证自己的用户，于是由它回答本运行时原本自己回答的两个
 * 问题 —— **这条请求是怎么被认证的**，以及**那个人在这里是谁** —— 而运行时就**不再**索要委托令牌、
 * 也**不再**去查它自带的 tier-A 目录。两个答案都以普通 bean 的形式到达（`CallerAuthenticator`、
 * `IdentityResolver`）；默认实现带 `@ConditionalOnMissingBean`，就此让位。
 *
 * <p>**不许**变的是下游那一半。`CurrentPrincipal` 与整条授权链**一行未动**：它们照旧从 security context
 * 取 `AuthenticatedSubject`，照旧经身份 SPI 解析。第一条测试正是从外部断言这条性质 —— 答出来的角色是
 * **宿主给的**角色，而这条请求**根本没有**带 KeelBase 令牌。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class HostIdentitySeamTest {

    private static final String SELF = "/auth/me/permissions";

    /**
     * A host, in miniature: it authenticates from its own header, and it knows its own user's role.
     *
     * <p>Both beans are what a real adapter would declare — in RuoYi's case over its own token service
     * and its {@code sys_user}/{@code sys_role} tables. Nothing here reads a role from a request header,
     * which is the thing the old header adapter did and this one must not.
     *
     * <p>**一个微缩的宿主**：它按自己的请求头认证，并且知道自己用户的角色。
     *
     * <p>这两个 bean 就是真实适配器会声明的东西 —— 在 RuoYi 的情形里，它们落在它自己的 token service
     * 与 `sys_user`/`sys_role` 表之上。这里没有任何一处从请求头读角色，那正是旧的 header 适配器做过、
     * 而这个**不许**做的事。
     */
    @TestConfiguration
    static class HostSuppliedIdentity {

        @Bean
        CallerAuthenticator hostCallerAuthenticator() {
            return request -> {
                String user = request.getHeader("X-Host-User");
                return user == null ? null : new AuthenticatedSubject("host:" + user, null);
            };
        }

        @Bean
        IdentityResolver hostIdentityResolver() {
            return evidence -> {
                String subject = evidence.attribute(IdentityEvidence.SUBJECT);
                if (!"host:carol".equals(subject)) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "this host does not know that subject");
                }
                return new Principal("carol", Principal.ROLE_ADMIN);
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /**
     * The whole point: no delegation token, and the caller is still a caller — with the role the host
     * says, which is not the role the runtime's own directory would have given.
     */
    @Test
    void theHostsAuthenticationIsEnoughAndItsRolesAreTheOnesThatApply() {
        ResponseEntity<Map> response = rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(hostHeaders("carol")), Map.class);

        assertEquals(200, response.getStatusCode().value(),
                "the host established the caller; the runtime asks for nothing more");
        Map<String, Object> data = Envelopes.data(response.getBody());
        assertEquals(PermissionCapabilityList.ROLE_ADMIN, data.get("role"),
                "and the role is the one the host's directory gave, not tier A's");
    }

    /**
     * The other half of the same decision: the delegation token is no longer how this deployment is
     * entered. Pinned because it is the difference between "the host adapter is installed" and "the
     * runtime's own default is still standing" — without this assertion the test above would pass
     * under either, and the seam could be missing while the suite stayed green.
     */
    @Test
    void aValidDelegationTokenIsNotTheWayInForThisDeployment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser("alice", delegationSecret));

        assertEquals(401, rest.exchange(URI.create(SELF), HttpMethod.GET,
                        new HttpEntity<>(headers), Map.class).getStatusCode().value(),
                "this host's users are authenticated by the host, not by a token we minted");
    }

    /** A request the host did not authenticate proves nothing — the chain's own answer is 401. */
    @Test
    void aRequestTheHostDidNotAuthenticateIsRefused() {
        assertEquals(401, rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode().value());
    }

    /**
     * A subject the host authenticated but its own directory does not know is refused — the host
     * answers not only "who is this" but "is this somebody we have", and the runtime does not default
     * an unknown caller to a nominal role.
     */
    @Test
    void aSubjectTheHostsDirectoryDoesNotKnowIsRefused() {
        assertEquals(401, rest.exchange(URI.create(SELF), HttpMethod.GET,
                new HttpEntity<>(hostHeaders("nobody")), Map.class).getStatusCode().value());
    }

    private HttpHeaders hostHeaders(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Host-User", user);
        return headers;
    }
}
