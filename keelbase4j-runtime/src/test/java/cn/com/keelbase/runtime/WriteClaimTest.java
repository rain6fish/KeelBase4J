// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
