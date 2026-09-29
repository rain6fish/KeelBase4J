// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.Principal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * A high-impact action waits for a <em>second person</em> (ADR-0018) — and "a second person" has to
 * mean it, or the mode is a label.
 *
 * <p>The assertions that matter are the ones an implementation could get wrong while every status
 * code looks right: that nothing runs while it waits, that the initiator cannot answer their own
 * request, and that when somebody else does answer it the write belongs to the <b>initiator</b>
 * rather than to whoever approved it. The last one is the difference between approving a request and
 * taking it over.
 *
 * <p>高影响动作要等**第二个人**（ADR-0018）——而「第二个人」必须当真，否则这个模式只是个标签。
 *
 * <p>真正要紧的断言，是那种「所有状态码都看着对、实现却做错了」才会暴露的：等的时候**什么都没跑**、发起人
 * **不能**回答自己的请求、以及别人回答时那次写归**发起人**而不是归批准它的人。最后这一条，正是「批准一项
 * 请求」与「把它接手过去」之间的差别。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TwoPersonApprovalTest {

    @LocalServerPort
    int port;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    ConfirmationStore store;

    @Autowired
    ConfirmationRequestRepository confirmations;

    @Autowired
    CustomerRepository customers;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    SideEffectRepository sideEffects;

    private Long customerId;

    @BeforeEach
    void oneCustomerAndNoLeftoverConfirmations() {
        confirmations.deleteAll();
        sideEffects.deleteAll();
        customerId = customers.save(new Customer("Approval Co", "low", "alice")).getId();
    }

    // ── the gate ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theGateWritesARowAndNothingRuns() {
        ExecutionOutcome outcome = escalateAs("alice");

        assertEquals("requires_approval", outcome.status(),
                "the status the gate already had: the call is neither done nor refused");
        assertNotNull(outcome.token(), "a second person can only answer something that has a token");
        ConfirmationRequest row = confirmations.findByToken(outcome.token()).orElseThrow();
        assertEquals(ConfirmationMode.APPROVAL, row.getMode());
        assertEquals(ConfirmationLifecycle.PENDING, row.getStatus());
        assertTrue(written().isEmpty(), "nothing runs while it waits for the second person");
    }

    @Test
    void theInitiatorCannotApproveTheirOwnAction() {
        String token = escalateAs("alice").token();

        // Same person, administrator role: the rule is about *who*, not about what they may do
        // elsewhere — a high-impact action needs somebody other than its author.
        //
        // 同一个人、但带管理员角色：这条规则管的是**是谁**，不是他在别处能做什么——高影响动作需要的是
        // 作者**以外**的某个人。
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.decideApproval(token, new Principal("alice", "admin"), "approve"));

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals(ConfirmationLifecycle.PENDING, statusOf(token), "and the row is untouched");
        assertTrue(written().isEmpty(), "a refused self-approval must not run the tool");
    }

    @Test
    void aSecondPersonApprovingRunsTheWriteAsTheInitiator() {
        String token = escalateAs("alice").token();

        ExecutionOutcome outcome = engine.decideApproval(token, new Principal("carol", "admin"), "approve");

        assertEquals("executed", outcome.status());
        List<FollowUp> written = written();
        assertEquals(1, written.size());
        assertEquals("alice", written.get(0).getOwnerUserId(),
                "the write belongs to the person who asked for it, not to whoever approved it");
        ConfirmationRequest row = confirmations.findByToken(token).orElseThrow();
        assertEquals(ConfirmationLifecycle.APPROVED, row.getStatus());
        assertEquals("carol", row.getApproverId(), "and the row records who answered");
        assertEquals(ExecutionAxis.SUCCEEDED, ExecutionAxis.derive(row, Instant.now()));
    }

    @Test
    void aManagerMayDeclineTheirOwnRequestButNotApproveIt() {
        String token = escalateAs("carol").token();

        assertThrows(ResponseStatusException.class,
                () -> engine.decideApproval(token, new Principal("carol", "admin"), "approve"),
                "approving one's own high-impact action is what the mode forbids");
        ExecutionOutcome declined = engine.decideApproval(token, new Principal("carol", "admin"), "decline");

        assertEquals(ConfirmationLifecycle.DECLINED, declined.status(),
                "withdrawing one's own request is not a self-approval, so it is allowed");
        assertTrue(written().isEmpty());
    }

    @Test
    void twoApproversAtOnceExecuteOnce() throws Exception {
        String token = escalateAs("alice").token();

        int racers = 2;
        CyclicBarrier bothReady = new CyclicBarrier(racers);
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        List<String> refused = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            String approver = i == 0 ? "carol" : "dave";
            Thread t = new Thread(() -> {
                try {
                    bothReady.await();
                    executed.add(engine.decideApproval(token, new Principal(approver, "admin"), "approve")
                            .status());
                } catch (ResponseStatusException e) {
                    refused.add(String.valueOf(e.getStatusCode()));
                } catch (Exception e) {
                    refused.add(e.getClass().getSimpleName());
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(1, executed.size(), "exactly one approver may win; the other is refused");
        assertEquals(HttpStatus.CONFLICT.toString(), refused.get(0));
        assertEquals(1, written().size(), "and the tool ran once — the claim is the arbitration point");
    }

    // ── the initiator's own paths do not serve an approval row ──────────────────────────────────

    @Test
    void anApprovalRowIsNeitherListedForNorDecidableByItsInitiator() {
        String token = escalateAs("alice").token();

        assertTrue(store.mine("alice", null, Instant.now(), ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS)
                        .isEmpty(),
                "a row waiting on somebody else is not waiting on me");

        ConfirmationStore.OutOfBandResult decision = store.decideOutOfBand(
                token, new Principal("alice", "user"), ConfirmationLifecycle.APPROVE, Instant.now(),
                ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS);
        assertEquals(ConfirmationStore.OutOfBand.NOT_THIS_PATHS_ROW, decision.outcome(),
                "and it is not 'the window closed' — the window is open and somebody else must answer");

        ResponseStatusException inBand = assertThrows(ResponseStatusException.class,
                () -> engine.approve(token, new Principal("alice", "user")));
        assertEquals(HttpStatus.CONFLICT, inBand.getStatusCode());
        assertEquals("this confirmation is awaiting an approver, not its initiator",
                inBand.getReason(), "and the answer says why, rather than claiming it is already pending");
        assertEquals(ConfirmationLifecycle.PENDING, statusOf(token));
        assertTrue(written().isEmpty(), "no path the initiator has can run it");
    }

    // ── retry ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void retryingAnUnknownTokenIsNotFound() {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.retryExecution("no-such-token", new Principal("carol", "admin")));

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode());
    }

    @Test
    void retryingSomethingThatIsNotAnApprovalRowIsRefused() {
        ExecutionOutcome pending = engine.execute("create_followup",
                Map.of("customerId", customerId, "note", "not an approval"), new Principal("alice", "user"));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.retryExecution(pending.token(), new Principal("carol", "admin")));

        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(),
                "only an approval has an execution that can be retried");
    }

    @Test
    void retryingWhileAnAttemptLooksAliveIsRefused() {
        String token = approvedApprovalRow();
        claimAt(token, Instant.now());

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.retryExecution(token, new Principal("carol", "admin")));

        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(),
                "refusing here is what keeps a retry from racing an attempt that may still be running");
        assertTrue(written().isEmpty());
    }

    @Test
    void retryingAnAlreadySucceededConfirmationIsRefused() {
        String token = escalateAs("alice").token();
        engine.decideApproval(token, new Principal("carol", "admin"), "approve");

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> engine.retryExecution(token, new Principal("carol", "admin")));

        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals(1, written().size(), "and the successful write is not repeated");
    }

    /**
     * The case retry exists for: a row that reads {@code approved} with a dead attempt and no result —
     * and, because we cannot tell where the attempt died, possibly a write that already landed.
     *
     * <p>So this is also where the idempotency probe is asserted. The first attempt is made to have
     * happened (the effect is in the ledger) while the row looks unexecuted; the retry must then settle
     * the row <em>without</em> running the tool again. Without the probe the ledger would still read
     * one row while a second follow-up had been written — the effect deduplicated, the action not.
     *
     * <p>**重试存在的理由就是这一格**：一行读作 {@code approved}、带着一个死掉的尝试、且没有结果——而
     * 因为**我们分不清那次尝试死在哪里**，它也可能**已经写过**了。
     *
     * <p>所以这里同时是幂等探测的断言处。构造上让第一次尝试**确实发生过**（账本里有那条 effect），而那一行
     * 看起来没执行过；于是重试必须**在不重跑工具的前提下**把这一行了结。少了探测，账本仍是一行，而**第二条
     * 跟进已经写下了**——effect 被去重了，动作没有。
     */
    @Test
    void aDeadAttemptIsRetriedWithoutWritingASecondTime() {
        String token = escalateAs("alice").token();
        engine.decideApproval(token, new Principal("carol", "admin"), "approve");
        assertEquals(1, written().size(), "the first attempt wrote");
        leaveAsIfTheAttemptHadDied(token);

        ExecutionOutcome retried = engine.retryExecution(token, new Principal("carol", "admin"));

        assertEquals("executed", retried.status());
        assertEquals(1, written().size(),
                "the write already happened: re-running the tool would make this two");
        assertEquals(1, sideEffects.count(), "and there is still exactly one effect");
        ConfirmationRequest row = confirmations.findByToken(token).orElseThrow();
        assertEquals(ExecutionAxis.SUCCEEDED, ExecutionAxis.derive(row, Instant.now()),
                "the retry settles the row it found dead");
    }

    @Test
    void aDeadAttemptWithNoWriteIsRetriedAndWritesOnce() {
        String token = approvedApprovalRow();
        claimAt(token, Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 1_000));

        ExecutionOutcome retried = engine.retryExecution(token, new Principal("carol", "admin"));

        assertEquals("executed", retried.status());
        assertEquals(1, written().size(), "nothing had been written, so the retry writes it once");
        assertEquals("alice", written().get(0).getOwnerUserId(), "still as the initiator");
    }

    // ── the wire ────────────────────────────────────────────────────────────────────────────────

    /**
     * The whole path over HTTP, because each half passing on its own is not the same claim: a question
     * the planner routes to the R4 tool, the gate turning it into a waiting row, and a second person's
     * answer running it as the asker. The row is not planted — it comes out of a real conversation.
     *
     * <p>**整条路走一遍 HTTP**，因为两半各自跑通并不等于同一个主张：一句被规划器路由到 R4 工具的话、
     * 门控把它变成一条等待中的行、再由第二个人回答并以**提问者**的身份执行。这一行不是种进去的——
     * 它出自一次真实的对话。
     */
    @Test
    void theWholePathRunsOverHttp() throws Exception {
        HttpResponse<String> asked = post("/api/v1/ai/chat", "alice",
                "{\"message\":\"把这个客户升级处理\",\"customerId\":" + customerId + "}");
        Map<String, Object> turn = Envelopes.data(Json.parse(asked.body()));
        assertEquals("requires_approval", turn.get("status"), "the gate held it: " + turn);
        String token = String.valueOf(turn.get("token"));
        assertTrue(written().isEmpty(), "and nothing ran");

        HttpResponse<String> answered = post("/api/v1/ai/confirmations/" + token + "/approve-by", "carol",
                "{\"decision\":\"approve\"}");

        assertEquals(200, answered.statusCode(), answered.body());
        assertEquals(1, written().size(), "the second person's answer is what ran the write");
        assertEquals("alice", written().get(0).getOwnerUserId(), "as the person who asked");
    }

    @Test
    void answeringAnApprovalIsForAdministrators() throws Exception {
        String token = escalateAs("alice").token();

        assertEquals(403, post("/api/v1/ai/confirmations/" + token + "/approve-by", "bob",
                "{\"decision\":\"approve\"}").statusCode(),
                "a non-administrator may not answer somebody else's high-impact action");
        assertEquals(403, post("/api/v1/ai/confirmations/" + token + "/retry-execution", "bob", "")
                .statusCode());

        HttpResponse<String> approved = post("/api/v1/ai/confirmations/" + token + "/approve-by", "carol",
                "{\"decision\":\"approve\"}");
        assertEquals(200, approved.statusCode(), "an administrator may: " + approved.body());
        Map<String, Object> body = Envelopes.data(Json.parse(approved.body()));
        assertEquals("executed", body.get("status"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ExecutionOutcome escalateAs(String user) {
        return engine.execute("escalate_customer", Map.of("customerId", customerId),
                new Principal(user, "user"));
    }

    /**
     * An approval row moved to {@code approved} without an attempt having been made.
     *
     * <p>一条被移到 {@code approved} 的审批行，且**没有任何尝试发生过**。
     */
    private String approvedApprovalRow() {
        String token = escalateAs("alice").token();
        store.claimApproval(token, new Principal("carol", "admin"), ConfirmationLifecycle.APPROVED);
        return token;
    }

    private void claimAt(String token, Instant at) {
        ConfirmationRequest row = confirmations.findByToken(token).orElseThrow();
        row.setExecutionClaimedAt(at);
        store.save(row);
    }

    /**
     * Put the row back into the state a crashed attempt leaves: approved, no recorded result, and a
     * claim old enough that the lease has run out. The effect stays in the ledger, which is the whole
     * point — that is what a crash after the write and before the result looks like.
     *
     * <p>把这一行放回**一次崩溃的尝试留下的状态**：已批准、没有记录的结果、认领旧到租约早已过期。
     * **账本里那条 effect 保留**，而这正是要点——「写完了、但结果没记上就崩了」看上去就是这个样子。
     */
    private void leaveAsIfTheAttemptHadDied(String token) {
        ConfirmationRequest row = confirmations.findByToken(token).orElseThrow();
        row.setExecutedAt(null);
        row.setExecutionError(null);
        row.setExecutionClaimedAt(Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 1_000));
        store.save(row);
    }

    private List<FollowUp> written() {
        return followUps.findByCustomerIdAndDeletedAtIsNull(customerId);
    }

    private String statusOf(String token) {
        return confirmations.findByToken(token).orElseThrow().getStatus();
    }

    private HttpResponse<String> post(String path, String user, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.forUser(user, delegationSecret))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
