// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Two different transitions of the same stale hold, launched together, still leave one consistent row.
 *
 * <p>The existing concurrency case races two <em>identical</em> releases, which exercises one
 * conditional update against itself. The interesting question for a lease is different: a release
 * (the operator closing an unknown outcome) and a takeover (the next identical call reclaiming the
 * expired lease) both start from "claimed and stale", and they move the row in opposite directions —
 * one says the key stays held and the conclusion stays unknown, the other hands the key to a new
 * attempt. Whichever lands first must make the other fail, and the chain must end up with exactly the
 * row for the act that actually happened.
 *
 * <p>The other two cases cross the release and the takeover with a settle — the late arrival of an
 * execution that comes back after its lease ran out. Those are the two orders the lease's javadoc
 * admits it cannot distinguish; what the test pins down is the part that <em>must</em> hold under both:
 * the terminal state is one of the legal ones, a failed transition leaves no row, and a
 * {@code closed_unknown} row can never afterwards be settled.
 *
 * <p>Every case runs {@value #ROUNDS} rounds behind a shared start barrier, counts how many rounds had
 * both calls genuinely in flight, and prints the split between the two orders. One round in three
 * launches both calls together; the other two nudge one side a few milliseconds later, because with
 * both calls this cheap the faster path always wins the database and one order — and one half of the
 * state machine — would otherwise never be raced. The assertions are on "exactly one legal outcome",
 * never on which side wins, so they hold under any interleaving. The class runs on the embedded H2 by
 * default and on a real PostgreSQL when {@code KEELBASE_PG_URL} is set.
 *
 * <p>同一份陈旧占位的两种不同迁移同时发出，仍只留下一种一致的结果。
 *
 * <p>既有的并发用例让两次**相同**的释放互撞，练的是一次条件更新对它自己。租约真正有意思的问题是另一个：
 * 释放（运维把一份结局未知的占位关闭）与接管（下一个相同调用收回过期租约）都从「claimed 且陈旧」出发，
 * 却把这一行推向相反的方向 —— 一个说键仍被占住、结论仍未知，另一个把键交给新的尝试。先落地的那一个必须让
 * 另一个失败，而链上最终只该有**实际发生的那个动作**的那一行。
 *
 * <p>另外两条用例把释放与接管分别和落定（settle）交叉 —— 落定是一次**租约跑完之后才回来**的执行的迟到
 * 到达。这正是租约的 javadoc 自己承认分不清的两种顺序；测试钉住的是**两种顺序下都必须成立**的那部分：
 * 终态是合法状态之一、失败的迁移不留行、以及 `closed_unknown` 之后**绝不会**再被落定。
 *
 * <p>每条用例都在同一道起跑栅栏后跑 {@value #ROUNDS} 轮，数出「两个调用真正同时在飞」的轮数，并打印
 * 两种顺序各占多少轮。每三轮里有一轮同时放出去，另外两轮把一侧推迟几毫秒 —— 因为两个调用都这么便宜，
 * 更快的那个总是先赢下数据库，否则**一种顺序**（以及状态机的一半）永远得不到竞争。断言落在「恰好一种合法
 * 结果」上、从不落在「谁赢」上，故与调度无关。本类默认跑在嵌入式 H2 上，设了 {@code KEELBASE_PG_URL} 则跑在
 * 真的 PostgreSQL 上。
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimReleaseCrossRaceTest {

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);
    private static final Principal CAROL = new Principal("carol", Principal.ROLE_ADMIN);
    private static final String TOOL = "create_followup";

    /** Enough rounds that each of the three round shapes comes up many times. */
    private static final int ROUNDS = 50;

    /**
     * How long a nudged side waits after the start gun. Both calls are cheap enough that this is
     * longer than either takes to reach its update, so the nudged side reliably loses the database
     * race — which is the point: it makes the <em>other</em> order happen.
     *
     * 被推迟的一侧在发令枪后等多久。两个调用都足够便宜，这个时长比任一方走到自己那次更新还长，故被推迟的
     * 一侧会稳定地输掉数据库那一场竞争 —— 这正是目的：让**另一种**顺序真的发生。
     */
    private static final long NUDGE_MILLIS = 10L;

    /**
     * Run the same cases on a real PostgreSQL when one is offered — the same convention the
     * single-transition concurrency test uses, so both are measured on both dialects.
     *
     * 有人给真 PostgreSQL 时就在它上面跑同一批用例 —— 与单迁移并发用例同一约定，于是两者都在两种方言上被测到。
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
     * What one racer got: whether it won, the status it was answered with, the reason it read, and the
     * window its call spent in flight.
     *
     * 一个竞争者的所得：是否赢了、答复的状态码、读到的理由，以及它这次调用在飞的时间窗。
     */
    private record Attempt(boolean won, int status, String reason, long enteredAt, long exitedAt) {
    }

    /**
     * A stale hold together with the arguments that name its key — the caller needs both, because the
     * takeover is addressed by content and the release by row id.
     *
     * 一份陈旧占位，连同点名它那个键的参数 —— 调用方两样都要：接管按内容寻址，释放按行 id 寻址。
     */
    private record Stranded(WriteClaim hold, String args) {
    }

    /**
     * The operator's close and the next identical call's takeover, on the same stale row: one of them
     * moves the row, and the chain carries exactly the row of that one act.
     *
     * <p>The two transitions ask opposite things of the row. {@code close_unknown} makes the key stay
     * occupied and the conclusion stay unknown; {@code reclaim} hands the key to a new attempt. Both
     * are conditional on "claimed and stale", so whichever commits first makes the other's condition
     * false — and because both leave a {@code claim_release} row when they win, the count is what says
     * the loser wrote nothing, not the terminal state alone.
     *
     * 运维的关闭与下一个相同调用的接管落在同一份陈旧行上：其中一个动这一行，而链上恰好只有那一件事的那一行。
     *
     * <p>两种迁移对这一行要的东西相反：`close_unknown` 让键保持被占、结论保持未知；`reclaim` 把键交给新的
     * 尝试。两者都以「claimed 且陈旧」为条件，故先提交的那个让另一个的条件不成立 —— 又因为两者赢了都会留一行
     * `claim_release`，所以「输家什么都没写」要靠**行数**来说，不能只看终态。
     */
    @Test
    void aReleaseAndATakeoverOfTheSameStaleHoldLeaveOneOutcomeAndOneRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int releaseWins = 0;
        int takeoverWins = 0;
        int overlapping = 0;
        List<Long> roundMillis = new ArrayList<>();
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                int turn = round;
                long roundStarted = System.nanoTime();
                Stranded stranded = strandedHold();
                String marker = "note-" + UUID.randomUUID();
                String conversationId = "conversation-" + UUID.randomUUID();
                CyclicBarrier together = new CyclicBarrier(2);

                Future<Attempt> releasing = pool.submit(
                        () -> releaseAttempt(together, turn, 0, stranded.hold().getId(), marker));
                Future<Attempt> taking = pool.submit(
                        () -> claimAttempt(together, turn, 1, ALICE, TOOL, stranded.args(),
                                conversationId));
                Attempt rel = releasing.get(60, TimeUnit.SECONDS);
                Attempt tak = taking.get(60, TimeUnit.SECONDS);
                roundMillis.add((System.nanoTime() - roundStarted) / 1_000_000);

                int winners = (rel.won() ? 1 : 0) + (tak.won() ? 1 : 0);
                assertEquals(1, winners, "round " + round
                        + ": exactly one of release and takeover may move the row; release=" + rel
                        + " takeover=" + tak);
                if (Math.max(rel.enteredAt(), tak.enteredAt())
                        < Math.min(rel.exitedAt(), tak.exitedAt())) {
                    overlapping++;
                }

                WriteClaim after = claimRows.findById(stranded.hold().getId()).orElseThrow();
                List<AuditLog> rows = rowsFor(stranded.hold().getId());
                assertEquals(1, rows.size(), "round " + round
                        + ": the act that moved the row wrote exactly one row, the other wrote none: "
                        + rows);
                AuditLog row = rows.get(0);
                assertEquals("claim_release", row.getAction());
                assertEquals(TOOL, row.getTool());
                assertNull(row.getOutcome(), "round " + round + ": a release is never a tool verdict");

                if (rel.won()) {
                    releaseWins++;
                    assertEquals(WriteClaim.CLOSED_UNKNOWN, after.getStatus(),
                            "round " + round + ": the operator's close is the terminal state");
                    assertEquals(409, tak.status(),
                            "round " + round + ": the takeover is refused, not raced past it: " + tak);
                    assertEquals(WriteClaim.CLOSED_UNKNOWN, tak.reason(),
                            "round " + round + ": and it is told which state it found: " + tak);
                    assertTrue(row.getDetail().contains(marker),
                            "round " + round + ": the row is the operator's note: " + row.getDetail());
                    assertFalse(row.getDetail().contains("take-over"),
                            "round " + round + ": a refused takeover wrote no row: " + row.getDetail());
                } else {
                    takeoverWins++;
                    assertEquals(WriteClaim.CLAIMED, after.getStatus(),
                            "round " + round + ": the takeover leaves the key held, by a new attempt");
                    assertFalse(isStale(after),
                            "round " + round + ": and the new attempt's lease is fresh");
                    assertEquals(409, rel.status(),
                            "round " + round + ": the close is refused, not applied to a fresh claim: "
                                    + rel);
                    assertTrue(rel.reason() != null && rel.reason().startsWith("not_stale"),
                            "round " + round + ": the refusal names the age condition: " + rel.reason());
                    assertTrue(row.getDetail().contains("take-over by user " + ALICE.userId()),
                            "round " + round + ": the row is the takeover: " + row.getDetail());
                    assertFalse(row.getDetail().contains(marker),
                            "round " + round + ": the refused close wrote no row: " + row.getDetail());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(releaseWins > 0 && takeoverWins > 0,
                "the fixture must produce both orders — a race that only ever runs one of them proves "
                        + "half of what it claims: releaseWins=" + releaseWins
                        + " takeoverWins=" + takeoverWins);
        assertTrue(audit.verify().valid(), "the chain stays one valid line through every race");
        System.out.println("claim_release vs takeover on " + dialect() + ": " + ROUNDS + " rounds, "
                + releaseWins + " rounds the operator closed the hold, " + takeoverWins
                + " rounds the next call took it over, " + overlapping
                + " rounds had both calls in flight at once, per-round " + roundMillis + " ms");
    }

    /**
     * The operator's close and a late settle, on the same stale row: the row ends either settled or
     * closed as unknown, never both — and a close that won is never afterwards written over by a
     * settle.
     *
     * <p>{@code settle} is conditional on {@code status = claimed} and nothing else, so it does not
     * ask about age. That is deliberate: an execution that comes back late still produced whatever it
     * produced. What it must not do is land on a row an operator has already closed as unknown —
     * "nobody knows what became of this write" would turn into "here is its effect" after the fact.
     * The conditional update is what stops it, and the row count is where that shows.
     *
     * 运维的关闭与一次迟到的落定落在同一份陈旧行上：这一行的终态要么是 settled、要么关闭为未知，绝不**两者都是**
     * —— 而赢了的那次关闭之后，也绝不会再被落定写过去。
     *
     * <p>`settle` 只以 `status = claimed` 为条件、不问年龄。这是有意的：晚回来的执行照样产出了它产出的东西。
     * 它不得做的是落到一行**运维已关闭为未知**的行上 —— 「没人知道这次写怎么了」不能事后变成「这是它的 effect」。
     * 挡住它的正是那句条件更新，而这一点在行数上看得出来。
     */
    @Test
    void aReleaseAndALateSettleNeverBothLandOnTheSameHold() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int releaseWins = 0;
        int settleWins = 0;
        int overlapping = 0;
        List<Long> roundMillis = new ArrayList<>();
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                int turn = round;
                long roundStarted = System.nanoTime();
                Stranded stranded = strandedHold();
                String marker = "note-" + UUID.randomUUID();
                long effectId = 900_000_000L + round;
                CyclicBarrier together = new CyclicBarrier(2);

                Future<Attempt> releasing = pool.submit(
                        () -> releaseAttempt(together, turn, 0, stranded.hold().getId(), marker));
                Future<Attempt> settling = pool.submit(
                        () -> settleAttempt(together, turn, 1, ALICE, TOOL, stranded.args(), effectId));
                Attempt rel = releasing.get(60, TimeUnit.SECONDS);
                Attempt settlingDone = settling.get(60, TimeUnit.SECONDS);
                roundMillis.add((System.nanoTime() - roundStarted) / 1_000_000);

                assertEquals(200, settlingDone.status(),
                        "round " + round + ": settling is never refused, it either lands or no-ops: "
                                + settlingDone);
                if (Math.max(rel.enteredAt(), settlingDone.enteredAt())
                        < Math.min(rel.exitedAt(), settlingDone.exitedAt())) {
                    overlapping++;
                }

                WriteClaim after = claimRows.findById(stranded.hold().getId()).orElseThrow();
                List<AuditLog> rows = rowsFor(stranded.hold().getId());
                String status = after.getStatus();
                assertTrue(WriteClaim.SETTLED.equals(status)
                                || WriteClaim.CLOSED_UNKNOWN.equals(status),
                        "round " + round + ": the terminal state is one of the two legal ones, got "
                                + status);
                if (WriteClaim.SETTLED.equals(status)) {
                    assertFalse(rel.won(),
                            "round " + round + ": a closed row cannot have been settled in the same "
                                    + "round: " + rel);
                    settleWins++;
                    assertEquals(409, rel.status(),
                            "round " + round + ": the close is refused, not applied to a settled row: "
                                    + rel);
                    assertTrue(rel.reason() != null && rel.reason().startsWith("not_claimable"),
                            "round " + round + ": the refusal names the settled state: " + rel.reason());
                    assertEquals(0, rows.size(),
                            "round " + round + ": the refused close wrote no row: " + rows);
                    assertEquals(effectId, after.getEffectId(),
                            "round " + round + ": the effect the settle named is on the row");
                    assertNotNull(after.getSettledAt(), "round " + round + ": and its settle time");
                } else {
                    assertTrue(rel.won(),
                            "round " + round + ": the row reads closed_unknown, so the close is what "
                                    + "won: " + rel);
                    releaseWins++;
                    assertEquals(1, rows.size(),
                            "round " + round + ": the close wrote exactly one row: " + rows);
                    assertTrue(rows.get(0).getDetail().contains(marker),
                            "round " + round + ": and it is the operator's: " + rows.get(0).getDetail());
                    assertNull(after.getEffectId(),
                            "round " + round + ": the late settle no-opped — no effect on a closed row");
                    assertNull(after.getSettledAt(),
                            "round " + round + ": and no settle time on a closed row");
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(releaseWins > 0 && settleWins > 0,
                "the fixture must produce both orders — a race that only ever runs one of them proves "
                        + "half of what it claims: releaseWins=" + releaseWins
                        + " settleWins=" + settleWins);
        assertTrue(audit.verify().valid(), "the chain stays one valid line through every race");
        System.out.println("claim_release vs settle on " + dialect() + ": " + ROUNDS + " rounds, "
                + releaseWins + " rounds ended closed_unknown, " + settleWins
                + " rounds ended settled, " + overlapping
                + " rounds had both calls in flight at once, per-round " + roundMillis + " ms");
    }

    /**
     * The next call's takeover and the previous attempt's late settle, on the same stale row.
     *
     * <p>This is the pair the lease deliberately cannot arbitrate: {@code reclaim} leaves the row
     * {@code claimed} (with a fresh timestamp), and {@code settle} only asks for {@code claimed}, so a
     * settle from the old attempt may land after the takeover. The key fact the state machine still
     * guarantees is that the row ends settled exactly once, with one effect id, and the chain carries
     * at most the takeover's single row — never a second close.
     *
     * 下一个调用的接管与上一次尝试的迟到落定，落在同一份陈旧行上。
     *
     * <p>这正是租约**有意不做仲裁**的那一对：`reclaim` 把这一行留作 `claimed`（时间戳刷新），而 `settle`
     * 只问 `claimed`，故旧尝试的落定可能落在接管之后。状态机仍保证的关键事实是：这一行最终**恰好落定一次**、
     * 只带一个 effect id，而链上至多只有接管的那一行 —— 绝不会出现第二个关闭。
     */
    @Test
    void aTakeoverAndALateSettleEndSettledOnceWithAtMostTheTakeoverRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int takeoverWins = 0;
        int settleWins = 0;
        int overlapping = 0;
        List<Long> roundMillis = new ArrayList<>();
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                int turn = round;
                long roundStarted = System.nanoTime();
                Stranded stranded = strandedHold();
                long effectId = 910_000_000L + round;
                String conversationId = "conversation-" + UUID.randomUUID();
                CyclicBarrier together = new CyclicBarrier(2);

                Future<Attempt> taking = pool.submit(
                        () -> claimAttempt(together, turn, 0, ALICE, TOOL, stranded.args(),
                                conversationId));
                Future<Attempt> settling = pool.submit(
                        () -> settleAttempt(together, turn, 1, ALICE, TOOL, stranded.args(), effectId));
                Attempt tak = taking.get(60, TimeUnit.SECONDS);
                Attempt settlingDone = settling.get(60, TimeUnit.SECONDS);
                roundMillis.add((System.nanoTime() - roundStarted) / 1_000_000);

                assertEquals(200, settlingDone.status(),
                        "round " + round + ": settling is never refused: " + settlingDone);
                if (Math.max(tak.enteredAt(), settlingDone.enteredAt())
                        < Math.min(tak.exitedAt(), settlingDone.exitedAt())) {
                    overlapping++;
                }

                WriteClaim after = claimRows.findById(stranded.hold().getId()).orElseThrow();
                List<AuditLog> rows = rowsFor(stranded.hold().getId());
                assertEquals(WriteClaim.SETTLED, after.getStatus(), "round " + round
                        + ": whichever came first, the effect is recorded and the row ends settled");
                assertEquals(effectId, after.getEffectId(),
                        "round " + round + ": with the one effect id the settle named");
                assertNotNull(after.getSettledAt(), "round " + round + ": and a settle time");

                if (tak.won()) {
                    takeoverWins++;
                    assertEquals(1, rows.size(), "round " + round
                            + ": the takeover wrote its one row and nothing wrote a second: " + rows);
                    assertTrue(rows.get(0).getDetail().contains("take-over by user " + ALICE.userId()),
                            "round " + round + ": and it is the takeover: " + rows.get(0).getDetail());
                } else {
                    settleWins++;
                    assertEquals(WriteClaim.SETTLED, tak.reason(),
                            "round " + round + ": the losing takeover is told it found a settled row: "
                                    + tak);
                    assertEquals(0, rows.size(),
                            "round " + round + ": a refused takeover wrote no row: " + rows);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(takeoverWins > 0 && settleWins > 0,
                "the fixture must produce both orders — a race that only ever runs one of them proves "
                        + "half of what it claims: takeoverWins=" + takeoverWins
                        + " settleWins=" + settleWins);
        assertTrue(audit.verify().valid(), "the chain stays one valid line through every race");
        System.out.println("takeover vs settle on " + dialect() + ": " + ROUNDS + " rounds, "
                + takeoverWins + " rounds the takeover won, " + settleWins
                + " rounds the settle won, " + overlapping
                + " rounds had both calls in flight at once, per-round " + roundMillis + " ms");
    }

    /**
     * The start gun. Every third round (including the first, {@code round % 3 == 1}) both sides go at
     * once; the other two hold one side back, alternating which one. Without that, the cheaper call
     * wins every round and the other branch is never entered — a race test that only ever runs one
     * order proves half of what it claims to.
     *
     * 发令枪。每三轮里有一轮（含第一轮，`round % 3 == 1`）两边同时出发；另外两轮各把一侧按住，且轮流按。
     * 没有这一步，更便宜的那个调用会每轮都赢，另一支永远不会被走到 —— 只跑一种顺序的竞争测试，只证明了它
     * 声称的一半。
     */
    private static void atGun(CyclicBarrier together, int round, int side) throws Exception {
        together.await(20, TimeUnit.SECONDS);
        boolean nudged = (round % 3 == 2 && side == 0) || (round % 3 == 0 && side == 1);
        if (nudged) {
            Thread.sleep(NUDGE_MILLIS);
        }
    }

    /**
     * One racer: wait for the start gun, then close the hold and record what came back.
     *
     * 一个竞争者：等发令枪，然后关闭占位并记下得到什么。
     */
    private Attempt releaseAttempt(CyclicBarrier together, int round, int side, Long claimId,
                                   String marker) throws Exception {
        atGun(together, round, side);
        long enteredAt = System.nanoTime();
        try {
            releases.release(claimId, CAROL, marker, null);
            return new Attempt(true, 200, null, enteredAt, System.nanoTime());
        } catch (ResponseStatusException refused) {
            return new Attempt(false, refused.getStatusCode().value(), refused.getReason(),
                    enteredAt, System.nanoTime());
        } catch (RuntimeException failure) {
            return new Attempt(false, 500,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    enteredAt, System.nanoTime());
        }
    }

    /**
     * One racer: wait for the start gun, then ask for the same call again — which is the takeover when
     * the row is stale — and record the answer.
     *
     * 一个竞争者：等发令枪，然后再次请求同一个调用 —— 行陈旧时那就是接管 —— 并记下答复。
     */
    private Attempt claimAttempt(CyclicBarrier together, int round, int side, Principal who,
                                 String tool, String args, String conversationId) throws Exception {
        atGun(together, round, side);
        long enteredAt = System.nanoTime();
        try {
            WriteClaimService.Outcome outcome = claims.claim(who, tool, args, conversationId);
            return new Attempt(outcome.won(), outcome.won() ? 200 : 409, outcome.status(),
                    enteredAt, System.nanoTime());
        } catch (RuntimeException failure) {
            return new Attempt(false, 500,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    enteredAt, System.nanoTime());
        }
    }

    /** One racer: wait for the start gun, then settle. Settling answers nothing, so it cannot refuse. */
    private Attempt settleAttempt(CyclicBarrier together, int round, int side, Principal who,
                                  String tool, String args, Long effectId) throws Exception {
        atGun(together, round, side);
        long enteredAt = System.nanoTime();
        try {
            claims.settle(who, tool, args, effectId);
            return new Attempt(true, 200, null, enteredAt, System.nanoTime());
        } catch (RuntimeException failure) {
            return new Attempt(false, 500,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    enteredAt, System.nanoTime());
        }
    }

    /**
     * A hold taken for alice and aged past the lease: exactly the row both transitions exist for.
     *
     * 为 alice 取下、并老化到越过租约的占位：正是两种迁移都存在的那一行。
     */
    private Stranded strandedHold() {
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
        return new Stranded(hold, args);
    }

    /** Strictly past the lease, the same boundary the conditional updates ask in SQL. */
    private static boolean isStale(WriteClaim claim) {
        return Instant.now().toEpochMilli() - claim.getClaimedAt().toEpochMilli()
                > ExecutionAxis.LEASE_MILLIS;
    }

    /**
     * The chain rows naming this hold — unique per test and per round, so the shared table is safe.
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

    /** Which dialect this run measured, for the printed report. / 本次跑测的是哪种方言，供打印报告。 */
    private static String dialect() {
        String url = System.getenv("KEELBASE_PG_URL");
        return url == null || url.isBlank() ? "h2" : "postgresql";
    }
}
