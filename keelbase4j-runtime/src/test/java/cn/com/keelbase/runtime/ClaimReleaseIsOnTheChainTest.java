// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Releasing a stranded write hold leaves one row of the same chain, and both read faces show it.
 *
 * <p>A hold whose lease ran out is taken over by the next caller, because the alternative is a key
 * nobody can ever use again. That takeover used to be silent: the hold changed hands and the chain
 * said nothing, so "who abandoned this write, and who took it over" had no answer. The contract makes
 * the act the record — {@code claim_release}, no {@code outcome}, because the hold's conclusion stays
 * unknown — and this class pins the three places that must agree: exactly one such row, the
 * conversation's timeline showing it as a notice, and the administrator's read face returning it.
 *
 * <p>牵走一份租约跑完的写占位，会在**同一条链**上留下一行，而两个读面都看得见它。
 *
 * <p>租约跑完的占位会被下一个调用方接管，因为另一条路是一个**再也没人能用**的键。这次接管过去是
 * **无声的**：占位换了主人，链上什么都没说，于是「谁放弃了这次写、谁又接了过去」没有答案。契约把**这个动作**
 * 定为记录 —— `claim_release`、**不写** `outcome`，因为占位的结论保持未知 —— 而本类钉住三处必须一致的地方：
 * **恰好一行**这样的记录、会话时间线把它显示成一条 notice、以及管理员读面能取回它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ClaimReleaseIsOnTheChainTest {

    private static final String ALICE = "alice";
    private static final String ADMIN = "carol";
    private static final String WRITE = "创建跟进任务，提醒续约";
    private static final String TOOL = "create_followup";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JsonMapper json;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    CustomerRepository customers;

    @Autowired
    SideEffectRepository sideEffects;

    @Autowired
    WriteClaimRepository claimRepository;

    @Autowired
    AuditLogRepository auditRows;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void aStrandedHoldIsTakenOverOnTheChainAndReadBackFromBothFaces() {
        Long customer = customers.save(new Customer("Stranded Co", "low", ALICE)).getId();
        ExecutionOutcome first = approve(aliceProposes(customer).token());

        WriteClaim settled = claimRepository.findAll().stream()
                .filter(claim -> first.effectId().equals(claim.getEffectId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the execution took a claim and settled it"));
        String key = settled.getIdempotencyKey();
        // The state a second call sees after the first process died: the effect it never recorded is
        // gone, and the claim it never settled is back, still `claimed` and past its lease.
        //
        // 第一个进程死掉之后，第二次调用看到的状态：它从未记下的 effect 不在，它从未落定的占位回来了，
        // 仍是 `claimed`、已过租约。
        sideEffects.deleteById(first.effectId());
        claimRepository.deleteById(settled.getId());
        Long strandedId = claimRepository.saveAndFlush(new WriteClaim(key, ALICE, TOOL)).getId();
        ageTheClaim(key);

        Proposal again = aliceProposes(customer);
        approve(again.token());

        List<AuditLog> rows = auditRows.findAll().stream()
                .filter(row -> "claim_release".equals(row.getAction()))
                .filter(row -> row.getDetail() != null
                        && row.getDetail().contains("#" + strandedId))
                .toList();
        assertEquals(1, rows.size(), "the takeover is exactly one row on the chain");
        AuditLog row = rows.get(0);
        assertNull(row.getOutcome(),
                "no outcome: this row is not a tool verdict and the hold's conclusion stays unknown");
        assertEquals(TOOL, row.getTool(), "the hold's tool is on the row, so it can be filtered by it");
        assertEquals(again.conversationId(), row.getConversationId(),
                "the row belongs to the conversation that took the hold over");
        assertTrue(row.getDetail().contains("held by user " + ALICE),
                "the previous owner is named: " + row.getDetail());
        assertTrue(row.getDetail().contains("take-over by user " + ALICE),
                "and so is the taker: " + row.getDetail());
        assertTrue(row.getDetail().contains("lease expired"),
                "and the reason it was released: " + row.getDetail());

        Map<String, Object> trace = Vectors.map(getData(
                "/ai/conversations/" + again.conversationId() + "/trace", ALICE));
        List<Map<String, Object>> notices = ((List<?>) trace.get("steps")).stream()
                .map(Vectors::map)
                .filter(step -> "notice".equals(step.get("type")))
                .toList();
        assertEquals(1, notices.size(),
                "the timeline shows the released hold as one notice, not as a tool call: " + trace);
        String content = String.valueOf(notices.get(0).get("content"));
        assertTrue(content.contains("write hold"),
                "the notice says in English what happened: " + content);
        assertTrue(content.contains("写占位"),
                "and in Chinese: " + content);
        assertEquals(row.getDetail(), notices.get(0).get("detail"),
                "the specifics travel unchanged from the chain to the timeline");

        List<?> logged = rowsOf("/audit/logs?userId=" + ALICE, ADMIN);
        List<Map<String, Object>> released = logged.stream()
                .map(Vectors::map)
                .filter(entry -> "claim_release".equals(entry.get("action"))
                        && String.valueOf(entry.get("detail")).contains("#" + strandedId))
                .toList();
        assertEquals(1, released.size(), "the administrator's read face returns it too: " + logged);
        assertNull(released.get(0).get("outcome"),
                "with the outcome still absent on the way out");
    }

    /** A caller's turn that proposes the write, plus where to find both halves of it. */
    private record Proposal(String token, String conversationId) {
    }

    private Proposal aliceProposes(Long customerId) {
        int before = Pending.count(rest, ALICE, delegationSecret);
        ResponseEntity<Map> proposed = rest.postForEntity("/ai/chat",
                entity(Map.of("message", WRITE, "customerId", customerId)), Map.class);
        assertEquals(200, proposed.getStatusCode().value(), "the turn is served: " + proposed.getBody());
        assertEquals(before + 1, Pending.count(rest, ALICE, delegationSecret),
                "a write waits for a decision, which is one more pending row for this caller");
        Map<String, Object> body = Envelopes.data(proposed.getBody());
        String conversationId = (String) body.get("conversationId");
        assertNotNull(conversationId, "the frontends keep this and send it back");
        return new Proposal(Pending.token(rest, ALICE, delegationSecret), conversationId);
    }

    private ExecutionOutcome approve(String token) {
        ResponseEntity<Map> decided = rest.postForEntity("/ai/confirmations/" + token,
                entity(Map.of("decision", "approve")), Map.class);
        return json.convertValue(Envelopes.data(decided.getBody()), ExecutionOutcome.class);
    }

    /** Age the row by hand: the only way to reach the stale branch without waiting the lease out. */
    private void ageTheClaim(String key) {
        transactions.executeWithoutResult(status -> entityManager
                .createQuery("update WriteClaim c set c.claimedAt = :old where c.idempotencyKey = :key")
                .setParameter("old", Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 60_000L))
                .setParameter("key", key)
                .executeUpdate());
    }

    private List<?> rowsOf(String path, String user) {
        ResponseEntity<String> response = rest.exchange(URI.create(path), HttpMethod.GET,
                new HttpEntity<>(headers(user)), String.class);
        assertEquals(200, response.getStatusCode().value(), "the read must be served: " + response);
        return (List<?>) Envelopes.data(Vectors.map(Json.parse(response.getBody())));
    }

    private Object getData(String path, String user) {
        ResponseEntity<String> response = rest.exchange(URI.create(path), HttpMethod.GET,
                new HttpEntity<>(headers(user)), String.class);
        assertEquals(200, response.getStatusCode().value(), "GET " + path + ": " + response);
        return Envelopes.data(Vectors.map(Json.parse(response.getBody())));
    }

    private HttpHeaders headers(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return headers;
    }

    private HttpEntity<Object> entity(Object body) {
        return new HttpEntity<>(body, headers(ALICE));
    }
}
