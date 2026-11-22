// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimReleaseService;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.Principal;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two operators releasing the same stranded hold at the same instant close it once.
 *
 * <p>The close is a conditional update ({@code status = claimed and claimedAt < staleBefore}), so at
 * most one caller can move the row — but the act is also a row on the audit chain, and "at most one
 * update" says nothing about how many rows that leaves. The whole release runs in one transaction
 * precisely so the two halves commit together; this test starts both halves at a barrier and asserts
 * what the pair leaves behind: one winner, one {@code already_closed} refusal, one closed row, one
 * {@code claim_release} row carrying the winner's note, and a retry by the loser that writes nothing.
 *
 * <p>The assertion is on "exactly one", not on which caller wins: it has to hold under any
 * interleaving, and therefore does not depend on the scheduler. A known interleaving where the loser
 * reads the row only after the winner committed is not a failure — it still yields the same one
 * winner and one refusal — so the test is not flaky, only occasionally less adversarial. The run
 * counts how many rounds had both calls in flight at once and prints that alongside the timing.
 *
 * <p>The class runs on the embedded H2 by default and on a real PostgreSQL when {@code
 * KEELBASE_PG_URL} is set, so the same arbitration is measured on both the in-memory row lock and the
 * dialect a host actually runs.
 *
 * 两位运维在同一瞬间释放同一份滞留占位，只能关闭它一次。
 *
 * <p>关闭是一次条件更新（`status = claimed and claimedAt < staleBefore`），故至多一个调用能动这一行 ——
 * 但这个动作同样是审计链上的一行，「至多一次更新」并不说明它留下几行。整次释放刻意同处一个事务，正是为了让
 * 两半一起提交；本测试用同一道栅栏把两半同时放出去，并断言它们合起来留下什么：一个赢家、一次
 * `already_closed` 拒绝、一行已关闭的占位、一行带着赢家说明的 `claim_release`，以及输家重试时什么都不写。
 *
 * <p>断言落在「恰好一个」上、而不是落在「谁赢」上：它必须在任何交错下都成立，因而与调度无关。输家在赢家
 * 提交之后才读到那一行，也是一种合法交错 —— 它同样得到一赢一拒，故本测试不会时红时绿，只是偶尔不够对抗。
 * 运行时会数出「两个调用同时在飞」的轮数，并与耗时一起打印。
 *
 * <p>本类默认跑在嵌入式 H2 上；设了 {@code KEELBASE_PG_URL} 则跑在真的 PostgreSQL 上，于是同一个仲裁
 * 在内存库行锁与宿主实际运行的方言上都得到测量。
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimReleaseConcurrencyTest {

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);
    private static final Principal CAROL = new Principal("carol", Principal.ROLE_ADMIN);
    private static final String TOOL = "create_followup";

    /**
     * Enough rounds that at least one pair of calls is genuinely in flight together.
     *
     * 轮数足够多，至少有一对调用是真正同时在飞的。
     */
    private static final int ROUNDS = 50;

    /**
     * Run the same case on a real PostgreSQL when one is offered, and stay on the embedded H2 when
     * none is: the suite's default and the CI posture are H2, while {@code KEELBASE_PG_URL} is the
     * repository's own convention for "here is a real database, measure against it".
     *
     * 有人给真 PostgreSQL 时就在它上面跑同一条用例，没人给就留在嵌入式 H2 上：套件默认与 CI 的姿态都是
     * H2，而 `KEELBASE_PG_URL` 是本仓自己的约定 ——「这里有一个真库，拿它做实测」。
     */
    @DynamicPropertySource
    static void pointAtPostgresWhenOffered(DynamicPropertyRegistry registry) {
        String url = System.getenv("KEELBASE_PG_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> envOrDefault("KEELBASE_PG_USER", "keelbase"));
        registry.add("spring.datasource.password",
                () -> envOrDefault("KEELBASE_PG_PASSWORD", "keelbase"));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    WriteClaimService claims;

    @Autowired
    WriteClaimRepository claimRows;

    @Autowired
    WriteClaimReleaseService releases;

    @Autowired
    AuditLogRepository auditRows;

    @Autowired
    AuditService audit;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    /**
     * What one racer got: the note it carried, the answer, and the window its call spent in flight.
     *
     * 一个竞争者的所得：它带的说明、得到的答复，以及它这次调用在飞的时间窗。
     */
    private record Attempt(String marker, int status, String refusal, long enteredAt, long exitedAt) {

        boolean won() {
            return refusal == null;
        }
    }

    @Test
    void twoOperatorsReleasingTheSameHoldAtOnceCloseItExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int overlapping = 0;
        long started = System.nanoTime();
        List<Long> roundMillis = new ArrayList<>();
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                long roundStarted = System.nanoTime();
                WriteClaim hold = strandedHold();
                String[] markers = {"note-" + UUID.randomUUID(), "note-" + UUID.randomUUID()};
                CyclicBarrier together = new CyclicBarrier(2);

                Future<Attempt> left =
                        pool.submit(() -> attempt(together, hold.getId(), markers[0]));
                Future<Attempt> right =
                        pool.submit(() -> attempt(together, hold.getId(), markers[1]));
                Attempt a = left.get(60, TimeUnit.SECONDS);
                Attempt b = right.get(60, TimeUnit.SECONDS);
                roundMillis.add((System.nanoTime() - roundStarted) / 1_000_000);

                Attempt winner = a.won() ? a : b;
                Attempt loser = a.won() ? b : a;
                int winners = (a.won() ? 1 : 0) + (b.won() ? 1 : 0);
                assertEquals(1, winners,
                        "round " + round + ": exactly one release may win; left=" + a
                                + " right=" + b);
                assertEquals(409, loser.status(),
                        "round " + round + ": the loser is told what happened, not handed a success: "
                                + loser);
                assertTrue(loser.refusal() != null && loser.refusal().startsWith("already_closed"),
                        "round " + round + ": the second close is refused as already closed, got "
                                + loser.refusal());
                if (Math.max(a.enteredAt(), b.enteredAt()) < Math.min(a.exitedAt(), b.exitedAt())) {
                    overlapping++;
                }

                // The hold is closed once, and the chain carries exactly the winning act.
                // 占位只被关闭一次，而链上带的恰好是赢家的那个动作。
                assertEquals(WriteClaim.CLOSED_UNKNOWN,
                        claimRows.findById(hold.getId()).orElseThrow().getStatus(),
                        "round " + round + ": the hold is closed_unknown once");
                List<AuditLog> rows = rowsFor(hold.getId());
                assertEquals(1, rows.size(),
                        "round " + round + ": close and row are one act — one row, got " + rows);
                AuditLog row = rows.get(0);
                assertEquals("claim_release", row.getAction());
                assertTrue(row.getDetail().contains(winner.marker()),
                        "round " + round + ": the row belongs to the winning attempt: "
                                + row.getDetail());
                assertFalse(row.getDetail().contains(loser.marker()),
                        "round " + round + ": the losing attempt wrote nothing: " + row.getDetail());

                // The loser's retry is the only repair the API offers; it must stay a refusal and
                // must not add a second row.
                // 输家的重试是 API 唯一提供的补救；它必须仍是一次拒绝，且不得多写一行。
                Attempt retried = attemptNow(hold.getId(), loser.marker());
                assertEquals(409, retried.status(),
                        "round " + round + ": retrying a closed hold is refused: " + retried);
                assertTrue(retried.refusal() != null
                                && retried.refusal().startsWith("already_closed"),
                        "round " + round + ": and it names the same reason: " + retried.refusal());
                assertEquals(1, rowsFor(hold.getId()).size(),
                        "round " + round + ": a refused retry writes no second row");
            }
        } finally {
            pool.shutdownNow();
        }

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertTrue(audit.verify().valid(), "the chain stays one valid line through every race");

        String dialect = System.getenv("KEELBASE_PG_URL") == null ? "h2" : "postgresql";
        StringBuilder report = new StringBuilder();
        report.append("claim_release concurrency on ").append(dialect).append(": ").append(ROUNDS)
                .append(" rounds, one winner + one already_closed each, ")
                .append(overlapping).append(" rounds had both calls in flight at once, ")
                .append("elapsed ").append(elapsed).append(" ms, per-round ")
                .append(roundMillis).append(" ms");
        System.out.println(report);
    }

    /**
     * One racer: wait for the start gun, then release and record what came back.
     *
     * 一个竞争者：等发令枪，然后释放并记下得到什么。
     */
    private Attempt attempt(CyclicBarrier together, Long claimId, String marker) throws Exception {
        together.await(20, TimeUnit.SECONDS);
        return attemptNow(claimId, marker);
    }

    private Attempt attemptNow(Long claimId, String marker) {
        long enteredAt = System.nanoTime();
        try {
            releases.release(claimId, CAROL, marker, null);
            return new Attempt(marker, 200, null, enteredAt, System.nanoTime());
        } catch (ResponseStatusException refused) {
            return new Attempt(marker, refused.getStatusCode().value(), refused.getReason(),
                    enteredAt, System.nanoTime());
        } catch (RuntimeException failure) {
            // Not a refusal the API can produce; surface it through the assertion instead of a stack.
            // 这不是 API 会给出的拒绝；让它经由断言露出来，而不是甩出一段栈。
            return new Attempt(marker, 500,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    enteredAt, System.nanoTime());
        }
    }

    /**
     * A hold taken for alice and aged past the lease: exactly the row the release path exists for.
     *
     * 为 alice 取下、并老化到越过租约的占位：正是释放路径存在的那一行。
     */
    private WriteClaim strandedHold() {
        String args = "{\"note\":\"" + UUID.randomUUID() + "\"}";
        assertTrue(claims.claim(ALICE, TOOL, args).won(), "the fixture takes the claim");
        WriteClaim hold = claimRows
                .findByIdempotencyKey(WriteClaimService.keyFor(ALICE, TOOL, args))
                .orElseThrow(() -> new AssertionError("the fixture's claim row is readable"));
        transactions.executeWithoutResult(status -> entityManager
                .createQuery("update WriteClaim c set c.claimedAt = :old where c.id = :id")
                .setParameter("old", Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 60_000L))
                .setParameter("id", hold.getId())
                .executeUpdate());
        return hold;
    }

    /**
     * The chain rows naming this hold — unique per test and per round, so the table is shared safely.
     *
     * 点名这份占位的链行 —— 每个测试、每一轮都唯一，故共用这张表是安全的。
     */
    private List<AuditLog> rowsFor(Long claimId) {
        return auditRows.findAll().stream()
                .filter(row -> "claim_release".equals(row.getAction()))
                .filter(row -> row.getDetail() != null
                        && row.getDetail().contains("#" + claimId + " "))
                .toList();
    }
}
