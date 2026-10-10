// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import tools.jackson.databind.json.JsonMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * G1 / S4 acceptance — the trust loop, end to end:
 * Identity → Permission → Governance → Confirmation → Audit → Revoke.
 *
 * <p>Everything runs through the HTTP boundary, so this is the S3 proof too: the application is a
 * standalone Spring app serving its own API, with no generator involved.
 *
 * <p>Callers authenticate with a delegation token, so the loop starts the way a real one does.
 * Nothing here passes a role: the caller cannot state one, and the runtime maps the token's subject
 * to a local user and role instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class TrustLoopTest {

    @Autowired
    TestRestTemplate rest;

    /**
     * Responses arrive in the api-response envelope, so the payload is read through {@code data}.
     *
     * <p>Spring Boot 4 moved to Jackson 3 ({@code com.fasterxml.jackson} → {@code tools.jackson})
     * and auto-configures a {@code JsonMapper} rather than a bare {@code ObjectMapper}.
     */
    @Autowired
    JsonMapper json;

    @Autowired
    CustomerRepository customers;

    @Autowired
    FollowUpRepository followUps;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void s4_trust_loop() {
        Long aliceCustomer = customers.save(new Customer("Acme Industrial", "high", "alice")).getId();
        Long bobCustomer = customers.save(new Customer("Globex Trading", "medium", "bob")).getId();

        // 1. Read tool (R1) auto-executes — no confirmation. The non-streaming answer is the frozen
        // `chat-response`, so the tool's own answer reaches the caller the way the reference delivers
        // it: in the reply, which is the only place this object has for one.
        //
        // 读工具（R1）自动执行 —— 不需确认。非流式答案是冻结的 `chat-response`，所以工具**自己的答案**像
        // 参照实现那样到达调用方：**在 reply 里** —— 那是这个对象**唯一**放它的地方。
        int held = Pending.count(rest, "alice", delegationSecret);
        Map<String, Object> read = chat("alice", "分析客户风险", aliceCustomer);
        assertTrue(String.valueOf(read.get("reply")).contains("level=critical"),
                "the read ran and its answer is reported: " + read.get("reply"));
        assertEquals(held, Pending.count(rest, "alice", delegationSecret),
                "a read is not gated, so it holds nothing back");

        // 2. Write tool (R3) is gated — NOT executed before a human approves. The waiting row is the
        // evidence, and it is where a caller finds the token now that the answer carries none.
        //
        // 写工具（R3）被闸住 —— 在有人批准之前**不**执行。**那行待确认**就是证据，也是答案不再带 token
        // 之后调用方找到 token 的地方。
        chat("alice", "创建跟进任务，提醒续约", aliceCustomer);
        assertEquals(held + 1, Pending.count(rest, "alice", delegationSecret),
                "a write tool must be proposed and wait on a human");
        String writeToken = Pending.token(rest, "alice", delegationSecret);
        assertEquals(0, followUps.findByCustomerIdAndDeletedAtIsNull(aliceCustomer).size(),
                "nothing may be written before confirmation");

        // 3. Approve → executed, side effect recorded.
        ExecutionOutcome approved = confirm("alice", writeToken, "approve", 200);
        assertEquals("executed", approved.status());
        assertNotNull(approved.effectId(), "a write must record a side effect");
        assertEquals(1, followUps.findByCustomerIdAndDeletedAtIsNull(aliceCustomer).size());

        // 4. Audit chain is intact — read as an administrator, because that is who the chain is for:
        // the answer carries the rows themselves now, and the corpus replays this object with
        // `actor: admin` (see `AuditController`).
        ResponseEntity<Map> verify = rest.exchange("/audit/verify", HttpMethod.GET,
                entity("carol", null), Map.class);
        assertEquals(200, verify.getStatusCode().value());
        Map<String, Object> verifyData = Envelopes.data(verify.getBody());
        assertEquals(Boolean.TRUE, verifyData.get("valid"), "audit hash chain must verify");

        // 5. Revoke → local compensation (soft delete), status revoked.
        Map<?, ?> revoked = delete("/ai/tool-effects/" + approved.effectId(), "alice");
        assertEquals("revoked", revoked.get("revokeStatus"));
        assertEquals(0, followUps.findByCustomerIdAndDeletedAtIsNull(aliceCustomer).size(),
                "revoke must remove the follow-up from the live set");

        // 6. Permission: a regular user cannot read another user's customer — 403, zero side effect.
        ResponseEntity<Map> forbidden = rest.exchange(
                "/ai/chat", HttpMethod.POST,
                entity("alice", Map.of("message", "分析客户风险", "customerId", bobCustomer)),
                Map.class);
        assertEquals(403, forbidden.getStatusCode().value(),
                "cross-user access must be denied; body was " + forbidden.getBody());

        // 6b. A manager may read any customer.
        Map<String, Object> asManager = chat("carol", "分析客户风险", bobCustomer);
        assertTrue(String.valueOf(asManager.get("reply")).contains("level="),
                "a manager's read runs, and its answer is reported: " + asManager.get("reply"));
    }

    /** One turn over the wire, answering the frozen {@code chat-response}: no status, no token. */
    private Map<String, Object> chat(String userId, String message, Long customerId) {
        ResponseEntity<Map> res = rest.postForEntity(
                "/ai/chat",
                entity(userId, Map.of("message", message, "customerId", customerId)),
                Map.class);
        assertEquals(200, res.getStatusCode().value(), "POST /ai/chat");
        return Envelopes.data(res.getBody());
    }

    private ExecutionOutcome confirm(String userId, String token, String decision, int expectedStatus) {
        ResponseEntity<Map> res = rest.postForEntity(
                "/ai/confirmations/" + token,
                entity(userId, Map.of("decision", decision)),
                Map.class);
        assertEquals(expectedStatus, res.getStatusCode().value(), "POST /ai/confirmations");
        return json.convertValue(Envelopes.data(res.getBody()), ExecutionOutcome.class);
    }

    private Map<?, ?> delete(String path, String userId) {
        ResponseEntity<Map> res = rest.exchange(path, HttpMethod.DELETE, entity(userId, null), Map.class);
        assertEquals(200, res.getStatusCode().value(), "DELETE " + path);
        return Envelopes.data(res.getBody());
    }

    /** A request as that user; {@code userId == null} sends no token at all. */
    private HttpEntity<Object> entity(String userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        }
        return new HttpEntity<>(body, headers);
    }
}
