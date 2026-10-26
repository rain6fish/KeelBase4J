// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import java.net.URI;
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

/**
 * A 403 names what was refused and what to do about it, without claiming a basis the contract does
 * not define.
 *
 * <p>The three cases are the three answers a caller can get from the authorization chain: the coarse
 * rule refused an action on a subject, the row gate refused this particular row, and nothing was
 * refused. The pair matters: a successful read carries none of the failure fields, so their presence
 * really means a refusal happened rather than being boilerplate.
 *
 * <p>The row gate still claims no {@code deniedBy}: the frozen {@code permission-decision} names no
 * basis for it, and inventing one would be this runtime minting contract vocabulary. The action and
 * subject are not a basis — they are what the guard was asked, and they are what makes the reason
 * checkable.
 *
 * 403 点名拒了什么、以及怎么办，同时不声称契约没有定义的依据。
 *
 * <p>三个用例就是调用方能从授权链得到的三种答案：粗粒度规则拒了这个主体上的这个动作；行闸拒了这一行；
 * 什么都没拒。成对的那一半要紧：成功的读不携带任何失败字段，故它们出现就真的意味着发生了一次拒绝，而不是
 * 套话。
 *
 * <p>行闸仍然不声称 `deniedBy`：冻结的 `permission-decision` 没有为它定义依据，编一个就是本运行时在
 * 铸造契约词表。动作与主体不是依据 —— 它们是守卫被问到的问题，也是让理由可核的东西。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class FailureDiagnosisTest {

    private static final String STATUS = "/keelbase/status";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CustomerRepository customers;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Test
    void aRuleDenialNamesTheActionSubjectAndFix() {
        ResponseEntity<Map> response = rest.exchange(URI.create(STATUS), HttpMethod.GET,
                entity("alice", null), Map.class);

        assertEquals(403, response.getStatusCode().value(), "body was " + response.getBody());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("FORBIDDEN_RULE", body.get("errorCode"));
        assertNotNull(body.get("reason"));
        assertNotNull(body.get("impact"), "the refusal says what did not happen");
        String nextStep = String.valueOf(body.get("nextStep"));
        assertTrue(nextStep.contains("IntegrationStatus") && nextStep.contains("read"),
                "the next step names the capability that was refused: " + nextStep);

        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) body.get("explanation");
        assertNotNull(explanation, "a rule denial carries its basis");
        assertEquals("casl", explanation.get("deniedBy"));
        assertEquals("IntegrationStatus", explanation.get("subject"),
                "the explanation names the subject the guard asked about");
        assertEquals("read", explanation.get("action"),
                "and the action it asked about");
    }

    @Test
    void aRowDenialSaysTheRangeAndTheFixWithoutClaimingABasis() {
        Long bobCustomer = customers.save(new Customer("Globex Trading", "medium", "bob")).getId();

        ResponseEntity<Map> response = rest.exchange(URI.create("/ai/chat"), HttpMethod.POST,
                entity("alice", Map.of("message", "分析客户风险", "customerId", bobCustomer)),
                Map.class);

        assertEquals(403, response.getStatusCode().value(), "body was " + response.getBody());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("ROW_OUT_OF_SCOPE", body.get("errorCode"),
                "a row refusal is distinguishable from a rule refusal");
        assertTrue(String.valueOf(body.get("reason")).contains("数据范围"));
        assertTrue(String.valueOf(body.get("nextStep")).contains("数据范围"),
                "the next step says which boundary the caller can act on");
        // The row gate still claims no basis — that is now expressed by `deniedBy` being absent rather
        // than by the whole `explanation` being absent — and the refusal names what it refused.
        //
        // 行闸**仍然不声称依据**——只是这个「不声称」现在由 `deniedBy` 的缺席表达，而不是整段
        // `explanation` 的缺席；同时它点名了拒的是什么。
        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) body.get("explanation");
        assertNotNull(explanation, "a row denial names the subject and action it refused");
        assertNull(explanation.get("deniedBy"),
                "the row gate claims no basis: the frozen permission-decision defines none for it");
        assertEquals("Customer", explanation.get("subject"));
        assertEquals("read", explanation.get("action"));
    }

    @Test
    void aSuccessfulGovernanceReadCarriesNoFailureFields() {
        ResponseEntity<Map> response = rest.exchange(URI.create(STATUS), HttpMethod.GET,
                entity("carol", null), Map.class);

        assertEquals(200, response.getStatusCode().value(), "body was " + response.getBody());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNull(body.get("errorCode"), "a success claims no failure code");
        assertNull(body.get("reason"));
        assertNull(body.get("impact"));
        assertNull(body.get("nextStep"));
        assertNull(body.get("explanation"));
    }

    private <T> HttpEntity<T> entity(String user, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, secret));
        return new HttpEntity<>(body, headers);
    }
}
