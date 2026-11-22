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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two <em>processes</em> releasing the same stranded hold at the same instant close it once.
 *
 * <p>The in-process concurrency test crosses a thread boundary; this one crosses a process boundary,
 * which is the shape a second deployment instance has. Two child JVMs each boot the real runtime
 * against one PostgreSQL, wait for the same wall-clock instant and release the same claim with
 * different notes. The arbitration is still the database's conditional update — nothing in the
 * application serializes callers in memory — and the assertions are the ones that must survive the
 * boundary: one winner, one {@code already_closed}, one closed row, one {@code claim_release} row
 * carrying the winner's note, and a loser retry that writes nothing.
 *
 * <p>PostgreSQL only, and deliberately so: an in-memory H2 cannot be shared by two processes, so
 * without {@code KEELBASE_PG_URL} this class is skipped rather than silently testing one process. The
 * eight rounds use one start gun each; the two children stay warmed up between rounds, so the setup
 * cost is paid once.
 *
 * 两个**进程**在同一瞬间释放同一份滞留占位，只能关闭它一次。
 *
 * <p>进程内并发用例跨的是线程边界；这一条跨的是**进程边界** —— 也就是第二个部署实例的形状。两个子 JVM
 * 各自对同一个 PostgreSQL 引导真实运行时，等同一个 wall-clock 时刻，用不同的说明释放同一份占位。仲裁仍是
 * 数据库的条件更新 —— 应用里没有任何东西在内存中把调用方串起来 —— 而断言正是那些必须跨过边界仍成立的事：
 * 一个赢家、一次 `already_closed`、一行已关闭的占位、一行带赢家说明的 `claim_release`，以及输家重试时
 * 什么都不写。
 *
 * <p>只跑 PostgreSQL，且是有意的：内存 H2 无法被两个进程共享，故没有 `KEELBASE_PG_URL` 时本类**跳过**，
 * 而不是悄悄只测一个进程。八轮各用一个起跑时刻；两个子进程在轮间保持热态，故启动成本只付一次。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "KEELBASE_PG_URL", matches = ".+")
class ClaimReleaseCrossProcessTest {

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);

    private static final String TOOL = "create_followup";

    /** One start gun per round; the children are already warm, so the spacing only separates rounds. */
    private static final int ROUNDS = 8;

    /** Time for two real application starts before the first gun; observed startup is far shorter. */
    private static final long WARM_UP_MILLIS = 40_000L;

    private static final long SPACING_MILLIS = 750L;

    private static final Pattern READY = Pattern.compile(
            "^READY pid=(\\d+) at=(\\d+) startAt=(\\d+) url=(\\S+)$");

    private static final Pattern RESULT = Pattern.compile(
            "^RESULT round=(\\d+) won=(true|false) status=(\\d+)(?: reason=(.*))?$");

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

    @DynamicPropertySource
    static void pointAtPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("KEELBASE_PG_URL"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username",
                () -> envOrDefault("KEELBASE_PG_USER", "keelbase"));
        registry.add("spring.datasource.password",
                () -> envOrDefault("KEELBASE_PG_PASSWORD", "keelbase"));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** One child JVM and the two files its streams went to. */
    private record Child(String name, Process process, Path out, Path err, long pid, long readyAt) {
    }

    @Test
    void twoProcessesReleasingTheSameHoldAtOnceCloseItExactlyOnce() throws Exception {
        List<WriteClaim> holds = new ArrayList<>();
        List<String> leftNotes = new ArrayList<>();
        List<String> rightNotes = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            holds.add(strandedHold());
            leftNotes.add("note-" + UUID.randomUUID());
            rightNotes.add("note-" + UUID.randomUUID());
        }

        long started = System.currentTimeMillis();
        long startAt = started + WARM_UP_MILLIS;
        Path logs = Files.createTempDirectory("claim-race-");
        Child left = launch("left", logs, holds, leftNotes, startAt);
        Child right = launch("right", logs, holds, rightNotes, startAt);
        // Both are launched before either is awaited: starting them one after the other would put the
        // second one's boot time inside its own warm-up instead of before the shared gun.
        //
        // 两个都先启动、再等就绪：一个接一个地等，会把第二个的启动时间算进它自己的预热里，而不是放在
        // 共同发令之前。
        left = awaitReady(left);
        right = awaitReady(right);

        assertTrue(left.process().waitFor(240, TimeUnit.SECONDS),
                "the left process must finish; stderr: " + tail(left.err()));
        assertTrue(right.process().waitFor(240, TimeUnit.SECONDS),
                "the right process must finish; stderr: " + tail(right.err()));
        assertEquals(0, left.process().exitValue(), "left exited; stderr: " + tail(left.err()));
        assertEquals(0, right.process().exitValue(), "right exited; stderr: " + tail(right.err()));
        assertTrue(left.readyAt() > 0 && left.readyAt() < startAt,
                "left was ready before the first gun: " + left);
        assertTrue(right.readyAt() > 0 && right.readyAt() < startAt,
                "right was ready before the first gun: " + right);

        List<RoundResult> leftResults = results(left);
        List<RoundResult> rightResults = results(right);
        assertEquals(ROUNDS, leftResults.size(), "left answered every round: " + leftResults);
        assertEquals(ROUNDS, rightResults.size(), "right answered every round: " + rightResults);

        int leftWins = 0;
        for (int round = 0; round < ROUNDS; round++) {
            RoundResult a = leftResults.get(round);
            RoundResult b = rightResults.get(round);
            assertEquals(round, a.round());
            assertEquals(round, b.round());
            int wins = (a.won() ? 1 : 0) + (b.won() ? 1 : 0);
            assertEquals(1, wins,
                    "round " + round + ": exactly one process may win; left=" + a + " right=" + b);
            RoundResult loser = a.won() ? b : a;
            assertEquals(409, loser.status(),
                    "round " + round + ": the losing process is refused: " + loser);
            assertTrue(loser.reason() != null && loser.reason().startsWith("already_closed"),
                    "round " + round + ": and told already closed: " + loser.reason());
            if (a.won()) {
                leftWins++;
            }

            WriteClaim hold = holds.get(round);
            String winnerNote = a.won() ? leftNotes.get(round) : rightNotes.get(round);
            String loserNote = a.won() ? rightNotes.get(round) : leftNotes.get(round);
            assertEquals(WriteClaim.CLOSED_UNKNOWN,
                    claimRows.findById(hold.getId()).orElseThrow().getStatus(),
                    "round " + round + ": the hold is closed_unknown once");
            List<AuditLog> rows = rowsFor(hold.getId());
            assertEquals(1, rows.size(),
                    "round " + round + ": one row on the chain, got " + rows);
            assertTrue(rows.get(0).getDetail().contains(winnerNote),
                    "round " + round + ": the row belongs to the winning process: "
                            + rows.get(0).getDetail());
            assertFalse(rows.get(0).getDetail().contains(loserNote),
                    "round " + round + ": the losing process wrote nothing: "
                            + rows.get(0).getDetail());

            ResponseStatusException retried = refusal(hold.getId(), loserNote);
            assertTrue(retried.getReason() != null
                            && retried.getReason().startsWith("already_closed"),
                    "round " + round + ": the loser's retry is the same refusal: "
                            + retried.getReason());
            assertEquals(1, rowsFor(hold.getId()).size(),
                    "round " + round + ": a refused retry writes no second row");
        }

        assertTrue(audit.verify().valid(), "the chain stays one valid line across two processes");

        long elapsed = System.currentTimeMillis() - started;
        System.out.println("claim_release cross-process race on postgresql: " + ROUNDS
                + " rounds, two JVMs (pids " + left.pid() + " and " + right.pid() + ", ready "
                + (startAt - left.readyAt()) + " / " + (startAt - right.readyAt())
                + " ms before the first gun), winners left=" + leftWins + " right="
                + (ROUNDS - leftWins) + ", elapsed " + elapsed + " ms");
    }

    /** Launch one child with the same claims and start gun, and its own notes. */
    private Child launch(String name, Path logs, List<WriteClaim> holds, List<String> notes,
                         long startAt) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        List<String> command = new ArrayList<>(List.of(
                java,
                "-cp", System.getProperty("java.class.path"),
                CrossProcessReleaseHarness.class.getName(),
                String.valueOf(ROUNDS),
                holds.stream().map(hold -> String.valueOf(hold.getId())).reduce((a, b) -> a + "," + b)
                        .orElseThrow(),
                String.join(",", notes),
                String.valueOf(startAt),
                String.valueOf(SPACING_MILLIS)));
        Path out = logs.resolve(name + ".log");
        Path err = logs.resolve(name + ".err");
        Process process = new ProcessBuilder(command)
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        return new Child(name, process, out, err, process.pid(), 0);
    }

    /** Wait until the child's context is up and it has said so before the shared gun. */
    private Child awaitReady(Child child) throws InterruptedException {
        String line = awaitLine(child.out(), "READY ", 120_000L);
        Matcher ready = READY.matcher(line);
        assertTrue(ready.matches(), "the child said when it was ready: " + line);
        assertTrue(ready.group(4).startsWith("jdbc:postgresql:"),
                "the child must race on the shared PostgreSQL, not on its own embedded H2: " + line);
        return new Child(child.name(), child.process(), child.out(), child.err(),
                Long.parseLong(ready.group(1)), Long.parseLong(ready.group(2)));
    }

    /** Poll the child's log for the marker; the child writes it as soon as its context is up. */
    private static String awaitLine(Path log, String prefix, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<String> lines = readLines(log);
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("no '" + prefix + "' line within " + timeoutMillis
                + " ms; log: " + tail(log));
    }

    private static List<RoundResult> results(Child child) {
        List<RoundResult> results = new ArrayList<>();
        for (String line : readLines(child.out())) {
            Matcher result = RESULT.matcher(line);
            if (result.matches()) {
                results.add(new RoundResult(
                        Integer.parseInt(result.group(1)),
                        Boolean.parseBoolean(result.group(2)),
                        Integer.parseInt(result.group(3)),
                        result.group(4)));
            }
        }
        return results;
    }

    private record RoundResult(int round, boolean won, int status, String reason) {
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path).stream().map(String::trim).toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    private static String tail(Path path) {
        List<String> lines = readLines(path);
        int from = Math.max(0, lines.size() - 12);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    private ResponseStatusException refusal(Long claimId, String note) {
        try {
            releases.release(claimId, new Principal("carol", Principal.ROLE_ADMIN), note, null);
        } catch (ResponseStatusException refused) {
            return refused;
        }
        throw new AssertionError("the retry should have been refused");
    }

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

    private List<AuditLog> rowsFor(Long claimId) {
        return auditRows.findAll().stream()
                .filter(row -> "claim_release".equals(row.getAction()))
                .filter(row -> row.getDetail() != null
                        && row.getDetail().contains("#" + claimId + " "))
                .toList();
    }
}
