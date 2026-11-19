// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.identity.Principal;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pre-execution claim arbitrates a write, so two identical calls cannot both perform it (seam S11).
 *
 * <p>Asking the ledger before executing settles every repeat that arrives after the first one has
 * finished. It settles nothing about calls that arrive <em>together</em> — and that is what the first
 * test here is about, with real threads rather than a constructed state: the assertion is "exactly one
 * winner", which holds under any interleaving and therefore does not depend on scheduling.
 *
 * <p>The other two are the properties that make the row safe to rely on: a released claim can be taken
 * again (so a refusal does not make a call permanently impossible), and a claim that <em>cannot be
 * read</em> is not treated as free (so a database hiccup cannot turn into a duplicate write).
 *
 * <p>执行前的占位会仲裁这次写，使两次相同调用不可能都执行（缝 S11）。
 *
 * <p>执行前先问账本，能解决**第一次已经完成之后**到达的每一次重复；它对**同时到达**的调用一无所用——而这正是
 * 第一个用例的内容，用的是**真线程**、不是构造出来的状态：断言是「恰好一个赢家」，它在任何交错下都成立，因而
 * 不依赖调度。
 *
 * <p>另外两条是「这一行值得被信赖」所依赖的性质：释放过的占位可以被再次取得（故一次拒绝不会让某次调用**永远**
 * 做不到），以及**读不出来**的占位不被当成空闲（故数据库打个嗝不会变成一次重复的写）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WriteClaimTest {

    private static final Principal ALICE = new Principal("alice", "user");
    private static final String TOOL = "create_followup";

    @Autowired
    WriteClaimService claims;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    WriteClaimRepository claimRows;

    @Autowired
    AuditLogRepository auditRows;

    /**
     * Arguments unique to one test, and that is not decoration: these tests share a database, and the
     * claim is keyed by the content of the call. Reusing one literal would make a test's outcome depend
     * on which other test ran first — the arbitration test would find the key already held and report
     * zero winners, which looks like broken arbitration and is really a shared fixture.
     */
    private static String sameCallEverywhere() {
        return "{\"note\":\"" + java.util.UUID.randomUUID() + "\"}";
    }

    /** Eight callers, one key, one start gun: the unique key decides, not the schedule. */
    @Test
    void ofTwoCallsAtTheSameInstantExactlyOneOwnsTheExecution() throws Exception {
        String args = sameCallEverywhere();
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> claims = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return this.claims.claim(ALICE, TOOL, args).won();
                }));
            }
            start.countDown();

            long winners = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners,
                    "the unique key is the arbiter: one execution, and everyone else told not to run it");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aReleasedClaimCanBeTakenAgainAndAHeldOneCannot() {
        String args = sameCallEverywhere();
        claims.claim(ALICE, TOOL, args);
        assertFalse(claims.claim(ALICE, TOOL, args).won(),
                "a second attempt while the first is in flight is refused, not quietly allowed");

        claims.release(ALICE, TOOL, args);
        assertTrue(claims.claim(ALICE, TOOL, args).won(),
                "an attempt that failed without landing frees the key — a refusal must not be permanent");
    }

    /**
     * A claim that outlived its lease is taken again — the door that keeps a crash from making a call
     * permanently impossible.
     *
     * <p>This is the case the row cannot see its way out of on its own: a process killed between taking
     * the claim and settling it leaves a row that looks exactly like one still executing, and no code of
     * ours runs again to say otherwise. Time is the only thing that separates them, which is why the
     * lease exists and why it is the same constant the confirmation row's execution claim spends.
     */
    @Test
    void aClaimWhoseLeaseRanOutIsTakenAgain() {
        String args = sameCallEverywhere();
        claims.claim(ALICE, TOOL, args);
        assertFalse(claims.claim(ALICE, TOOL, args).won(), "a fresh claim is not retried");

        ageTheClaim(args, ExecutionAxis.LEASE_MILLIS + 1_000L);

        assertTrue(claims.claim(ALICE, TOOL, args).won(),
                "past the lease the attempt is not coming back, so the key is free again");
    }

    /** Age the row by hand: the only way to reach the stale branch without waiting five minutes. */
    private void ageTheClaim(String args, long byMillis) {
        transactions.executeWithoutResult(status -> entityManager
                .createQuery("update WriteClaim c set c.claimedAt = :old where c.idempotencyKey = :key")
                .setParameter("old", Instant.now().minusMillis(byMillis))
                .setParameter("key", WriteClaimService.keyFor(ALICE, TOOL, args))
                .executeUpdate());
    }

    /**
     * Fail closed. A row that cannot be read is not evidence that the key is free; reading it as "free"
     * would let a database hiccup produce exactly the duplicate write this row exists to prevent.
     */
    @Test
    void anUnreadableClaimIsRefusedRatherThanTreatedAsFree() {
        WriteClaimRepository broken = mock(WriteClaimRepository.class);
        when(broken.saveAndFlush(any(WriteClaim.class)))
                .thenThrow(new DataIntegrityViolationException("unique idempotency_key"));
        when(broken.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        assertFalse(new WriteClaimService(broken).claim(ALICE, TOOL, sameCallEverywhere()).won(),
                "no evidence that the key is free is not evidence that it is");
    }

    /**
     * The lease door leaves one row of the chain, and only one: a hold nobody could conclude changed
     * hands, which is the act the contract records as {@code claim_release}.
     *
     * <p>The row is found by the hold's own id rather than by "all claim_release rows", because these
     * tests share a database and an assertion over the whole table would depend on which test ran
     * first — the same trap {@link #sameCallEverywhere} exists to avoid.
     *
     * 租约门在链上留下**恰好一行**：一份**没人能下结论**的占位换了主人，而契约把这个动作记成
     * `claim_release`。
     *
     * <p>按占位自己的 id 找这一行、而不是找「全部 claim_release 行」，因为这些测试**共用**一个数据库，
     * 对整张表的断言会依赖哪个测试先跑 —— 正是 {@link #sameCallEverywhere} 要避开的那种坑。
     */
    @Test
    void aStrandedClaimIsTakenOverAndLeavesExactlyOneRowOnTheChain() {
        String args = sameCallEverywhere();
        claims.claim(ALICE, TOOL, args);
        String key = WriteClaimService.keyFor(ALICE, TOOL, args);
        Long strandedId = claimRows.findByIdempotencyKey(key)
                .orElseThrow(() -> new AssertionError("the first call took the claim")).getId();

        ageTheClaim(args, ExecutionAxis.LEASE_MILLIS + 1_000L);

        String conversationId = "conversation-" + java.util.UUID.randomUUID();
        assertTrue(claims.claim(ALICE, TOOL, args, conversationId).won(),
                "past the lease the attempt is not coming back, so the key is free again");

        List<AuditLog> rows = auditRows.findAll().stream()
                .filter(row -> "claim_release".equals(row.getAction()))
                .filter(row -> row.getDetail() != null
                        && row.getDetail().contains("#" + strandedId))
                .toList();
        assertEquals(1, rows.size(), "the takeover is one row, exactly: " + rows);
        AuditLog row = rows.get(0);
        assertNull(row.getOutcome(),
                "no outcome: this row is not a tool verdict, and the hold's conclusion stays unknown");
        assertEquals(TOOL, row.getTool(), "the tool is on the row so it can be filtered without detail");
        assertEquals(conversationId, row.getConversationId(),
                "the row is filed under the conversation that took the hold over");
        assertTrue(row.getDetail().contains("held by user " + ALICE.userId()),
                "the previous owner is named: " + row.getDetail());
        assertTrue(row.getDetail().contains("take-over by user " + ALICE.userId()),
                "and so is the new one: " + row.getDetail());
        assertTrue(row.getDetail().contains("lease expired"),
                "and why the hold was released: " + row.getDetail());
    }

    /**
     * A chain that refuses the row does not undo the takeover: the key has already moved, and making
     * the audit a precondition would refuse an execution that is entitled to run.
     *
     * 链拒绝了那一行，不会撤销这次接管：键已经换了主人，把审计变成前提会拒掉一次**有权运行**的执行。
     */
    @Test
    void aFailedChainWriteDoesNotBlockTheTakeover() {
        WriteClaimRepository repository = mock(WriteClaimRepository.class);
        WriteClaim stranded = mock(WriteClaim.class);
        when(stranded.getStatus()).thenReturn(WriteClaim.CLAIMED);
        when(stranded.getClaimedAt())
                .thenReturn(Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 60_000L));
        when(stranded.getId()).thenReturn(7L);
        when(stranded.getUserId()).thenReturn(ALICE.userId());
        when(stranded.getToolName()).thenReturn(TOOL);
        when(stranded.getEffectId()).thenReturn(null);
        when(repository.saveAndFlush(any(WriteClaim.class)))
                .thenThrow(new DataIntegrityViolationException("unique idempotency_key"));
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(stranded));
        when(repository.reclaim(anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(1);

        AuditService audit = mock(AuditService.class);
        when(audit.append(anyString(), anyString(), anyString(), anyBoolean(), any(), anyString(),
                any())).thenThrow(new IllegalStateException("the chain is down"));
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);

        assertTrue(new WriteClaimService(repository, provider).claim(ALICE, TOOL, sameCallEverywhere())
                        .won(),
                "the chain is a record of the takeover, never a precondition of it");
        verify(audit).append(eq("claim_release"), eq(ALICE.userId()), anyString(), eq(false),
                isNull(), eq(TOOL), any());
    }
}
