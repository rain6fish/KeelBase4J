// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The same call twice writes one row and records one effect (seam S11).
 *
 * <p>The ledger's idempotency key is content-derived, and its javadoc promises that re-running the same
 * call "reuses the existing effect instead of writing twice". The write did not honour it: the tool
 * wrote a row every time and the ledger then deduped, so the second identical call left a follow-up
 * <em>named by no effect</em> — a write with no revocation path, invisible to the ledger and
 * unreachable by {@code revoke}. Measured on a host, and only by rerunning: a single pass writes each
 * distinct call once, so nothing about the first time can show this.
 *
 * <p>The fix is to ask before executing rather than after — the shape the reference implementation
 * already had (it probes by key and returns early). The assertions below are what makes the promise
 * true rather than merely stated: the second call reports the <em>same</em> effect and adds no row.
 *
 * <p>The second case is the other half of "before": an effect that was revoked keeps its key, so the
 * same call is refused instead of quietly remade under it. Remaking it would either write an untracked
 * row again or silently do nothing, and neither is an answer a caller can act on.
 *
 * <p>**Known and not covered here:** two identical calls <em>at the same instant</em> can both probe and
 * miss and both write. The reference arbitrates that with a write claim taken before execution; this
 * runtime has no such row. Recorded as open rather than claimed.
 *
 * <p>同一调用做两次，只写一行、只记一条 effect（缝 S11）。
 *
 * <p>账本的幂等键由内容推导，它的 javadoc 承诺重复同一次调用会「复用已有 effect、而不是写两次」。**写没有
 * 遵守它**：工具每次都写一行，账本事后才去重，于是第二次相同调用留下一条**没有任何 effect 指向**的跟进
 * ——一次**没有撤销路径**的写，账本看不见、`revoke` 够不着。**在宿主上实测、且只有重跑能显出来**：单趟运行
 * 里每种不同的调用只写一次，所以第一趟的任何东西都显不出它。
 *
 * <p>修法是**执行前问、而不是执行后问**——参照实现本来就是这个形状（按键探测、命中即早返回）。下面的断言
 * 让那句承诺**成真**、而不只是被写下：第二次调用报的是**同一个** effect，并且一行都不加。
 *
 * <p>第二个用例是「执行前」的另一半：**撤销过的 effect 保持它的键**，于是同一调用被**拒绝**，而不是在它
 * 底下悄悄重做。重做要么再写出一行无主数据、要么无声地什么都不做，两者都不是调用方能据以行动的答案。
 *
 * <p>**已知、且本条不覆盖**：两条**同一瞬间**的相同调用仍可能双双探测落空、双双写入。参照实现用执行前
 * 取得的 write claim 仲裁；本运行时没有那张表。记录为**未解决**，而不是宣称修完。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class RepeatedCallIsNotWrittenTwiceTest {

    private static final String WRITE = "创建跟进任务，提醒续约";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JsonMapper json;

    @Autowired
    CustomerRepository customers;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    SideEffectRepository sideEffects;

    @Autowired
    WriteClaimRepository claimRepository;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void theSameCallTwiceWritesOneRowAndOneEffect() {
        Long customer = customers.save(new Customer("Repeat Co", "low", "alice")).getId();
        ExecutionOutcome first = approve(aliceWrites(customer));
        assertNotNull(first.effectId());

        long followUpsBefore = followUps.count();
        long effectsBefore = sideEffects.count();

        ExecutionOutcome second = approve(aliceWrites(customer));

        assertEquals(first.effectId(), second.effectId(),
                "the second call is the same call, so it is the same effect");
        assertEquals(followUpsBefore, followUps.count(),
                "and it writes no second row -- the one that used to be left out of the ledger");
        assertEquals(effectsBefore, sideEffects.count(), "nor a second effect");
    }

    @Test
    void aRevokedCallIsRefusedRatherThanRemadeUnderItsOwnKey() {
        Long customer = customers.save(new Customer("Revoked Co", "low", "alice")).getId();
        ExecutionOutcome executed = approve(aliceWrites(customer));

        rest.exchange("/ai/tool-effects/" + executed.effectId(), HttpMethod.DELETE,
                entity(Map.of()), Map.class);

        long followUpsBefore = followUps.count();
        ResponseEntity<Map> again = rest.postForEntity("/ai/confirmations/" + aliceWrites(customer),
                entity(Map.of("decision", "approve")), Map.class);

        assertEquals(409, again.getStatusCode().value(),
                "a revoked key stays occupied; body was " + again.getBody());
        assertEquals(followUpsBefore, followUps.count(), "and nothing is written under it again");
    }

    /**
     * The engine obeys the claim, which is its half of the concurrent case.
     *
     * <p>The state below — the key held, no effect recorded yet — is what a second call <em>sees</em>
     * while a first one is executing. It is constructed rather than raced, because a raced version would
     * assert something about scheduling; the race itself is arbitrated by the unique key and covered in
     * {@code WriteClaimTest}. What this one pins is that the engine, handed that answer, does not
     * execute — which is the part a read-back cannot give you.
     */
    @Test
    void aCallWhoseClaimIsHeldIsNotExecuted() {
        Long customer = customers.save(new Customer("Held Co", "low", "alice")).getId();
        ExecutionOutcome executed = approve(aliceWrites(customer));

        sideEffects.deleteById(executed.effectId());
        WriteClaim settled = claimRepository.findAll().stream()
                .filter(claim -> executed.effectId().equals(claim.getEffectId()))
                .findFirst().orElse(null);
        assertNotNull(settled, "the execution took a claim and settled it onto its effect");
        String key = settled.getIdempotencyKey();
        claimRepository.deleteById(settled.getId());
        claimRepository.saveAndFlush(new WriteClaim(key, "alice", "create_followup"));

        long followUpsBefore = followUps.count();
        ResponseEntity<Map> again = rest.postForEntity("/ai/confirmations/" + aliceWrites(customer),
                entity(Map.of("decision", "approve")), Map.class);

        assertEquals(409, again.getStatusCode().value(),
                "a key somebody is executing under is not free; body was " + again.getBody());
        assertEquals(followUpsBefore, followUps.count(),
                "and the call is not run — which is the whole point of asking before executing");
    }

    /** Propose the write and return its confirmation token, read from the caller's own rows. */
    private String aliceWrites(Long customerId) {
        int before = Pending.count(rest, "alice", delegationSecret);
        ResponseEntity<Map> proposed = rest.postForEntity("/ai/chat",
                entity(Map.of("message", WRITE, "customerId", customerId)), Map.class);
        assertEquals(200, proposed.getStatusCode().value(), "the turn is served: " + proposed.getBody());
        // A write waits for a decision, and the waiting is one **more** pending row for alice: the chat
        // answer is the frozen `chat-response` and reports no status or token.
        //
        // 写在等人裁决，而「在等」就是 **alice 多了一行**待确认：聊天答案是冻结的 `chat-response`，
        // 不报 status 也不报 token。
        assertEquals(before + 1, Pending.count(rest, "alice", delegationSecret),
                "a write waits for a decision");
        return Pending.token(rest, "alice", delegationSecret);
    }

    private ExecutionOutcome approve(String token) {
        ResponseEntity<Map> decided = rest.postForEntity("/ai/confirmations/" + token,
                entity(Map.of("decision", "approve")), Map.class);
        return json.convertValue(Envelopes.data(decided.getBody()), ExecutionOutcome.class);
    }

    private HttpEntity<Object> entity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser("alice", delegationSecret));
        return new HttpEntity<>(body, headers);
    }
}
