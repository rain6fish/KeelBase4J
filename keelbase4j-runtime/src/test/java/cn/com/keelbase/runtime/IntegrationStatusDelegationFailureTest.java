// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The delegation dimension red, and only it: a blank delegation audience.
 *
 * <p>The audience is the claim that refuses a token minted for another system. With it blank, the
 * chain still <em>verifies</em> — a token addressed to the empty audience passes an empty expected
 * audience — which is exactly why this is the honest failure to show: the signature check alone
 * cannot tell an addressed token from an unaddressed one. The local probe reports
 * {@code audienceConfigured: false} and turns the dimension red; the inbound token that verified does
 * not stand in for a configured target system. The other three dimensions are asserted green.
 *
 * <p>A blank <em>secret</em> would be the more obvious failure, but it cannot be presented through
 * this endpoint: the frozen signer refuses an empty HMAC key, so no inbound token can be minted to
 * reach the report. The audience failure is the reachable one, and it is a real one.
 *
 * 委托维度红、且只有它：空的委托 audience。
 *
 * <p>audience 是拒绝「铸给别的系统」的令牌的那条声明。它为空时，链仍然**验得过** —— 一枚发给空 audience
 * 的令牌会通过空期望 audience 的检查 —— 这正是这个故障值得展示的原因：光看签名，分不出一枚**有收件方**的
 * 令牌与一枚**没有收件方**的令牌。本地探针报 `audienceConfigured: false`，维度转红；验过的入站令牌不冒充
 * 一个已配置的目标系统。另外三个维度断言为绿。
 *
 * <p>空的 **secret** 是更显眼的故障，却无法经这个端点呈现：冻结的签发器拒绝空 HMAC 密钥，故铸不出能到达
 * 报告的入站令牌。audience 故障是**可达**的那一个，也是真实的那一个。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:status-delegation;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "keelbase.delegation.audience="
})
class IntegrationStatusDelegationFailureTest {

    private static final String ADMIN = "carol";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Value("${keelbase.delegation.audience}")
    String audience;

    @Test
    void aBlankAudienceTurnsOnlyTheDelegationDimensionRed() {
        IntegrationStatusProbe.seedAudit(audit);
        assertEquals("", audience, "this test's premise is the blank audience the deployment was given");

        // The token is addressed to the same blank audience, so the inbound verification succeeds.
        // What fails is the reading that this deployment has a target system at all.
        //
        // 令牌同样发给那个空 audience，故入站验签成功。失败的读数是：这个部署**根本没有目标系统**。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forSubject("local:" + ADMIN, null, secret, "", 3600));
        ResponseEntity<String> response = rest.exchange(URI.create(IntegrationStatusProbe.PATH),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(200, response.getStatusCode().value(), "the request itself must verify");

        Map<String, Object> report = IntegrationStatusProbe.data(response);
        Map<String, Object> delegation = IntegrationStatusProbe.block(report, "delegation");
        assertEquals("error", delegation.get("status"));
        assertEquals(Boolean.TRUE, delegation.get("secretConfigured"));
        assertEquals(Boolean.FALSE, delegation.get("audienceConfigured"));
        assertEquals(Boolean.FALSE, delegation.get("selfTest"));
        assertEquals(Boolean.TRUE, delegation.get("inboundVerified"),
                "the request did verify; the failure is that it was addressed to nothing");

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) IntegrationStatusProbe.block(report, "health").get("errors");
        assertTrue(errors.stream().anyMatch(error -> error.contains("audience is blank")),
                "the error must say which setting is missing: " + errors);

        assertEquals("ok", IntegrationStatusProbe.block(report, "tools").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "confirmation").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "audit").get("status"));
        assertEquals("error", IntegrationStatusProbe.block(report, "health").get("status"));
    }
}
